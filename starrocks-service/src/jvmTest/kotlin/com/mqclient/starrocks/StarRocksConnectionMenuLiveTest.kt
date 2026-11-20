package com.mqclient.starrocks

import com.mqclient.core.ConfigStore
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionRegistry
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.EventBus
import com.mqclient.core.event.getBoolean
import com.mqclient.core.event.getLong
import com.mqclient.core.event.getString
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 右键菜单动作的**真机**验证：测试连接 → 连接 → 查询 → 断开，全部走真实的
 * `starrocks.connection.*` 事件路径（不是直接调 facade）。
 *
 * 假连接器只能证明「事件收到了」，证明不了「真的连上了、真的断开了、测试连接的
 * 结果能回到宿主」。这条连本机 StarRocks，按宿主（`ConnectionActionsImpl`）的
 * 方式发事件，观察注册表状态上报与 `test.completed` 事件。
 *
 * 连接来自 `config.toml` 里已有的 StarRocks 连接（密码经 app 自己解密，不经过
 * 命令行），没有就用 `-Dstarrocks.live.*` 拼一条。环境不就绪即跳过
 * （与 `HostKeyLiveTest` 同口径）：
 *   `-Dstarrocks.live.enabled=true`
 */
class StarRocksConnectionMenuLiveTest {

    private val enabled = System.getProperty("starrocks.live.enabled") == "true"
    private val host = System.getProperty("starrocks.live.host") ?: "127.0.0.1"
    private val port = System.getProperty("starrocks.live.port")?.toIntOrNull() ?: 9030
    private val user = System.getProperty("starrocks.live.user") ?: "root"
    private val password = System.getProperty("starrocks.live.password") ?: ""
    private val database = System.getProperty("starrocks.live.database") ?: "test_db"

    private val connectionId = "menu-live"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val bus = EventBus(scope)

    @AfterTest
    fun cleanup() {
        StarRocksModule.dispose()
        bus.cancelAll()
    }

    @Test
    fun `menu connect query disconnect and test use the real session`() = runBlocking {
        if (!enabled) return@runBlocking
        val connection = assertNotNull(resolveConnection(), "没有可用的 StarRocks 连接配置")
        val registry = Recorder(connection)
        StarRocksModule.initialize(context(registry))

        // 1) 测试连接（右键菜单 → 临时探针）
        val testEvent = awaitTestCompleted()
        assertTrue(
            testEvent.getBoolean("success") == true,
            "测试连接应成功，实际 message=${testEvent.getString("message")}",
        )
        println("LIVE test -> ok ${testEvent.getLong("durationMs")}ms")
        // 探针用完即关，不该留下会话。
        assertFalse(StarRocksModule.isAlive(connectionId))

        // 2) 连接（右键菜单 → 共享会话）
        bus.emitSync(StarRocksEvents.CONNECT_REQUESTED, mapOf("connectionId" to connectionId))
        withTimeout(60_000) { registry.statuses.first { it == ConnectionStatus.Connected } }
        assertTrue(StarRocksModule.isAlive(connectionId))

        val rows = mutableListOf<Int>()
        StarRocksModule.executeQuery(connectionId, "SELECT 1").collect { batch -> rows += batch.rows.size }
        assertEquals(1, rows.sum(), "SELECT 1 应返回一行")

        // 3) 断开（右键菜单）—— 物理会话必须真的放掉
        bus.emitSync(StarRocksEvents.DISCONNECT_REQUESTED, mapOf("connectionId" to connectionId))
        withTimeout(60_000) { registry.statuses.first { it == ConnectionStatus.Disconnected } }
        assertFalse(StarRocksModule.isAlive(connectionId), "断开后共享会话不该还在")
        println("LIVE statuses=${registry.all}")

        // 4) 再断一次：无会话时是空操作，但仍要复位状态（重复点击不报错）
        bus.emitSync(StarRocksEvents.DISCONNECT_REQUESTED, mapOf("connectionId" to connectionId))
        assertEquals(2, registry.all.count { it == ConnectionStatus.Disconnected })
    }

    /** 发一次测试请求并等宿主视角的结果事件。 */
    private suspend fun awaitTestCompleted(): AppEvent {
        val completed = CompletableDeferred<AppEvent>()
        val job = bus.on(StarRocksEvents.TEST_COMPLETED, scope) { event ->
            if (event.getString("connectionId") == connectionId) completed.complete(event)
        }
        try {
            bus.emitSync(StarRocksEvents.TEST_REQUESTED, mapOf("connectionId" to connectionId))
            return withTimeout(StarRocksModule.TEST_TIMEOUT_MS + 5_000) { completed.await() }
        } finally {
            job.cancel()
        }
    }

    /**
     * 连接配置来源：优先 `config.toml` 里已有的 StarRocks 连接（密码由 app 自行
     * 解密，不进命令行 / 日志），否则按 `-Dstarrocks.live.*` 拼一条。
     */
    private fun resolveConnection(): ConnectionInfo? {
        val saved = ConfigStore.loadConnections().getOrNull()
            ?.firstOrNull { it.serviceId == StarRocksConstants.PROTOCOL_ID }
        if (saved != null) return saved.copy(id = connectionId, name = "menu-live")
        return ConnectionInfo(
            id = connectionId,
            name = "menu-live",
            serviceId = StarRocksConstants.PROTOCOL_ID,
            mqName = database,
            host = host,
            port = port,
            options = mapOf(
                "host" to host,
                "port" to port.toString(),
                "user" to user,
                "password" to password,
                "database" to database,
            ),
        )
    }

    private fun context(registry: ConnectionRegistry): ServiceContext = ServiceContext.of(
        moduleId = StarRocksConstants.PROTOCOL_ID,
        eventBus = bus,
        parentScope = scope,
        jobScheduler = JobScheduler(scope, bus),
        connectionRegistry = registry,
    )

    private class Recorder(private val connection: ConnectionInfo) : ConnectionRegistry {
        private val list = MutableStateFlow(listOf(connection))
        override val connections = list.asStateFlow()

        val statuses = MutableStateFlow<ConnectionStatus?>(null)
        val all = java.util.concurrent.CopyOnWriteArrayList<ConnectionStatus>()

        override fun findConnection(connectionId: String): ConnectionInfo? =
            connection.takeIf { it.id == connectionId }

        override fun reportStatus(
            connectionId: String,
            status: ConnectionStatus,
            source: String,
        ): Boolean {
            all += status
            statuses.value = status
            return true
        }
    }
}
