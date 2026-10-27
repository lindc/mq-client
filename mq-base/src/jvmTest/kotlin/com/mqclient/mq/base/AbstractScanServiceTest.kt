package com.mqclient.mq.base

import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * 扫描服务骨架的契约（三个 MQ 模块的共同底线）。
 *
 * 抽这个基类是因为三份 `*ScanService` 里有一整段逐字重复的骨架：扫描锁、手动刷新
 * 任务、TTL 公式、缓存取用、失败记账、单类兜底、全类失败判定。名字还不统一
 * （`safely` / `scanTypeOr` / `scanKindOr` 是同一个东西），读代码的人得先确认它们
 * 真的一样。
 *
 * 这里用假实现钉住模板语义，重点在**最容易悄悄坏掉**的两条：
 *  1. 取消绝不能变成「这一类扫空了」（`scanKindOr`）；
 *  2. 单类失败不能判连接已断（`isConnectionBroken`）。
 */
class AbstractScanServiceTest {

    private enum class FakeKind(val label: String) { TOPICS("topics"), GROUPS("groups") }

    private class FakeContext(connectionId: String) : AbstractConnectionContext<String>(connectionId, "cfg")

    private class FakeScanService(context: ServiceContext) :
        AbstractScanService<FakeContext, FakeKind>(context, "fake-mq") {

        val cleared = mutableListOf<String>()
        val reportedFailures = mutableListOf<Pair<String, Set<FakeKind>>>()
        val reportedFailureMessages = mutableListOf<Pair<String, String>>()
        val scanned = AtomicInteger(0)
        val observedEvent = AtomicBoolean(false)

        override fun contextOrNull(connectionId: String): FakeContext? =
            FakeContext(connectionId).takeIf { connectionId == "c1" }

        override suspend fun scanObjects(connectionId: String) {
            scanned.incrementAndGet()
        }

        override val allKinds: Collection<FakeKind> = FakeKind.entries

        override fun describeKind(kind: FakeKind): String = kind.label

        override fun objectsScanIntervalMs(ctx: FakeContext): Long = 60_000
        override fun fastScanIntervalMs(ctx: FakeContext): Long = 10_000

        override fun reportScanFailure(connectionId: String, message: String): Boolean {
            reportedFailureMessages += connectionId to message
            return true
        }

        override fun reportScanFailures(connectionId: String, kinds: Set<FakeKind>) {
            reportedFailures += connectionId to kinds
        }

        override fun clearScanFailures(connectionId: String) {
            cleared += connectionId
        }

        override val objectsScanFailedEvent: String = "fake.objects.failed"

        // 基类把 TTL / cache / 收尾钩子声明为 protected（子类内部用）；测试替身
        // 把它们暴露出来，断言才不用绕一圈再观察行为。
        public fun ttlOf(ctx: FakeContext): Pair<Long, Long> = objectsTtlMs(ctx) to fastTtlMs(ctx)
        public fun cacheOfConnection(connectionId: String) = cacheOf(connectionId)
        public fun reportAllFailed(connectionId: String, failures: Map<FakeKind, String>) =
            reportAllKindsFailed(connectionId, failures)
    }

    private fun newService(): Pair<FakeScanService, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus(scope)
        val context = ServiceContext.of("fake-mq", bus, scope, JobScheduler(scope, bus))
        return FakeScanService(context) to scope
    }

    /* ------------------------------ 单类兜底 ------------------------------ */

    @Test
    fun `scanKindOr records the failure and returns the fallback`() = runBlocking {
        val (service, scope) = newService()
        try {
            val failures = mutableMapOf<FakeKind, String>()

            val rows = service.scanKindOr(failures, FakeKind.TOPICS, emptyList<String>()) {
                throw IllegalStateException("ACL 拒绝")
            }

            assertTrue(rows.isEmpty(), "兜底应给空列表，不往上抛")
            assertEquals("ACL 拒绝", failures[FakeKind.TOPICS])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scanKindOr never swallows cancellation`() = runBlocking {
        val (service, scope) = newService()
        try {
            val failures = mutableMapOf<FakeKind, String>()

            assertFailsWith<CancellationException> {
                service.scanKindOr(failures, FakeKind.GROUPS, emptyList<String>()) {
                    throw CancellationException("用户点了停止")
                }
            }
            assertTrue(failures.isEmpty(), "取消不该被记成「这一类失败了」")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `successful scanKindOr records nothing`() = runBlocking {
        val (service, scope) = newService()
        try {
            val failures = mutableMapOf<FakeKind, String>()
            val rows = service.scanKindOr(failures, FakeKind.TOPICS, emptyList<String>()) {
                listOf("orders")
            }

            assertEquals(listOf("orders"), rows)
            assertTrue(failures.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    /* ------------------------------ 断连判定 ------------------------------ */

    @Test
    fun `one kind failing is not a broken connection`() {
        val (service, scope) = newService()
        try {
            assertFalse(service.isConnectionBroken(setOf(FakeKind.TOPICS)))
            assertFalse(service.isConnectionBroken(emptyList()))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `all kinds failing means broken`() {
        val (service, scope) = newService()
        try {
            assertTrue(service.isConnectionBroken(FakeKind.entries.toSet()))
        } finally {
            scope.cancel()
        }
    }

    /* ------------------------------ TTL ------------------------------ */

    @Test
    fun `ttl factors are applied uniformly`() {
        val (service, scope) = newService()
        try {
            val ctx = FakeContext("c1")
            // 慢扫 60s × 1.5 = 90s；快扫 10s × 2 = 20s。
            assertEquals(90_000L to 20_000L, service.ttlOf(ctx))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cache lookup goes through the connection context`() {
        val (service, scope) = newService()
        try {
            // c1 有上下文（cache 未建 → null）；c2 未登记。
            assertEquals(null, service.cacheOfConnection("c1"))
            assertEquals(null, service.cacheOfConnection("c2"))
        } finally {
            scope.cancel()
        }
    }

    /* ------------------------------ 全类失败收尾 ------------------------------ */

    @Test
    fun `reportAllKindsFailed clears flags emits and marks the connection`() {
        val (service, scope) = newService()
        try {
            val broke = service.reportAllFailed(
                "c1",
                mapOf(FakeKind.TOPICS to "boom", FakeKind.GROUPS to "bang"),
            )

            assertTrue(broke, "返回 true 便于调用方直接 return")
            assertEquals(listOf("c1"), service.cleared, "整体不可用时「部分失败」结论要清掉")
            assertEquals(listOf("c1" to "boom"), service.reportedFailureMessages, "取第一条原因记账")
        } finally {
            scope.cancel()
        }
    }

    /* ------------------------------ 手动扫描 ------------------------------ */

    @Test
    fun `launchManualScan runs scanObjects for that connection`() = runBlocking {
        val (service, scope) = newService()
        try {
            service.launchManualScan("c1")

            // 手动扫描是 fire-and-forget：等内容出现（有界等待，避免挂死）。
            val deadline = System.currentTimeMillis() + 2_000
            while (service.scanned.get() == 0 && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(10)
            }
            assertEquals(1, service.scanned.get(), "应当触发一次 scanObjects")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelScan is safe when nothing is running`() {
        val (service, scope) = newService()
        try {
            service.cancelScan("c1") // 不应抛
        } finally {
            scope.cancel()
        }
    }
}
