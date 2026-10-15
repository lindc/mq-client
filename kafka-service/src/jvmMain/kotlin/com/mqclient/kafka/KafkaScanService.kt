package com.mqclient.kafka

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.cache.CacheManager
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions
import org.apache.kafka.clients.admin.ListTopicsOptions
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.common.TopicCollection
import org.apache.kafka.common.TopicPartition

/**
 * Kafka 扫描服务：定时快扫 / 慢扫 + 缓存写入 + 事件通知，与 ibm-mq 的
 * [IbmMQService] 扫描链路同一套模式（write-through 缓存、事件驱动 UI、失败分级）。
 *
 *  - **对象慢扫** [scanObjects]（默认 10min）：brokers / topics（分区明细）/
 *    groups（状态 + assignment 订阅的 topic）三类**并发**查询；**单类失败只跳过记
 *    日志**（受限账号 ACL 是预期内的），describeCluster 失败才整轮失败 —— 连接转
 *    FAILED。结果全量替换写缓存（顺带清掉集群里已删除的对象）。
 *  - **Lag 心跳** [scanGroupLags]（默认 30s）：每组 current offset（已提交）+
 *    latest offset（listOffsets）→ 分区 / 组级 lag；**单组失败只跳过**；顺带做
 *    topic 名单 / 分区数变化检测（add-or-update 进 `topics` 缓存，全属性等慢扫）。
 *    整轮失败 → 连接转 FAILED；跑通 → reportStatus(Connected) 保活（与 MQ 心跳同款）。
 *
 * 缓存：该连接专属的 [CacheManager]（挂 [KafkaConnectionContext.cache]，连接成功
 * 创建、断开清空）。key：`brokers` / `topics` / `groups` / `groupLags`。读口
 * [cachedBrokers] 等只查缓存、不碰 AdminClient、不挂起。
 *
 * 事件：完成 / 失败都发（KafkaEvents.OBJECTS_SCANNED 等），订阅方（UI）读缓存刷新
 * —— 缓存优先、事件驱动的统一消费模式，与 ibm-mq 完全一致。全程不碰 UI 线程
 * （AdminClient 调用在 Dispatchers.IO）。
 */
class KafkaScanService(private val context: ServiceContext) {

    private companion object {
        /** 缓存 key：broker 列表。 */
        const val KEY_BROKERS = "brokers"

        /** 缓存 key：topic 列表（含分区明细）。 */
        const val KEY_TOPICS = "topics"

        /** 缓存 key：consumer group 列表。 */
        const val KEY_GROUPS = "groups"

        /** 缓存 key：组级 lag（Map<groupId, KafkaGroupLagSnapshot>）。 */
        const val KEY_GROUP_LAGS = "groupLags"

        /**
         * 慢扫数据的 TTL 倍率：慢扫间隔 × 1.5（与 ibm-mq 的「TTL = 90min / 间隔 60min」
         * 同一口径）——失联后数据别永远烂在缓存里的兜底。
         */
        const val OBJECTS_TTL_FACTOR = 1.5f

        /** 快扫数据的 TTL 倍率：心跳间隔 × 2。 */
        const val LAG_TTL_FACTOR = 2f
    }

    /** 同一模块内多次扫描串行执行，避免重叠扫描互相覆盖结果（与 MQ 的 scanLock 同职责）。 */
    private val scanLock = Mutex()

    // ---------------------------------------------------------------- 对象慢扫

