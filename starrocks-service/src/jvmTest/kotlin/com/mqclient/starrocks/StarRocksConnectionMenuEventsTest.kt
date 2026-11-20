package com.mqclient.starrocks

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
 * 宿主右键菜单动作的**接线契约**（不碰真数据库、不触网）。
 *
 * 回归的缺陷：StarRocks 只经面板内的隐式连接工作，从未订阅宿主的
 * `starrocks.connection.connect / disconnect / test` —— 右键点「断开连接」「测试连接」
 * 等于往一条没人收的总线上喊话，界面毫无反应。这里钉住三件事：
 *
 *  1. 请求事件名与宿主拼出的 `<serviceId>.connection.*` 一致（PROTOCOL_ID 派生）；
 *  2. 「断开连接」把事实状态上报成 Disconnected（宿主靠这次跳变收 Tab）；
 *  3. 「测试连接」**失败也发** `starrocks.connection.test.completed`（宿主据此弹框），
 *     连接不存在时同样有结果 —— 不允许点了没有任何反馈。
 */
class StarRocksConnectionMenuEventsTest {

    private val connection = ConnectionInfo(
        id = "c1",
        name = "sr-local",
        serviceId = StarRocksConstants.PROTOCOL_ID,
        mqName = "starrocks",
        host = "127.0.0.1",
        port = 9030,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val bus = EventBus(scope)
    private val registry = FakeRegistry(connection)

    @AfterTest
    fun tearDown() {
        StarRocksModule.dispose()
        bus.cancelAll()
    }

    @Test
    fun `event names follow the host convention`() {
        // 宿主发 `${serviceId}.connection.<action>`，serviceId = ServiceAdapter.id。
        assertEquals("starrocks.connection.connect", StarRocksEvents.CONNECT_REQUESTED)
        assertEquals("starrocks.connection.disconnect", StarRocksEvents.DISCONNECT_REQUESTED)
        assertEquals("starrocks.connection.test", StarRocksEvents.TEST_REQUESTED)
        // 宿主按后缀 `.connection.test.completed` 订阅。
        assertTrue(StarRocksEvents.TEST_COMPLETED.endsWith(".connection.test.completed"))
    }

    @Test
    fun `disconnect request closes and reports disconnected`() = runBlocking {
        StarRocksModule.initialize(context())

        bus.emitSync(StarRocksEvents.DISCONNECT_REQUESTED, mapOf("connectionId" to "c1"))

        // 等状态流里出现 Disconnected（事件处理是异步的）
        val reports = withTimeout(TIMEOUT_MS) {
            registry.reports.first { list -> list.any { it.second == ConnectionStatus.Disconnected } }
        }
        assertEquals(listOf("c1" to ConnectionStatus.Disconnected), reports)
    }

    @Test
    fun `test request always answers with a completed event`() = runBlocking {
        StarRocksModule.initialize(context())
        val completed = CompletableDeferred<AppEvent>()
        bus.on(StarRocksEvents.TEST_COMPLETED, scope) { event ->
            if (event.getString("connectionId") == "missing") completed.complete(event)
        }

        // 不存在的连接：不发网络请求，也必须给出结果（否则宿主永远不弹结果框）。
        bus.emitSync(StarRocksEvents.TEST_REQUESTED, mapOf("connectionId" to "missing"))

        val event = withTimeout(TIMEOUT_MS) { completed.await() }
        assertEquals(false, event.getBoolean("success"))
        assertFalse(event.getString("message").isNullOrBlank(), "失败原因不能为空")
        assertEquals(0L, event.getLong("durationMs"))
    }

    private fun context(): ServiceContext = ServiceContext.of(
        moduleId = StarRocksConstants.PROTOCOL_ID,
        eventBus = bus,
        parentScope = scope,
        jobScheduler = JobScheduler(scope, bus),
        connectionRegistry = registry,
    )

    /** 只读连接表 + 状态上报记录。 */
    private class FakeRegistry(private val connection: ConnectionInfo) : ConnectionRegistry {
        private val connectionList = MutableStateFlow(listOf(connection))
        override val connections = connectionList.asStateFlow()

        private val recorded = MutableStateFlow<List<Pair<String, ConnectionStatus>>>(emptyList())
        val reports = recorded.asStateFlow()

        override fun findConnection(connectionId: String): ConnectionInfo? =
            connection.takeIf { it.id == connectionId }

        override fun reportStatus(
            connectionId: String,
            status: ConnectionStatus,
            source: String,
        ): Boolean {
            recorded.value = recorded.value + (connectionId to status)
            return true
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
