package com.mqclient.kafka

import com.mqclient.core.cache.CacheManager
import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * **对象慢扫的编排语义**（真实路径要 AdminClient + 活连接，这里注入假 scanner）。
 *
 * 这三条是整套扫描设计的地基，此前只有零散的单点测试（`scanKindOr` 单独测、
 * `putIfScanned` 单独测），没有一条测试**把它们串起来**跑一遍真实编排：
 *
 *  1. 单类失败不影响其他类（并发隔离）；
 *  2. 全部失败才判连接不可用；
 *  3. 缓存只写**本轮真的扫到**的类型（失败的类型保留上一轮结果）。
 *
 * 第 3 条是最要紧的：把空列表写进缓存等于告诉 UI「这个集群上没有 topic」，
 * 导航树会被整片清空 —— 而真相只是「这次没读到」。
 */
class KafkaObjectScanOrchestrationTest {

    /**
     * 等事件投递完成。
     *
     * `EventBus.emitSync` 只是往 SharedFlow 里 emit —— 投递到订阅者仍是异步的。
     * 屏障事件（后发的 flush）在同一订阅者上保序，看到它就说明之前的事件都已送达。
     */
    private suspend fun awaitFlush(flushed: java.util.concurrent.atomic.AtomicBoolean) {
        withTimeout(2_000) {
            while (!flushed.get()) kotlinx.coroutines.delay(5)
        }
    }

    private fun newContext(scope: CoroutineScope): ServiceContext {
        val bus = EventBus(scope)
        return ServiceContext.of("kafka", bus, scope, JobScheduler(scope, bus))
    }

    private fun broker(id: Int) = KafkaBrokerSnapshot(
        brokerId = id,
        host = "broker-$id",
        port = 9092,
        rack = null,
        isController = id == 0,
    )

    private fun topic(name: String) = KafkaTopicSnapshot(name = name, internal = false, partitions = emptyList())

    private fun group(id: String) = KafkaGroupSnapshot(
        groupId = id,
        state = "Stable",
        members = 0,
        simple = false,
        subscribedTopics = emptySet(),
    )

    private val okBrokers = ClusterScan(
        brokers = listOf(broker(0), broker(1)),
        cluster = KafkaClusterSnapshot(clusterId = "cluster-A", controllerId = 0),
    )

    /* --------------------------- 1. 单类失败不影响其他类 --------------------------- */

