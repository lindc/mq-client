package com.mqclient.mq.base

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.scheduler.ScheduledJob
import com.mqclient.core.spi.ServiceContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * 连接管理基类的生命周期契约。
 *
 * 这份测试是四个 MQ 模块的**共同底线**：基类现在承载 connect 状态机、每连接锁、
 * 扫描任务注册、连续失败阈值与自愈。子类只要按约定实现 `open` / `closeResources`，
 * 行为就应当一致 —— 所以这里用假实现钉住模板的顺序与边界。
 */
class AbstractConnectionManagerTest {

    /** 假上下文：句柄就是一个可观察的字符串。 */
    private class FakeContext(
        connectionId: String,
        config: FakeConfig,
    ) : AbstractConnectionContext<FakeConfig>(connectionId, config) {
        @Volatile
        var handle: String? = null

        override fun cleanup() {
            handle = null
            super.cleanup()
        }
    }

    private data class FakeConfig(val failOpen: Boolean = false, val scanIntervalSeconds: Int = 10)

    /** 记录调用顺序，验证模板顺序（open → onConnected → Connected → 注册任务）。 */
    private class FakeManager(context: ServiceContext) :
        AbstractConnectionManager<FakeContext>(context, "fake-mq") {

        val calls = mutableListOf<String>()
        var openCount = 0
        var closeCount = 0
        var failNextOpen = false

        override fun createContext(connectionId: String, connection: ConnectionInfo) =
            FakeContext(connectionId, FakeConfig())

        override fun applyConfig(ctx: FakeContext, connection: ConnectionInfo) {
            calls += "applyConfig"
        }

        override suspend fun open(ctx: FakeContext) {
            openCount++
            calls += "open"
            if (failNextOpen) throw IllegalStateException("boom")
            ctx.handle = "handle-${ctx.connectionId}"
        }

        override suspend fun closeResources(ctx: FakeContext) {
            closeCount++
            calls += "closeResources"
            ctx.handle = null
        }

        override fun scanJobs(ctx: FakeContext): List<ScheduledJob> = listOf(
            ScheduledJob(
                id = ScheduledJob.idFor(moduleId, ctx.connectionId, "scan.fake"),
                intervalMs = 60_000L,
                // 不真的跑：这里只验证「注册过」。
                action = { },
            ),
        )

        override suspend fun onConnected(ctx: FakeContext) {
            calls += "onConnected"
            assertNotNull(ctx.cache, "onConnected 必须在 cache 创建之后调用")
        }

        override suspend fun onBeforeDisconnect(ctx: FakeContext) {
            calls += "onBeforeDisconnect"
        }

        override fun isHandlePresent(ctx: FakeContext): Boolean = ctx.handle != null

        override val connectedEvent: String = "fake.connected"
        override val connectFailedEvent: String = "fake.connect.failed"
        override val disconnectedEvent: String = "fake.disconnected"
    }

