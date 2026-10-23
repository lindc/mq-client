package com.mqclient.rabbitmq

import com.mqclient.core.cache.CacheManager
import com.mqclient.core.cache.allKindsFailed
import com.mqclient.core.cache.putIfScanned
import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * 对象慢扫的**失败隔离**契约（不碰网络、不碰真集群）。
 *
 * 这一组测试盯的是一个用户可见的误判：只带 `management` tag 的只读账号能读
 * exchanges / queues / bindings，但 `GET /api/nodes` 要 `monitor` tag 会 403。
 * 曾经的写法「任一类失败整轮失败」会把这个完全可用的账号标成
 * `ConnectionStatus.Failed`，界面全红、点重连也没用。
 */
class RabbitMqScanFailureIsolationTest {

    @Test
    fun `a single failing type does not count as a broken connection`() {
        // nodes 失败 = 只读账号的常态，不是连接故障。
        assertTrue(
            !isConnectionBroken(setOf(RabbitMqScanType.NODES)),
            "单类失败不该判定连接已断",
        )
        assertTrue(
            !isConnectionBroken(setOf(RabbitMqScanType.QUEUES, RabbitMqScanType.BINDINGS)),
            "部分失败不该判定连接已断",
        )
    }

    @Test
    fun `no failures is not a broken connection`() {
        assertTrue(!isConnectionBroken(emptySet()), "全成功当然不算断")
    }

    @Test
    fun `all four failing means the connection is broken`() {
        assertTrue(
            isConnectionBroken(RabbitMqScanType.entries.toSet()),
            "四类全读不通才是连接真的断了",
        )
    }

    @Test
    fun `failed type keeps the previous cache, successful type is replaced`() {
        val cache = CacheManager()
        cache.put("queues", listOf("上一轮的队列"))
        cache.put("nodes", listOf("上一轮的节点"))
        val failures = mapOf(RabbitMqScanType.NODES to "HTTP 403")

        // 本轮：队列扫到了（新值），节点被拒（空表）。
        cache.putIfScanned("queues", RabbitMqScanType.QUEUES, failures, listOf("新队列"), 60_000)
        cache.putIfScanned("nodes", RabbitMqScanType.NODES, failures, emptyList<String>(), 60_000)

        assertEquals(listOf("新队列"), cache.getAs<List<String>>("queues"), "成功的类型应当被替换")
        assertEquals(
            listOf("上一轮的节点"),
            cache.getAs<List<String>>("nodes"),
            "失败的类型不能拿空表覆盖上一轮结果",
        )
    }

    @Test
    fun `empty failure map writes every type`() {
        val cache = CacheManager()
        val failures = emptyMap<RabbitMqScanType, String>()

        cache.putIfScanned("bindings", RabbitMqScanType.BINDINGS, failures, listOf("b1"), 60_000)

        assertEquals(listOf("b1"), cache.getAs<List<String>>("bindings"))
    }

    @Test
    fun `null cache is tolerated`() {
        // 连接断开的窗口里 cacheOf 可能为 null；不能因此抛异常。
        // （这一条也是 app-core 的 ScannedCacheTest 覆盖的公共契约，这里保留是因为
        // service 侧真的会传 null 进来。）
        val cache: CacheManager? = null
        cache.putIfScanned("queues", RabbitMqScanType.QUEUES, emptyMap(), "v", ttlMs = 1_000)
    }

    @Test
    fun `scanTypeOr records the failure and returns the fallback`() = runBlocking {
        val failures = java.util.concurrent.ConcurrentHashMap<RabbitMqScanType, String>()
        val service = RabbitMqScanService(context())

        val rows = service.scanTypeOr(failures, RabbitMqScanType.NODES, emptyList<String>()) {
            throw IllegalStateException("HTTP 403 from /api/nodes")
        }

        assertTrue(rows.isEmpty(), "兜底应当给空列表，不往上抛")
        assertEquals(
            "HTTP 403 from /api/nodes",
            failures[RabbitMqScanType.NODES],
            "失败原因要留档（四类全失败时用它给用户看）",
        )
    }

    @Test
    fun `scanTypeOr does not swallow cancellation`() = runBlocking {
        val failures = java.util.concurrent.ConcurrentHashMap<RabbitMqScanType, String>()
        val service = RabbitMqScanService(context())

        assertFailsWith<CancellationException> {
            service.scanTypeOr(failures, RabbitMqScanType.QUEUES, emptyList<String>()) {
                throw CancellationException("用户点了停止")
            }
        }
        assertTrue(failures.isEmpty(), "取消不是「这个类失败」，不该登记")
    }

    @Test
    fun `successful scanTypeOr records nothing`() = runBlocking {
        val failures = java.util.concurrent.ConcurrentHashMap<RabbitMqScanType, String>()
        val service = RabbitMqScanService(context())

        val rows = service.scanTypeOr(failures, RabbitMqScanType.EXCHANGES, emptyList<String>()) {
            listOf("amq.direct")
        }

        assertEquals(listOf("amq.direct"), rows)
        assertTrue(failures.isEmpty())
    }

    /**
     * 只为了拿一个 `scanTypeOr` 宿主：上面几个用例都不碰 `context`，
     * 所以用 [ServiceContext.of] 拼一个空壳（不需要真模块 / 真连接）。
     */
    private fun context(): ServiceContext {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bus = EventBus(scope)
        return ServiceContext.of(
            moduleId = "rabbitmq-test",
            eventBus = bus,
            parentScope = scope,
            jobScheduler = JobScheduler(scope, bus),
        )
    }
}