    /**
     * 定时任务入口（`kafka.<connectionId>.scan.objects`）：一轮扫齐 brokers / topics /
     * groups 三类。单类失败跳过；describeCluster 失败（连接大概率已断）整轮失败。
     *
     * topic 的 configs 不取（describeConfigs 每主题一调用太重），第一版只要布局快照。
     */
    suspend fun scanObjects(connectionId: String) = scanLock.withLock {
        val ctx = connectedContext(connectionId) ?: return@withLock
        val admin = ctx.adminClient ?: return@withLock
        try {
            val timeoutMs = ctx.config.connectionTimeout * 1_000L
            val objectsTtl = objectsTtlMs(ctx)
            // 三类并发：各自独立 try 兜底，单类失败不影响其他类（coroutineScope 保证
            // 取消语义 —— CancellationException 在 safely 里原样上抛，带动整组退出）。
            val result = withContext(Dispatchers.IO) {
                coroutineScope {
                    val brokers = async { safely("brokers", emptyList()) { scanBrokers(admin, timeoutMs) } }
                    val topics = async { safely("topics", emptyList()) { scanTopics(admin, timeoutMs) } }
                    val groups = async { safely("groups", emptyList()) { scanGroups(admin, timeoutMs) } }
                    ScanResult(brokers.await(), topics.await(), groups.await())
                }
            }

            val cache = ctx.cache
            cache?.put(KEY_BROKERS, result.brokers, ttlMs = objectsTtl)
            cache?.put(KEY_TOPICS, result.topics, ttlMs = objectsTtl)
            cache?.put(KEY_GROUPS, result.groups, ttlMs = objectsTtl)

            context.emit(
                name = KafkaEvents.OBJECTS_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "brokerCount" to result.brokers.size,
                    "topicCount" to result.topics.size,
                    "groupCount" to result.groups.size,
                ),
            )
            kafkaLog.info(
                "objects scanned: connection=$connectionId brokers=${result.brokers.size} " +
                    "topics=${result.topics.size} groups=${result.groups.size}",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val message = errorMessage(error)
            kafkaLog.error("objects scan failed: connection=$connectionId error=$message")
            context.emit(
                name = KafkaEvents.OBJECTS_SCAN_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to message),
            )
            markFailed(connectionId, message)
        }
    }

    // ---------------------------------------------------------------- Lag 心跳

    /**
     * 定时任务入口（`kafka.<connectionId>.scan.lag`）：全部组的 current / latest
     * offset → 分区与组级 lag；顺带做 topic 名单 / 分区数变化检测。
     *
     * 单组失败（无权 / 组消失 / 超时）只跳过该组（受限账号的预期现象）；list
     * consumer groups 与 listOffsets 这两步失败才是整轮失败。
     */
    suspend fun scanGroupLags(connectionId: String) = scanLock.withLock {
        val ctx = connectedContext(connectionId) ?: return@withLock
        val admin = ctx.adminClient ?: return@withLock
        try {
            val timeoutMs = ctx.config.connectionTimeout * 1_000L
            val lags = withContext(Dispatchers.IO) { scanAllGroupLags(admin, timeoutMs) }
            val topicsTouched = withContext(Dispatchers.IO) {
                syncTopicChanges(admin, timeoutMs, ctx.cache, objectsTtlMs(ctx))
            }

            val cache = ctx.cache
            cache?.put(KEY_GROUP_LAGS, lags, ttlMs = lagTtlMs(ctx))

            context.emit(
                name = KafkaEvents.LAG_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "groupCount" to lags.size,
                    "totalLag" to lags.values.sumOf { it.totalLag },
                    "topicChanges" to topicsTouched,
                ),
            )
            // 心跳跑通了即这条连接是活的，按「连接状态上报」约定报给核心（唯一入口，
            // 见 ConnectionStore.reportStatus）。状态没变时核心直接忽略。
            ConnectionStore.reportStatus(connectionId, ConnectionStatus.Connected, context.moduleId)
            kafkaLog.debug(
                "lag scanned: connection=$connectionId groups=${lags.size} " +
                    "totalLag=${lags.values.sumOf { it.totalLag }} topicChanges=$topicsTouched",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val message = errorMessage(error)
            kafkaLog.warn("lag scan failed: connection=$connectionId error=$message")
            context.emit(
                name = KafkaEvents.LAG_SCAN_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to message),
            )
            markFailed(connectionId, message)
        }
    }

    // ---------------------------------------------------------------- 只读口

    /** 当前缓存的 broker 列表（慢扫产物）；未连接 / 未扫过返回空列表。 */
    fun cachedBrokers(connectionId: String): List<KafkaBrokerSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_BROKERS) ?: emptyList()

    /** 当前缓存的 topic 列表（含分区明细）；未连接 / 未扫过返回空列表。 */
    fun cachedTopics(connectionId: String): List<KafkaTopicSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_TOPICS) ?: emptyList()

    /** 当前缓存的 consumer group 列表（慢扫产物）。 */
    fun cachedGroups(connectionId: String): List<KafkaGroupSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_GROUPS) ?: emptyList()

    /** 当前缓存的组级 lag（快扫产物）；key = groupId。 */
    fun cachedGroupLags(connectionId: String): Map<String, KafkaGroupLagSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_GROUP_LAGS) ?: emptyMap()

    // ---------------------------------------------------------------- 内部：查询

    private suspend fun scanBrokers(admin: AdminClient, timeoutMs: Long): List<KafkaBrokerSnapshot> {
        val cluster = admin.describeCluster(
            org.apache.kafka.clients.admin.DescribeClusterOptions().timeoutMs(timeoutMs.toInt()),
        )
        val controllerId = cluster.controller().get(timeoutMs, TimeUnit.MILLISECONDS)?.id()
        return cluster.nodes().get(timeoutMs, TimeUnit.MILLISECONDS)
            .map {
                KafkaBrokerSnapshot(
                    brokerId = it.id(),
                    host = it.host(),
                    port = it.port(),
                    rack = it.rack()?.takeIf(String::isNotBlank),
                    isController = it.id() == controllerId,
                )
            }
            .sortedBy { it.brokerId }
    }

    private suspend fun scanTopics(admin: AdminClient, timeoutMs: Long): List<KafkaTopicSnapshot> {
        val names = admin.listTopics(ListTopicsOptions().listInternal(true).timeoutMs(timeoutMs.toInt()))
            .listings()
            .get(timeoutMs, TimeUnit.MILLISECONDS)
            .map { it.name() }
            .sorted()
        if (names.isEmpty()) return emptyList()
        // 逐 topic future：单 topic 描述失败（无权 / 已删除）只跳过，不炸整类。
        val described = admin.describeTopics(TopicCollection.ofTopicNames(names))
            .topicNameValues()
        return described.mapNotNull { (name, future) ->
            try {
                val d = future.get(timeoutMs, TimeUnit.MILLISECONDS)
                KafkaTopicSnapshot(
                    name = name,
                    internal = d.isInternal,
                    partitions = d.partitions()
                        .map { p ->
                            KafkaPartitionSnapshot(
                                id = p.partition(),
                                leaderId = p.leader()?.takeIf { it.id() >= 0 }?.id(),
                                replicaIds = p.replicas().map { it.id() },
                                isrIds = p.isr().map { it.id() },
                            )
                        }
                        .sortedBy { it.id },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                kafkaLog.warn("topic describe skipped: topic=$name error=${errorMessage(error)}")
                null
            }
        }.sortedBy { it.name }
    }

    private suspend fun scanGroups(admin: AdminClient, timeoutMs: Long): List<KafkaGroupSnapshot> {
        val groupIds = admin.listConsumerGroups(ListConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()))
            .all()
            .get(timeoutMs, TimeUnit.MILLISECONDS)
            .map { it.groupId() }
            .sorted()
        if (groupIds.isEmpty()) return emptyList()
        // 逐组 future（describedGroups）：无权组只跳过，不炸整类 —— 与 kafka-ui 列表页同款。
        val described = admin.describeConsumerGroups(
            groupIds,
            org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()),
        ).describedGroups()
        return described.mapNotNull { (groupId, future) ->
            try {
                val d = future.get(timeoutMs, TimeUnit.MILLISECONDS)
                KafkaGroupSnapshot(
                    groupId = groupId,
                    state = d.state().toString(),
                    members = d.members().size,
                    simple = d.isSimpleConsumerGroup,
                    subscribedTopics = d.members()
                        .flatMap { it.assignment().topicPartitions() }
                        .map { it.topic() }
                        .toSortedSet(),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                kafkaLog.warn("group describe skipped: group=$groupId error=${errorMessage(error)}")
                null
            }
        }.sortedBy { it.groupId }
    }

    /**
     * 全部组的 lag：每组一次 listConsumerGroupOffsets（单组失败跳过），收集本轮成功
     * 组的全部分区后**一次** listOffsets(LATEST) 拿末尾位点（避免逐分区 RPC）。
     */
    private suspend fun scanAllGroupLags(
        admin: AdminClient,
        timeoutMs: Long,
    ): Map<String, KafkaGroupLagSnapshot> {
        val groupIds = admin.listConsumerGroups(ListConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()))
            .all()
            .get(timeoutMs, TimeUnit.MILLISECONDS)
            .map { it.groupId() }
        val committed = mutableMapOf<String, Map<TopicPartition, Long>>()
        groupIds.forEach { groupId ->
            try {
                val offsets = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
                committed[groupId] = offsets.mapValues { (_, m) -> m.offset() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                kafkaLog.warn("group offsets skipped: group=$groupId error=${errorMessage(error)}")
            }
        }
        if (committed.isEmpty()) return emptyMap()

        val allPartitions = committed.values.flatMap { it.keys }.toSet()
        val latest = if (allPartitions.isEmpty()) {
            emptyMap()
        } else {
            admin.listOffsets(allPartitions.associateWith { OffsetSpec.latest() })
                .all()
                .get(timeoutMs, TimeUnit.MILLISECONDS)
                .mapValues { (_, info) -> info.offset() }
        }

        return committed.mapValues { (groupId, offsets) ->
            val details = offsets.map { (tp, current) ->
                val end = latest[tp] ?: 0L
                KafkaPartitionLag(
                    topic = tp.topic(),
                    partition = tp.partition(),
                    currentOffset = current,
                    latestOffset = end,
                    lag = (end - current).coerceAtLeast(0L),
                )
            }.sortedWith(compareBy({ it.topic }, { it.partition }))
            KafkaGroupLagSnapshot(groupId = groupId, totalLag = details.sumOf { it.lag }, partitions = details)
        }
    }

    /**
     * topic 名单 / 分区数变化检测（心跳的 add-or-update，与 MQ 的 syncQueueDepths
     * 同一思想）：缓存里没有的 topic 以最小快照追加；分区数变化的更新明细。
     * **刻意不做删除** —— 快扫 describeTopics 对无权 / 已删除 topic 会跳过，全量
     * 替换会把慢扫扫到的对象误删；已删除对象的清理由慢扫全量替换负责。
     *
     * @return 有变更的 topic 数（日志 / 事件观测用）。
     */
    private suspend fun syncTopicChanges(
        admin: AdminClient,
        timeoutMs: Long,
        cache: CacheManager?,
        objectsTtl: Long,
    ): Int {
        if (cache == null) return 0
        val existing = cache.getAs<List<KafkaTopicSnapshot>>(KEY_TOPICS) ?: return 0
        val names = admin.listTopics(ListTopicsOptions().listInternal(true).timeoutMs(timeoutMs.toInt()))
            .names()
            .get(timeoutMs, TimeUnit.MILLISECONDS)
            .sorted()
        // 只描述「名单里出现、缓存里缺失或分区数可能变化」的部分：新增必查；
        // 已有的不重查（分区数变化等下轮慢扫全量替换修正 —— 名单对比只抓增删）。
        val missing = names.filter { name -> existing.none { it.name == name } }
        if (missing.isEmpty()) return 0
        val appended = admin.describeTopics(TopicCollection.ofTopicNames(missing))
            .topicNameValues()
            .mapNotNull { (name, future) ->
                try {
                    val d = future.get(timeoutMs, TimeUnit.MILLISECONDS)
                    KafkaTopicSnapshot(
                        name = name,
                        internal = d.isInternal,
                        partitions = d.partitions()
                            .map { p ->
                                KafkaPartitionSnapshot(
                                    id = p.partition(),
                                    leaderId = p.leader()?.takeIf { it.id() >= 0 }?.id(),
                                    replicaIds = p.replicas().map { it.id() },
                                    isrIds = p.isr().map { it.id() },
                                )
                            }
                            .sortedBy { it.id },
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    kafkaLog.warn("topic change describe skipped: topic=$name error=${errorMessage(error)}")
                    null
                }
            }
        if (appended.isEmpty()) return 0
        cache.put(KEY_TOPICS, (existing + appended).sortedBy { it.name }, ttlMs = objectsTtl)
        return appended.size
    }

    // ---------------------------------------------------------------- 内部：杂项

    private data class ScanResult(
        val brokers: List<KafkaBrokerSnapshot>,
        val topics: List<KafkaTopicSnapshot>,
        val groups: List<KafkaGroupSnapshot>,
    )

    /** 单类扫描兜底：任何失败 → [empty]（取消除外），分级记日志。 */
    private suspend fun <T> safely(kind: String, empty: T, block: suspend () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            kafkaLog.warn("objects scan type skipped: kind=$kind error=${errorMessage(error)}")
            empty
        }

    /** 慢扫数据 TTL：配置的慢扫间隔 × 1.5。 */
    private fun objectsTtlMs(ctx: KafkaConnectionContext): Long =
        (ctx.config.objectScanIntervalMinutes * 60_000L * OBJECTS_TTL_FACTOR).toLong()

    /** 快扫数据 TTL：配置的心跳间隔 × 2。 */
    private fun lagTtlMs(ctx: KafkaConnectionContext): Long =
        ctx.config.lagScanIntervalSeconds * 1_000L * LAG_TTL_FACTOR.toLong()

    /** 连接处于 CONNECTED 时的上下文；否则 null —— 显式连接模型的扫描守卫。 */
    private fun connectedContext(connectionId: String): KafkaConnectionContext? =
        KafkaModule.connectionOrNull(connectionId)
            ?.takeIf { it.status == ConnectionStatus.Connected }

    private fun cacheOf(connectionId: String): CacheManager? =
        KafkaModule.connectionOrNull(connectionId)?.cache

    /**
     * 整轮扫描失败即这条连接大概率已断：上下文与核心状态一起转 FAILED（UI 显示
     * 「连接失败」），定时任务下个轮次由状态守卫跳过，等用户重连。守卫 Connected：
     * 与 connect() 并发时不能把刚建好的连接踩成 Failed —— 与 MQ 的处理一致。
     */
    private fun markFailed(connectionId: String, message: String) {
        KafkaModule.connectionOrNull(connectionId)?.let { ctx ->
            if (ctx.status == ConnectionStatus.Connected) {
                ctx.lastError = message
                ctx.status = ConnectionStatus.Failed
                ConnectionStore.reportStatus(connectionId, ConnectionStatus.Failed, context.moduleId)
                kafkaLog.warn("connection marked FAILED after scan error: connection=$connectionId")
            }
        }
    }

    /** 给日志 / 事件的失败原因：解掉 ExecutionException 包装，拿根因的消息。 */
    private fun errorMessage(error: Throwable): String =
        (error as? java.util.concurrent.ExecutionException)?.cause?.message
            ?: error.message
            ?: error::class.simpleName
            ?: "unknown"
}
