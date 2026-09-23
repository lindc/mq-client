package com.mqclient.core

import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.EventNames
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 连接数据的唯一入口：创建 / 编辑 / 删除 → 落盘 → 广播事件，外加模块 → 核心的状态上报。
 *
 * 每个用例都把 `mqclient.config.dir` 重定向到临时目录（同 [ConfigManagerTest]），并接上
 * 真实的事件总线 —— 事件名与 payload 是模块侧的契约（消费方见 `IbmMQModule`），
 * 不能只在声明处存在。
 *
 * handler 跑在 `Dispatchers.Default` 上，所以断言前一律 [awaitEvents] 等投递到位，
 * 收集容器用线程安全的实现。
 */
class ConnectionStoreTest {

    private lateinit var dir: File
    private var previousOverride: String? = null
    private lateinit var scope: CoroutineScope

    /** 本用例收集到的连接事件。 */
    private val events = CopyOnWriteArrayList<AppEvent>()

    @BeforeTest
    fun setUp() {
        previousOverride = System.getProperty(DIR_PROPERTY)
        dir = Files.createTempDirectory("mqclient-connections-").toFile()
        System.setProperty(DIR_PROPERTY, dir.absolutePath)
        // ConnectionStore 是进程级单例（列表在类初始化时就装载了）：换过配置目录之后
        // 重载一次，保证每个用例都从这份空配置开始。
        ConnectionStore.reloadFromDisk()

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        CoreModule.initialize(scope)
        runBlocking {
            CoreModule.eventBus.onAll(scope) { event ->
                if (event.name.startsWith(CONNECTION_PREFIX)) events += event
            }
            // replay = 0：等订阅真正开始收集，否则下面发的事件会被直接丢掉。
            CoreModule.eventBus.awaitSubscribers()
        }
    }

    @AfterTest
    fun tearDown() {
        CoreModule.shutdown()
        scope.cancel()
        val previous = previousOverride
        if (previous == null) System.clearProperty(DIR_PROPERTY) else System.setProperty(DIR_PROPERTY, previous)
        dir.deleteRecursively()
    }

    // ------------------------------------------------------- 创建 / 编辑 / 删除

    @Test
    fun `create persists the connection and broadcasts added`() = runBlocking {
        val result = ConnectionStore.create(connection("mq-01", "生产环境"))

        assertTrue(result.isSuccess)
        assertEquals(listOf("mq-01"), ConnectionStore.connections.value.map { it.id })
        assertEquals(listOf("mq-01"), persisted().map { it.id }, "创建必须落盘，不是只在内存里")

        awaitEvents(1)
        val event = events.single()
        assertEquals(EventNames.CONNECTION_ADDED, event.name)
        assertEquals("mq-01", event.payload["connectionId"])
        assertEquals(MODULE_ID, event.payload["type"], "模块按 type 认领自己的连接")
        assertEquals("生产环境", event.payload["name"])
    }

    @Test
    fun `create with a duplicate id fails without writing`() = runBlocking {
        ConnectionStore.create(connection("mq-01", "first"))
        awaitEvents(1)

        val duplicate = ConnectionStore.create(connection("mq-01", "second"))

        assertTrue(duplicate.isFailure)
        assertEquals(listOf("first"), ConnectionStore.connections.value.map { it.name })
        assertEquals(listOf("first"), persisted().map { it.name })
        delay(50)
        assertEquals(1, events.size, "失败路径不该广播事件")
    }

    @Test
    fun `update re-keys the connection and broadcasts deleted + added`() = runBlocking {
        ConnectionStore.create(connection("mq-01", "first"))
        ConnectionStore.create(connection("mq-02", "second"))

        val result = ConnectionStore.update(connection("mq-01", "renamed"))

        assertTrue(result.isSuccess)
        val ids = ConnectionStore.connections.value.map { it.id }
        assertEquals(2, ids.size)
        assertTrue("mq-01" !in ids, "编辑 = 换发新 id（快照与历史关联一 id 一行），旧 id 不复存在")
        assertEquals(listOf("renamed", "second"), ConnectionStore.connections.value.map { it.name }, "顺序不变")
        assertEquals(listOf("renamed", "second"), persisted().map { it.name })

        awaitEvents(4)
        assertEquals(
            listOf(
                EventNames.CONNECTION_ADDED,
                EventNames.CONNECTION_ADDED,
                EventNames.CONNECTION_ADDED,
                EventNames.CONNECTION_DELETED,
            ),
            events.map { it.name },
            "换 id 由差异广播自然得出：新 id 是 added，旧 id 以 deleted 收尾",
        )
        assertEquals("mq-01", events[3].payload["connectionId"], "旧 id 收到的是删除事件")
        val reIdedId = events[2].payload["connectionId"]
        assertEquals(listOf(reIdedId, "mq-02"), ids, "新 id 接任原位置")
    }