    @Test
    fun `one failing kind does not affect the others`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = KafkaScanService(newContext(scope))
        val cache = CacheManager()
        try {
            service.runObjectScan(
                connectionId = "c1",
                cache = cache,
                objectsTtl = 60_000,
                scanBrokers = { okBrokers },
                scanTopics = { throw IllegalStateException("ACL 拒绝") },
                scanGroups = { listOf(group("cg-1")) },
            )

            // 成功的两类照常入缓存。
            assertEquals(listOf(0, 1), cache.getAs<List<KafkaBrokerSnapshot>>("brokers")!!.map { it.brokerId })
            assertEquals(listOf("cg-1"), cache.getAs<List<KafkaGroupSnapshot>>("groups")!!.map { it.groupId })
            // 失败的那类不入缓存（不是写了空列表）。
            assertEquals(null, cache.getAs<List<KafkaTopicSnapshot>>("topics"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `three kinds really run concurrently`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = KafkaScanService(newContext(scope))
        val cache = CacheManager()
        try {
            // 三类各自先报到、再等第三类也报到 —— 串行执行会在这里超时。
            val arrived = CompletableDeferred<Unit>()
            var count = 0
            val gate: suspend () -> Unit = {
                synchronized(arrived) {
                    count++
                    if (count == 3) arrived.complete(Unit)
                }
                withTimeout(2_000) { arrived.await() }
            }

            service.runObjectScan(
                connectionId = "c1",
                cache = cache,
                objectsTtl = 60_000,
                scanBrokers = { gate(); okBrokers },
                scanTopics = { gate(); listOf(topic("orders")) },
                scanGroups = { gate(); listOf(group("cg-1")) },
            )

            assertEquals(3, count, "三类必须同时在飞（串行会卡在 gate 上超时）")
            assertTrue(cache.getAs<List<KafkaTopicSnapshot>>("topics")!!.isNotEmpty())
        } finally {
            scope.cancel()
        }
    }

    /* --------------------------- 2. 全失败才判死 --------------------------- */

    @Test
    fun `partial failure emits a partial event and never marks the connection failed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val context = newContext(scope)
        val service = KafkaScanService(context)
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val sink: suspend (com.mqclient.core.event.AppEvent) -> Unit = { event ->
            @Suppress("UNCHECKED_CAST")
            events += event.name to (event.payload as Map<String, Any?>)
        }
        try {
            // 订阅两类结果事件：partial 走 SCANNED，全灭走 SCAN_FAILED。
            context.eventBus.on(KafkaEvents.OBJECTS_SCANNED, scope, sink)
            context.eventBus.on(KafkaEvents.OBJECTS_SCAN_FAILED, scope, sink)
            val flushed = java.util.concurrent.atomic.AtomicBoolean(false)
            context.eventBus.on("test.flush", scope) { flushed.set(true) }

            service.runObjectScan(
                connectionId = "c1",
                cache = CacheManager(),
                objectsTtl = 60_000,
                scanBrokers = { okBrokers },
                scanTopics = { throw IllegalStateException("boom") },
                scanGroups = { throw IllegalStateException("bang") },
            )
            context.eventBus.emitSync("test.flush", emptyMap())
            awaitFlush(flushed)

            val scanned = events.filter { it.first == KafkaEvents.OBJECTS_SCANNED }
            assertTrue(scanned.isNotEmpty(), "部分失败应发 SCANNED（带 partial=true），实际：$events")
            assertEquals(true, scanned.last().second["partial"])
            assertEquals(
                listOf("groups", "topics"),
                (scanned.last().second["failedKinds"] as List<*>).map { it.toString() }.sorted(),
                "failedKinds 用 kind.label（与 UI 提示条同一口径）",
            )
            assertTrue(
                events.none { it.first == KafkaEvents.OBJECTS_SCAN_FAILED },
                "两类成功一类失败不能判死：$events",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `all kinds failing emits the failure event instead of a partial one`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val context = newContext(scope)
        val service = KafkaScanService(context)
        val events = mutableListOf<String>()
        try {
            context.eventBus.on(KafkaEvents.OBJECTS_SCANNED, scope) { events += KafkaEvents.OBJECTS_SCANNED }
            context.eventBus.on(KafkaEvents.OBJECTS_SCAN_FAILED, scope) { events += KafkaEvents.OBJECTS_SCAN_FAILED }
            val flushed = java.util.concurrent.atomic.AtomicBoolean(false)
            context.eventBus.on("test.flush", scope) { flushed.set(true) }

            service.runObjectScan(
                connectionId = "c1",
                cache = CacheManager(),
                objectsTtl = 60_000,
                scanBrokers = { throw IllegalStateException("down") },
                scanTopics = { throw IllegalStateException("down") },
                scanGroups = { throw IllegalStateException("down") },
            )
            context.eventBus.emitSync("test.flush", emptyMap())
            awaitFlush(flushed)

            assertEquals(listOf(KafkaEvents.OBJECTS_SCAN_FAILED), events, "全部失败只发 SCAN_FAILED（不发 partial 的 SCANNED）")
        } finally {
            scope.cancel()
        }
    }

    /* --------------------------- 3. 缓存只写扫到的类型 --------------------------- */

    @Test
    fun `failed kind keeps the previous round's cache instead of an empty list`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = KafkaScanService(newContext(scope))
        val cache = CacheManager()
        try {
            // 上一轮成功扫到的 topic 列表。
            cache.put("topics", listOf(topic("orders"), topic("payments")), ttlMs = 60_000)

            service.runObjectScan(
                connectionId = "c1",
                cache = cache,
                objectsTtl = 60_000,
                scanBrokers = { okBrokers },
                scanTopics = { throw IllegalStateException("这次超时了") },
                scanGroups = { emptyList() },
            )

            val topics = cache.getAs<List<KafkaTopicSnapshot>>("topics")!!
            assertEquals(
                listOf("orders", "payments"),
                topics.map { it.name },
                "失败的类必须保留上一轮结果 —— 写空列表等于告诉 UI「集群上没 topic」",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `successful kind overwrites the previous round`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = KafkaScanService(newContext(scope))
        val cache = CacheManager()
        try {
            cache.put("topics", listOf(topic("stale")), ttlMs = 60_000)

            service.runObjectScan(
                connectionId = "c1",
                cache = cache,
                objectsTtl = 60_000,
                scanBrokers = { okBrokers },
                scanTopics = { listOf(topic("fresh")) },
                scanGroups = { emptyList() },
            )

            assertEquals(listOf("fresh"), cache.getAs<List<KafkaTopicSnapshot>>("topics")!!.map { it.name })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cluster metadata is only written when brokers were really scanned`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = KafkaScanService(newContext(scope))
        val cache = CacheManager()
        try {
            // broker 类失败 → 兜底的空 ClusterScan 不能覆盖上一轮的 clusterId。
            cache.put("cluster", KafkaClusterSnapshot(clusterId = "previous", controllerId = 1), ttlMs = 60_000)

            service.runObjectScan(
                connectionId = "c1",
                cache = cache,
                objectsTtl = 60_000,
                scanBrokers = { throw IllegalStateException("describeCluster failed") },
                scanTopics = { listOf(topic("orders")) },
                scanGroups = { emptyList() },
            )

            assertEquals(
                "previous",
                cache.getAs<KafkaClusterSnapshot>("cluster")!!.clusterId,
                "broker 类失败时不能让兜底空快照覆盖集群元信息",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `null cache does not crash the orchestration`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = KafkaScanService(newContext(scope))
        try {
            // 未连接（cache 为 null）时仍要发事件，只是不写缓存。
            service.runObjectScan(
                connectionId = "c1",
                cache = null,
                objectsTtl = 60_000,
                scanBrokers = { okBrokers },
                scanTopics = { emptyList() },
                scanGroups = { emptyList() },
            )
            // 走到这里没抛就算通过（cache 为 null 时 putIfScanned 静默跳过）。
        } finally {
            scope.cancel()
        }
    }
}