    private fun newManager(): Pair<FakeManager, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus(scope)
        val context = ServiceContext.of("fake-mq", bus, scope, JobScheduler(scope, bus))
        return FakeManager(context) to scope
    }

    private fun connection(id: String = "c1") = ConnectionInfo(
        id = id,
        name = id,
        serviceId = "fake-mq",
        mqName = "QM1",
        host = "localhost",
        port = 1414,
    )

    @Test
    fun `ensureContext only registers and stays disconnected`() {
        val (manager, scope) = newManager()
        try {
            val ctx = manager.ensureContext(connection())

            assertEquals(ConnectionStatus.Disconnected, ctx.status)
            assertNull(ctx.handle, "登记阶段绝不能建句柄")
            assertEquals(0, manager.openCount)
            // 幂等：同 id 再登记一次返回同一实例。
            assertTrue(ctx === manager.ensureContext(connection()))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `connect runs the documented order`() = runBlocking {
        val (manager, scope) = newManager()
        try {
            manager.ensureContext(connection())
            val result = manager.connect("c1")

            assertTrue(result.isSuccess, "actual: $result")
            assertEquals(listOf("closeResources", "open", "onConnected"), manager.calls)
            assertEquals(ConnectionStatus.Connected, manager.contextOrNull("c1")?.status)
            assertNotNull(manager.contextOrNull("c1")?.cache, "连接成功必须建 cache")
            // JUnit4 要求测试方法返回 void：末句是 assertNotNull（返回 T），这里收成 Unit。
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `connect is idempotent while connected and refuses while connecting`() = runBlocking {
        val (manager, scope) = newManager()
        try {
            manager.ensureContext(connection())
            manager.connect("c1")
            val opensAfterFirst = manager.openCount

            // 已连接：直接成功，不再建句柄。
            assertTrue(manager.connect("c1").isSuccess)
            assertEquals(opensAfterFirst, manager.openCount)

            // 连接中：拒绝（防重复点击）。
            manager.contextOrNull("c1")!!.status = ConnectionStatus.Connecting
            val refused = manager.connect("c1")
            assertTrue(refused.isFailure)
            val ex = refused.exceptionOrNull()
            assertTrue(
                ex is com.mqclient.core.ServiceException && ex.errorKey == "err_conn_in_progress",
                "连接中应拒绝且键正确，实际 $ex",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `connect failure marks failed and keeps the reason`() = runBlocking {
        val (manager, scope) = newManager()
        try {
            manager.ensureContext(connection())
            manager.failNextOpen = true

            val result = manager.connect("c1")

            assertTrue(result.isFailure)
            val ctx = manager.contextOrNull("c1")!!
            assertEquals(ConnectionStatus.Failed, ctx.status)
            assertEquals("boom", ctx.lastError)
            assertNull(ctx.cache, "失败不能留下缓存")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `disconnect closes handle clears cache and is idempotent`() = runBlocking {
        val (manager, scope) = newManager()
        try {
            manager.ensureContext(connection())
            manager.connect("c1")
            val ctx = manager.contextOrNull("c1")!!
            ctx.recordScanFailure()

            manager.disconnectNow("c1", reason = "test")

            assertEquals(ConnectionStatus.Disconnected, ctx.status)
            assertNull(ctx.handle)
            assertNull(ctx.cache)
            assertEquals(0, ctx.scanFailureCount(), "断开必须把失败计数归零")
            assertTrue("onBeforeDisconnect" in manager.calls)

            // 再断一次是空操作（不会重复关句柄）。
            val closes = manager.closeCount
            manager.disconnectNow("c1")
            assertEquals(closes, manager.closeCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scan failures are tolerated twice and fatal on the third`() = runBlocking {
        val (manager, scope) = newManager()
        try {
            manager.ensureContext(connection())
            manager.connect("c1")

            assertFalse(manager.onScanFailed("c1", "e1"))
            assertFalse(manager.onScanFailed("c1", "e2"))
            assertEquals(ConnectionStatus.Connected, manager.contextOrNull("c1")?.status, "两次抖动不判死")
            assertTrue(manager.onScanFailed("c1", "e3"), "第三次触发翻转")
            assertEquals(ConnectionStatus.Failed, manager.contextOrNull("c1")?.status)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scan success recovers a failed connection that still has a handle`() = runBlocking {
        val (manager, scope) = newManager()
        try {
            manager.ensureContext(connection())
            manager.connect("c1")
            repeat(AbstractConnectionManager.SCAN_FAILURE_THRESHOLD) { manager.onScanFailed("c1", "e") }
            val ctx = manager.contextOrNull("c1")!!
            assertEquals(ConnectionStatus.Failed, ctx.status)

            manager.onScanSucceeded("c1")

            assertEquals(ConnectionStatus.Connected, ctx.status, "句柄还在就必须自愈")
            assertNull(ctx.lastError)
            assertEquals(0, ctx.scanFailureCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scan failure without a handle never flips state`() = runBlocking {
        val (manager, scope) = newManager()
        try {
            manager.ensureContext(connection())
            // 没连接（无句柄）：扫描失败不归它管，交给 connect 路径。
            repeat(5) { assertFalse(manager.onScanFailed("c1", "e")) }
            assertEquals(ConnectionStatus.Disconnected, manager.contextOrNull("c1")?.status)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `shutdownAll disconnects every connection`() = runBlocking {
        val (manager, scope) = newManager()
        try {
            manager.ensureContext(connection("c1"))
            manager.ensureContext(connection("c2"))
            manager.connect("c1")
            manager.connect("c2")

            manager.shutdownAll()

            assertTrue(manager.allContexts().isEmpty(), "注册表要清空")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `threshold constant is shared by every module`() {
        // 各模块的 SCAN_FAILURE_THRESHOLD 常量都指向这里（见各 Module.kt）。
        assertEquals(3, AbstractConnectionManager.SCAN_FAILURE_THRESHOLD)
    }
}