    @Test
    fun `update refuses a serviceId change and unknown ids`() = runBlocking {
        ConnectionStore.create(connection("mq-01", "first"))

        val switched = ConnectionStore.update(connection("mq-01", "first", serviceId = "kafka"))
        val ghost = ConnectionStore.update(connection("mq-99", "ghost"))

        assertTrue(switched.isFailure, "换 serviceId 等于换协议，必须走删除 + 新建")
        assertTrue(ghost.isFailure)
        assertEquals(listOf(MODULE_ID), persisted().map { it.serviceId })
    }

    @Test
    fun `update overwrites every field and persists the re-keyed connection`() = runBlocking {
        ConnectionStore.create(connection("mq-01", "first", options = mapOf("vhost" to "/old")))

        val result = ConnectionStore.update(
            connection(
                "mq-01",
                "renamed",
                options = mapOf("vhost" to "/prod", "channel" to "SYSTEM.DEF.SVRCONN"),
                host = "mq.example.com",
                port = 1415,
                mqName = "QM2",
            ),
        )

        assertTrue(result.isSuccess)
        val stored = persisted().single()
        assertTrue(stored.id != "mq-01", "落盘的就是换过新 id 的那份")
        UUID.fromString(stored.id) // 必须是合法 UUID（解析失败直接抛错）
        assertEquals("renamed", stored.name)
        assertEquals("mq.example.com", stored.host)
        assertEquals(1415, stored.port)
        assertEquals("QM2", stored.mqName)
        assertEquals(mapOf("vhost" to "/prod", "channel" to "SYSTEM.DEF.SVRCONN"), stored.options)
        assertEquals(
            ConnectionStore.connections.value.single().id,
            stored.id,
            "内存快照与磁盘是同一份（新 id）",
        )
    }

    @Test
    fun `update leaves other connections untouched`() = runBlocking {
        ConnectionStore.create(
            connection("mq-01", "first", host = "old.example.com", options = mapOf("vhost" to "/old")),
        )
        ConnectionStore.create(connection("mq-02", "second", host = "other.example.com"))

        ConnectionStore.update(connection("mq-01", "renamed", host = "new.example.com"))

        val (reIded, untouched) = ConnectionStore.connections.value.toList()
        assertEquals("new.example.com", reIded.host)
        assertEquals("mq-02", untouched.id, "别的连接 id 不动")
        assertEquals("other.example.com", untouched.host, "别的连接字段一个字节都不改")
        assertEquals("other.example.com", persisted().last().host)
    }

    @Test
    fun `update always re-keys even when nothing else changed`() = runBlocking {
        ConnectionStore.create(connection("mq-01", "first"))
        awaitEvents(1)
        events.clear()

        val result = ConnectionStore.update(connection("mq-01", "first"))

        assertTrue(result.isSuccess)
        assertTrue(ConnectionStore.connections.value.single().id != "mq-01", "无变化也换新 id：一 id 一行快照是契约")
        awaitEvents(2)
        assertEquals(
            listOf(EventNames.CONNECTION_ADDED, EventNames.CONNECTION_DELETED),
            events.map { it.name },
        )
    }

    @Test
    fun `update failure paths do not broadcast or touch the disk`() = runBlocking {
        ConnectionStore.create(connection("mq-01", "first"))
        awaitEvents(1)
        events.clear()

        ConnectionStore.update(connection("mq-99", "ghost"))

        delay(50)
        assertEquals(0, events.size, "失败路径不发任何事件")
        assertEquals(listOf("mq-01"), persisted().map { it.id }, "失败路径不写盘")
        assertEquals(listOf("first"), ConnectionStore.connections.value.map { it.name }, "内存快照也不动")
    }

    @Test
    fun `delete removes the connection and broadcasts deleted`() = runBlocking {
        ConnectionStore.create(connection("mq-01", "first"))
        ConnectionStore.create(connection("mq-02", "second"))

        val result = ConnectionStore.delete("mq-01")

        assertTrue(result.isSuccess)
        assertEquals(listOf("mq-02"), ConnectionStore.connections.value.map { it.id })
        assertEquals(listOf("mq-02"), persisted().map { it.id })

        awaitEvents(3)
        assertEquals(EventNames.CONNECTION_DELETED, events.last().name)
        assertEquals("mq-01", events.last().payload["connectionId"])

        assertTrue(ConnectionStore.delete("nope").isFailure, "不存在的 id 必须失败、不写盘")
        assertEquals(listOf("mq-02"), persisted().map { it.id })
    }

    @Test
    fun `saveAll broadcasts only the differences`() = runBlocking {
        ConnectionStore.create(connection("mq-01", "first"))
        ConnectionStore.create(connection("mq-02", "second"))
        awaitEvents(2)
        events.clear()

        // 整体替换：mq-02 原样保留（只是换了个位置），mq-01 改了名字，mq-03 是新加的。
        val result = ConnectionStore.saveAll(
            listOf(
                connection("mq-02", "second"),
                connection("mq-01", "renamed"),
                connection("mq-03", "third"),
            ),
        )

        assertTrue(result.isSuccess)
        awaitEvents(2)
        assertEquals(
            listOf(EventNames.CONNECTION_ADDED, EventNames.CONNECTION_UPDATED),
            events.map { it.name },
            "没变的不发事件（哪怕换了位置），删除分支的用例见 delete",
        )
        assertEquals(listOf("mq-03", "mq-01"), events.map { it.payload["connectionId"] })
    }

    // ------------------------------------------------------------- 状态上报

    @Test
    fun `reportStatus updates memory only`() = runBlocking {
        ConnectionStore.create(connection("mq-01", "first"))

        assertTrue(ConnectionStore.reportStatus("mq-01", ConnectionStatus.Connected, MODULE_ID))
        assertEquals(ConnectionStatus.Connected, ConnectionStore.connections.value.single().status)

        assertFalse(ConnectionStore.reportStatus("mq-01", ConnectionStatus.Connected, MODULE_ID), "状态没变不算变化")
        assertFalse(ConnectionStore.reportStatus("ghost", ConnectionStatus.Connected, MODULE_ID), "不存在的连接")
        assertEquals(
            ConnectionStatus.Disconnected,
            persisted().single().status,
            "状态是运行时事实，不落盘",
        )
    }

    @Test
    fun `reload resets the live status to disconnected`() = runBlocking {
        ConnectionStore.create(connection("mq-01", "first"))
        ConnectionStore.reportStatus("mq-01", ConnectionStatus.Connected, MODULE_ID)

        ConnectionStore.reloadFromDisk()

        assertEquals(ConnectionStatus.Disconnected, ConnectionStore.connections.value.single().status)
    }

    @Test
    fun `options round-trip through the config file`() = runBlocking {
        val result = ConnectionStore.create(
            connection("mq-01", "first", options = mapOf("vhost" to "/prod", "channel" to "SYSTEM.DEF.SVRCONN")),
        )

        assertTrue(result.isSuccess)
        assertEquals(
            mapOf("vhost" to "/prod", "channel" to "SYSTEM.DEF.SVRCONN"),
            persisted().single().options,
        )
    }

    // ---------------------------------------------------------------- 工具

    /** 直接读盘（不经过 store）—— 验证「真的落盘了」，而不是「内存里改了」。 */
    private fun persisted(): List<ConnectionInfo> = ConfigStore.loadConnections().getOrThrow()

    private fun connection(
        id: String,
        name: String,
        serviceId: String = MODULE_ID,
        options: Map<String, String> = emptyMap(),
        host: String = "localhost",
        port: Int = 1414,
        mqName: String = "QM1",
    ) = ConnectionInfo(
        id = id,
        name = name,
        serviceId = serviceId,
        mqName = mqName,
        host = host,
        port = port,
        options = options,
    )

    /** 等 [count] 条事件真正投递到位；超时即测试失败。 */
    private suspend fun awaitEvents(count: Int) = withTimeout(5_000L) {
        while (events.size < count) delay(5)
    }

    private companion object {
        const val DIR_PROPERTY = "mqclient.config.dir"

        /** 只收连接事件：别的 `core.*` 事件（如错误上报）不该混进计数断言。 */
        const val CONNECTION_PREFIX = "core.connection."

        /** 测试里的连接都挂在 ibm-mq 名下；`type` 字段断言的就是它。 */
        const val MODULE_ID = "ibm-mq"
    }
}
