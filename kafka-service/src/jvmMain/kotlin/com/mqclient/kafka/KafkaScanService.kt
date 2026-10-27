package com.mqclient.kafka

import com.mqclient.core.db.CommandAudit
import com.mqclient.core.cache.CacheManager
import com.mqclient.core.cache.putIfScanned
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.ListTopicsOptions
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicCollection
import org.apache.kafka.common.TopicPartition
import com.mqclient.mq.base.AbstractScanService

/**
 * Kafka 扫描服务：定时快扫 / 慢扫 + 缓存写入 + 事件通知，与 ibm-mq 的
 * [IbmMQService] 扫描链路同一套模式（write-through 缓存、事件驱动 UI、失败分级）。
 *
 *  - **对象慢扫** [scanObjects]（默认 10min）：brokers / topics（分区明细）/
 *    groups（状态 + assignment 订阅的 topic）三类**并发**查询；**单类失败只跳过记
 *    日志**（受限账号 ACL 是预期内的），并**保留该类的上一轮缓存**；三类全失败才
 *    整轮失败 —— 连接转 FAILED。结果全量替换写缓存（顺带清掉集群里已删除的对象）。
 *  - **Lag 心跳** [scanGroupLags]（默认 30s）：每组 current offset（已提交）+
 *    latest offset（listOffsets）→ 分区 / 组级 lag；**单组失败只跳过**；顺带做
 *    topic 名单 / 分区数变化检测（add-or-update 进 `topics` 缓存，全属性等慢扫）。
 *    整轮失败 → 连接转 FAILED；跑通 → reportStatus(Connected) 保活（与 MQ 心跳同款）。
 *
 * 缓存：该连接专属的 [CacheManager]（挂 [KafkaConnectionContext.cache]，连接成功
 * 创建、断开清空）。key：`brokers` / `cluster` / `topics` / `groups` / `groupLags`。
 * 读口 [cachedBrokers] 等只查缓存、不碰 AdminClient、不挂起。
 *
 * 事件：完成 / 失败都发（KafkaEvents.OBJECTS_SCANNED 等），订阅方（UI）读缓存刷新
 * —— 缓存优先、事件驱动的统一消费模式，与 ibm-mq 完全一致。全程不碰 UI 线程
 * （AdminClient 调用在 Dispatchers.IO）。
 */
class KafkaScanService(
    context: ServiceContext,
) : AbstractScanService<KafkaConnectionContext, KafkaScanKind>(context, MODULE_ID) {

    private companion object {
        /** 缓存 key：broker 列表。 */
        const val KEY_BROKERS = "brokers"

        /** 缓存 key：集群元信息（Cluster ID + controller，与 brokers 同源于 describeCluster）。 */
        const val KEY_CLUSTER = "cluster"

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

    /* ------------------------------ 基类钩子 ------------------------------ */

    override fun contextOrNull(connectionId: String): KafkaConnectionContext? =
        KafkaModule.connectionOrNull(connectionId)

    override val allKinds: Collection<KafkaScanKind> = KafkaScanKind.entries

    override fun describeKind(kind: KafkaScanKind): String = kind.label

    override fun objectsScanIntervalMs(ctx: KafkaConnectionContext): Long =
        ctx.config.objectScanIntervalMinutes * 60_000L

    override fun fastScanIntervalMs(ctx: KafkaConnectionContext): Long =
        ctx.config.lagScanIntervalSeconds * 1_000L

    override fun reportScanFailure(connectionId: String, message: String): Boolean =
        KafkaModule.reportScanFailure(connectionId, message)

    override fun reportScanFailures(connectionId: String, kinds: Set<KafkaScanKind>) {
        KafkaModule.reportScanFailures(connectionId, kinds)
    }

    override fun clearScanFailures(connectionId: String) {
        KafkaModule.clearScanFailures(connectionId)
    }

    override val objectsScanFailedEvent: String = KafkaEvents.OBJECTS_SCAN_FAILED

    /** 解掉 `ExecutionException` 包装：KafkaFuture 的根因藏在 cause 里。 */
    override fun errorMessage(error: Throwable): String =
        (error as? java.util.concurrent.ExecutionException)?.cause?.message
            ?: super.errorMessage(error)

    // ---------------------------------------------------------------- 对象慢扫

    /**
     * 定时任务入口（`kafka.<connectionId>.scan.objects`）：一轮扫齐 brokers / topics /
     * groups 三类。
     *
     * 失败语义：单类失败跳过该类并**保留它的上一轮缓存**（空列表写进去等于告诉 UI
     * 「这个集群没有 topic」，列表会被清空）；三类**全**失败才判定连接已断。
     *
     * topic 的 configs 不取（describeConfigs 每主题一调用太重），第一版只要布局快照。
     */
    override suspend fun scanObjects(connectionId: String) = withScanLock(connectionId) {
        val (ctx, admin) = scanTargetOrNull(connectionId) ?: return@withScanLock
        val timeoutMs = ctx.config.connectionTimeout * 1_000L
        val objectsTtl = objectsTtlMs(ctx)
        // 三路 async 并发写，用线程安全容器（每类只写自己的 key）。
        val failures = ConcurrentHashMap<KafkaScanKind, String>()
        try {
            // 三类并发：各自独立 try 兜底，单类失败不影响其他类（coroutineScope 保证
            // 取消语义 —— CancellationException 在 safely 里原样上抛，带动整组退出）。
            val result = withContext(Dispatchers.IO) {
                coroutineScope {
                    val brokers = async {
                        val empty = ClusterScan(emptyList(), KafkaClusterSnapshot(null, null))
                        scanKindOr(failures, KafkaScanKind.BROKERS, empty) { scanBrokers(admin, timeoutMs) }
                    }
                    val topics = async {
                        scanKindOr(failures, KafkaScanKind.TOPICS, emptyList()) { scanTopics(admin, timeoutMs) }
                    }
                    val groups = async {
                        scanKindOr(failures, KafkaScanKind.GROUPS, emptyList()) { scanGroups(admin, timeoutMs) }
                    }
                    ScanResult(brokers.await(), topics.await(), groups.await())
                }
            }

            val cache = ctx.cache
            // 只为**本轮真的扫到了**的类型写缓存（实现见 app-core 的 putIfScanned）。
            cache.putIfScanned(KEY_BROKERS, KafkaScanKind.BROKERS, failures, result.brokerScan.brokers, objectsTtl)
            cache.putIfScanned(KEY_TOPICS, KafkaScanKind.TOPICS, failures, result.topics, objectsTtl)
            cache.putIfScanned(KEY_GROUPS, KafkaScanKind.GROUPS, failures, result.groups, objectsTtl)
            // 集群元信息跟着 broker 一起落：只在扫到货时写，不让兜底的空快照
            // 覆盖上一轮的 Cluster ID。
            if (!failures.containsKey(KafkaScanKind.BROKERS) && result.brokerScan.brokers.isNotEmpty()) {
                cache?.put(KEY_CLUSTER, result.brokerScan.cluster, ttlMs = objectsTtl)
            }

            // 三类全失败：AdminClient 一条都读不通，连接大概率真的断了。
            // 三类全失败：一条都读不通，连接大概率真的断了 —— 收尾逻辑在基类
            // （清失败标记 → 发失败事件 → 交连接管理器按连续失败阈值判定）。
            if (isConnectionBroken(failures.keys)) {
                reportAllKindsFailed(connectionId, failures, "Admin API 全部请求均失败")
                return@withScanLock
            }

            // 把「哪几类没取到」交给模块存起来：UI 的提示条要能在页面切来切去后
            // 仍然知道「这份数据是残的」，而事件是瞬时的。
            KafkaModule.reportScanFailures(connectionId, failures.keys.toSet())

            context.emit(
                name = KafkaEvents.OBJECTS_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    // 部分结果：UI / 日志据此区分「扫全了」和「缺了几类」。
                    "partial" to failures.isNotEmpty(),
                    "failedKinds" to failures.keys.map { it.label },
                    "brokerCount" to result.brokerScan.brokers.size,
                    "topicCount" to result.topics.size,
                    "groupCount" to result.groups.size,
                ),
            )
            if (failures.isEmpty()) {
                kafkaLog.info(
                    "objects scanned: connection=$connectionId brokers=${result.brokerScan.brokers.size} " +
                        "topics=${result.topics.size} groups=${result.groups.size}",
                )
            } else {
                kafkaLog.warn(
                    "objects scanned (partial): connection=$connectionId " +
                        "brokers=${result.brokerScan.brokers.size} topics=${result.topics.size} " +
                        "groups=${result.groups.size} failed=${failures.keys.map { it.label }}",
                )
            }
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
    suspend fun scanGroupLags(connectionId: String) = withScanLock(connectionId) {
        val (ctx, admin) = scanTargetOrNull(connectionId) ?: return@withScanLock
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
            // 心跳跑通了即这条连接是活的：计数归零，若之前被误判 Failed 则自动恢复。
            KafkaModule.reportScanSuccess(connectionId)
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

    /** 当前缓存的集群元信息（Cluster ID / controller）；未扫过返回 null。 */
    fun cachedCluster(connectionId: String): KafkaClusterSnapshot? =
        cacheOf(connectionId)?.getAs(KEY_CLUSTER)

    /** 当前缓存的 topic 列表（含分区明细）；未连接 / 未扫过返回空列表。 */
    fun cachedTopics(connectionId: String): List<KafkaTopicSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_TOPICS) ?: emptyList()

    /** 当前缓存的 consumer group 列表（慢扫产物）。 */
    fun cachedGroups(connectionId: String): List<KafkaGroupSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_GROUPS) ?: emptyList()

    /** 当前缓存的组级 lag（快扫产物）；key = groupId。 */
    fun cachedGroupLags(connectionId: String): Map<String, KafkaGroupLagSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_GROUP_LAGS) ?: emptyMap()

    // ---------------------------------------------------------------- 写操作

    /**
     * 创建主题（AdminClient.createTopics）：v1 只带名称 / 分区数 / 副本因子三要素，
     * 不带 config。TopicExistsException 等服务端错误原样上抛，由调用方（创建对话框）
     * 显示在表单内。
     */
    suspend fun createTopic(
        connectionId: String,
        name: String,
        partitions: Int,
        replicationFactor: Int,
    ) {
        val (ctx, admin) = targetOrThrow(connectionId)
        val timeoutMs = ctx.config.connectionTimeout * 1_000L
        audited(connectionId, "CREATE TOPIC $name (partitions=$partitions, replicationFactor=$replicationFactor)") {
            withContext(Dispatchers.IO) {
                admin.createTopics(listOf(NewTopic(name, partitions, replicationFactor.toShort())))
                    .all()
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
            }
        }
        kafkaLog.info("topic created: connection=$connectionId topic=$name partitions=$partitions replicas=$replicationFactor")

        // 即时可见：createTopics 成功后**单独描述新主题**（一次 RPC），把真实分区明细
        // 追加进 topics 缓存并广播 OBJECTS_SCANNED —— 列表立即出现新主题，不用等整轮
        // 慢扫（秒级）也不依赖用户再点刷新；随后的定时慢扫全量替换兜底。
        // 即时刷新失败不影响创建结果，warn 记日志即可。
        val cache = ctx.cache
        if (cache != null) {
            try {
                val described = withContext(Dispatchers.IO) {
                    admin.describeTopics(TopicCollection.ofTopicNames(listOf(name)))
                        .topicNameValues()[name]
                        ?.get(timeoutMs, TimeUnit.MILLISECONDS)
                }
                if (described != null) {
                    val snapshot = KafkaTopicSnapshot(
                        name = name,
                        internal = described.isInternal,
                        partitions = described.partitions()
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
                    // 原子读-改-写：慢扫在这一步前后可能整表替换过，先读后写会把刚扫到的
                    // 其它 topic 一起抹掉。已存在则原样返回 = 不写（与原 `if none` 等价）。
                    cache.updateAs<List<KafkaTopicSnapshot>>(KEY_TOPICS, objectsTtlMs(ctx)) { current ->
                        val existing = current ?: emptyList()
                        if (existing.any { it.name == name }) {
                            existing
                        } else {
                            (existing + snapshot).sortedBy { it.name }
                        }
                    }
                    context.emit(
                        name = KafkaEvents.OBJECTS_SCANNED,
                        payload = mapOf("connectionId" to connectionId, "createdTopic" to name),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                kafkaLog.warn("created topic cache sync failed: topic=$name error=${errorMessage(error)}")
            }
        }
    }

    // ---------------------------------------------------------------- Topic 属性 / 删除（实时）

    /** Topic 配置项（describeConfigs 投影，按 key 排序）。 */
    suspend fun getTopicConfigs(connectionId: String, name: String): List<KafkaTopicConfigSnapshot> {
        val (ctx, admin) = targetOrThrow(connectionId)
        val timeoutMs = ctx.config.connectionTimeout * 1_000L
        return withContext(Dispatchers.IO) {
            val config = admin.describeConfigs(listOf(ConfigResource(ConfigResource.Type.TOPIC, name)))
                .all()
                .get(timeoutMs, TimeUnit.MILLISECONDS)[ConfigResource(ConfigResource.Type.TOPIC, name)]
            config?.entries()
                ?.map { KafkaTopicConfigSnapshot(key = it.name(), value = it.value(), isDefault = it.isDefault()) }
                ?.sortedBy { it.key }
                ?: emptyList()
        }
    }

    /** Topic 各分区的位移区间（earliest / latest，两次 listOffsets 批量 RPC）。 */
    suspend fun getTopicOffsets(
        connectionId: String,
        name: String,
        partitions: List<Int>,
    ): Map<Int, KafkaTopicOffsetRange> {
        val (ctx, admin) = targetOrThrow(connectionId)
        val timeoutMs = ctx.config.connectionTimeout * 1_000L
        return withContext(Dispatchers.IO) {
            val tps = partitions.map { TopicPartition(name, it) }
            if (tps.isEmpty()) return@withContext emptyMap()
            val earliest = admin.listOffsets(tps.associateWith { OffsetSpec.earliest() })
                .all()
                .get(timeoutMs, TimeUnit.MILLISECONDS)
                .mapValues { (_, info) -> info.offset() }
            val latest = admin.listOffsets(tps.associateWith { OffsetSpec.latest() })
                .all()
                .get(timeoutMs, TimeUnit.MILLISECONDS)
                .mapValues { (_, info) -> info.offset() }
            tps.associateWith { tp -> KafkaTopicOffsetRange(earliest[tp] ?: 0L, latest[tp] ?: 0L) }
                .mapKeys { it.key.partition() }
        }
    }

    /** 删除主题（admin.deleteTopics）：权限不足 / 不存在等错误原样上抛由 UI 提示。 */
    suspend fun deleteTopic(connectionId: String, name: String) {
        val (ctx, admin) = targetOrThrow(connectionId)
        val timeoutMs = ctx.config.connectionTimeout * 1_000L
        audited(connectionId, "DELETE TOPIC $name") {
            withContext(Dispatchers.IO) {
                admin.deleteTopics(setOf(name)).all().get(timeoutMs, TimeUnit.MILLISECONDS)
            }
        }
        kafkaLog.info("topic deleted: connection=$connectionId topic=$name")
    }

    /**
     * 清空 topic 现有消息（admin.deleteRecords）：各分区的 log start offset 推到当前
     * 末端 —— 已有记录全部不可见（Kafka 无逐条删除，这是官方的「清空」语义），之后
     * 写入的新消息不受影响；空分区（earliest == latest）跳过。
     *
     * 消费组已提交位点多半越过新的 log start —— 消费端按各自 auto.offset.reset 处理
     * （earliest 回到新起点＝读到空、latest 接新数据），UI 侧确认框负责警示。
     *
     * @return 清掉的分区数（空分区不计）。
     */
    suspend fun clearTopicRecords(connectionId: String, name: String): Int {
        val (ctx, admin) = targetOrThrow(connectionId)
        val timeoutMs = ctx.config.connectionTimeout * 1_000L
        val cleared = audited(connectionId, "PURGE TOPIC $name", resultNote = { "(partitions=$it)" }) {
            withContext(Dispatchers.IO) {
            val partitions = admin.describeTopics(listOf(name))
                .allTopicNames()
                .get(timeoutMs, TimeUnit.MILLISECONDS)[name]
                ?.partitions()
                ?: throw IllegalStateException("topic '$name' 不存在或无权访问")
            val tps = partitions.sortedBy { it.partition() }.map { TopicPartition(name, it.partition()) }
            if (tps.isEmpty()) return@withContext 0
            val beginning = admin.listOffsets(tps.associateWith { OffsetSpec.earliest() })
                .all()
                .get(timeoutMs, TimeUnit.MILLISECONDS)
                .mapValues { (_, info) -> info.offset() }
            // 目标 = 各分区当前末端；earliest == latest 的空分区没有可删的记录，跳过。
            val targets = admin.listOffsets(tps.associateWith { OffsetSpec.latest() })
                .all()
                .get(timeoutMs, TimeUnit.MILLISECONDS)
                .mapValues { (_, info) -> info.offset() }
                .filter { (tp, end) -> end > (beginning[tp] ?: 0L) }
                .mapValues { (_, end) -> org.apache.kafka.clients.admin.RecordsToDelete.beforeOffset(end) }
            if (targets.isEmpty()) return@withContext 0
            admin.deleteRecords(targets).all()
                .get(timeoutMs, TimeUnit.MILLISECONDS)
            targets.size
            }
        }
        kafkaLog.info("topic records cleared: connection=$connectionId topic=$name partitions=$cleared")
        return cleared
    }

    // ---------------------------------------------------------------- 组详情（实时）

    /**
     * 消费组详情（实时查询，不走扫描缓存 —— 详情要新鲜数据）：describeConsumerGroups
     * 取成员明细与 assignment（分区 → 成员归属映射），listConsumerGroupOffsets +
     * listOffsets(LATEST) 取位点行。组不存在时抛原异常（UI 显示原因）。
     */
    suspend fun getGroupDetail(connectionId: String, groupId: String): KafkaGroupDetailSnapshot {
        val (ctx, admin) = targetOrThrow(connectionId)
        val timeoutMs = ctx.config.connectionTimeout * 1_000L
        return withContext(Dispatchers.IO) {
            val described = admin.describeConsumerGroups(
                listOf(groupId),
                org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions()
                    .timeoutMs(timeoutMs.toInt()),
            ).describedGroups()[groupId]
                ?.get(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw IllegalStateException("消费组不存在: $groupId")

            val memberDetails = described.members().map { m ->
                val tps = m.assignment().topicPartitions()
                    .sortedWith(compareBy({ it.topic() }, { it.partition() }))
                m to tps
            }
            val members = memberDetails.map { (m, tps) ->
                KafkaGroupMemberSnapshot(
                    memberId = m.consumerId(),
                    clientId = m.clientId(),
                    host = m.host(),
                    partitions = tps.map { KafkaTopicPartitionRef(it.topic(), it.partition()) },
                )
            }
            // 分区 → 成员归属：assignment 直接映射（TopicPartition 为主键）。
            val ownerByPartition = memberDetails
                .flatMap { (m, tps) -> tps.map { it to m.consumerId() } }
                .toMap()

            val committed = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata()
                .get(timeoutMs, TimeUnit.MILLISECONDS)
                .mapValues { (_, m) -> m.offset() }
            // 关心分区 = assignment ∪ 已提交（组空时 assignment 空、只剩已提交）。
            val partitions = (memberDetails.flatMap { (_, tps) -> tps }.toSet() + committed.keys)
                .sortedWith(compareBy({ it.topic() }, { it.partition() }))
            val latest = if (partitions.isEmpty()) {
                emptyMap()
            } else {
                admin.listOffsets(partitions.associateWith { OffsetSpec.latest() })
                    .all()
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
                    .mapValues { (_, info) -> info.offset() }
            }

            val offsets = partitions.map { tp ->
                val current = committed[tp]
                val end = latest[tp] ?: 0L
                KafkaGroupOffsetDetail(
                    topic = tp.topic(),
                    partition = tp.partition(),
                    currentOffset = current,
                    latestOffset = end,
                    lag = end - (current ?: 0L),
                    ownerId = ownerByPartition[tp],
                )
            }
            KafkaGroupDetailSnapshot(
                groupId = groupId,
                state = described.state().toString(),
                members = members,
                offsets = offsets,
                totalLag = offsets.sumOf { it.lag },
                subscribedTopics = members
                    .flatMap { it.partitions }
                    .map { it.topic }
                    .toSortedSet(),
            )
        }
    }

    /**
     * 重置位移：该组**已提交**分区的位移重置到 earliest / latest / 指定时间点。
     * 没有已提交位移的组无法重置（Kafka 语义：alterConsumerGroupOffsets 要指定分区）
     * —— 抛 IllegalStateException 由 UI 提示。
     *
     * **前置状态检查**：组必须 Empty / Dead（无活跃成员）才允许重置 —— 活跃消费者
     * 会立刻重新提交位移覆盖重置结果。UI 侧按钮同样按状态门禁（双保险）。
     *
     * [KafkaGroupResetTo.AtTimestamp]：forTimestamp 语义 —— 该时刻之后**有**消息的
     * 分区重置到第一条记录的 offset；返回 -1（该时刻之后无新消息）的分区兜底重置到
     * latest（消费从末端开始，不会落在非法位上）。
     *
     * @return 重置的分区数。
     */
    suspend fun resetGroupOffsets(
        connectionId: String,
        groupId: String,
        to: KafkaGroupResetTo,
    ): Int {
        val (ctx, admin) = targetOrThrow(connectionId)
        val strategy = when (to) {
            is KafkaGroupResetTo.Earliest -> "EARLIEST"
            is KafkaGroupResetTo.Latest -> "LATEST"
            is KafkaGroupResetTo.AtTimestamp -> "TIMESTAMP"
        }
        return audited(
            connectionId,
            "RESET OFFSETS GROUP $groupId (strategy=$strategy)",
            resultNote = { "(partitions=$it)" },
        ) {
            resetGroupOffsets(admin, groupId, to, ctx.config.connectionTimeout * 1_000L)
        }
    }

    /**
     * [resetGroupOffsets] 的实现体：按传入的 [AdminClient] 直调（diag 测试不经模块
     * 连接守卫也能走到同一条代码路径，同 [KafkaMessageService] 的拆分理由）。
     */
    internal suspend fun resetGroupOffsets(
        admin: AdminClient,
        groupId: String,
        to: KafkaGroupResetTo,
        timeoutMs: Long,
    ): Int = withContext(Dispatchers.IO) {
            // 状态检查：活跃成员会重新提交位移覆盖重置结果，必须先停掉消费者。
            val described = admin.describeConsumerGroups(
                listOf(groupId),
                org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()),
            ).describedGroups()[groupId]
                ?.get(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw IllegalStateException("消费组不存在: $groupId")
            val state = described.state().toString().uppercase()
            if (state != "EMPTY" && state != "DEAD") {
                throw IllegalStateException(
                    "消费组仍有活跃成员（状态 $state），请先停掉所有消费者再重置位移",
                )
            }
            val committed = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata()
                .get(timeoutMs, TimeUnit.MILLISECONDS)
            if (committed.isEmpty()) {
                throw IllegalStateException("该组没有已提交位移，无法重置")
            }
            val targets: Map<TopicPartition, Long> = when (to) {
                is KafkaGroupResetTo.Earliest ->
                    listOffsetTargets(admin, committed.keys, OffsetSpec.earliest(), timeoutMs)
                is KafkaGroupResetTo.Latest ->
                    listOffsetTargets(admin, committed.keys, OffsetSpec.latest(), timeoutMs)
                is KafkaGroupResetTo.AtTimestamp -> {
                    val found = admin
                        .listOffsets(committed.keys.associateWith { OffsetSpec.forTimestamp(to.epochMillis) })
                        .all()
                        .get(timeoutMs, TimeUnit.MILLISECONDS)
                    // -1（UNKNOWN_OFFSET）= 该时刻之后无消息：这批分区兜底到 latest。
                    val missing = found.filterValues { it.offset() < 0 }.keys
                    val fallback = if (missing.isEmpty()) {
                        emptyMap()
                    } else {
                        listOffsetTargets(admin, missing, OffsetSpec.latest(), timeoutMs)
                    }
                    found.mapValues { (_, info) -> info.offset() } + fallback
                }
            }
            admin.alterConsumerGroupOffsets(
                groupId,
                targets.mapValues { (_, offset) -> OffsetAndMetadata(offset) },
            ).all().get(timeoutMs, TimeUnit.MILLISECONDS)
            kafkaLog.info(
                "group offsets reset: group=$groupId mode=${to::class.simpleName} partitions=${targets.size}",
            )
            targets.size
        }

    /** listOffsets 批量取各分区 offset（earliest / latest / -1 兜底共用）。 */
    private fun listOffsetTargets(
        admin: AdminClient,
        tps: Set<TopicPartition>,
        spec: OffsetSpec,
        timeoutMs: Long,
    ): Map<TopicPartition, Long> =
        admin.listOffsets(tps.associateWith { spec })
            .all()
            .get(timeoutMs, TimeUnit.MILLISECONDS)
            .mapValues { (_, info) -> info.offset() }

    /**
     * 删除消费组（admin.deleteConsumerGroups）：组内有成员 / 有活跃提交时 Kafka 侧
     * 拒绝（GroupNotEmpty 等），异常原样上抛由 UI 提示。
     */
    suspend fun deleteGroup(connectionId: String, groupId: String) {
        val (ctx, admin) = targetOrThrow(connectionId)
        val timeoutMs = ctx.config.connectionTimeout * 1_000L
        audited(connectionId, "DELETE GROUP $groupId") {
            withContext(Dispatchers.IO) {
                admin.deleteConsumerGroups(setOf(groupId))
                    .all()
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    // ---------------------------------------------------------------- 内部：查询

    /**
     * describeCluster 一次调用的双产出：broker 明细（含 controller 标记）+ 集群元信息。
     * clusterId 单独兜底 —— 部分受限账号对 Describe Cluster 的细项 ACL 缺失时抛
     * Unauthorized，broker 列表照常产出、clusterId 置 null。
     */
    private data class ClusterScan(
        val brokers: List<KafkaBrokerSnapshot>,
        val cluster: KafkaClusterSnapshot,
    )

    private suspend fun scanBrokers(admin: AdminClient, timeoutMs: Long): ClusterScan {
        val cluster = admin.describeCluster(
            org.apache.kafka.clients.admin.DescribeClusterOptions().timeoutMs(timeoutMs.toInt()),
        )
        val controllerId = cluster.controller().get(timeoutMs, TimeUnit.MILLISECONDS)?.id()
        val clusterId = try {
            cluster.clusterId().get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            kafkaLog.warn("clusterId unavailable: error=${errorMessage(error)}")
            null
        }
        val brokers = cluster.nodes().get(timeoutMs, TimeUnit.MILLISECONDS)
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
        return ClusterScan(brokers, KafkaClusterSnapshot(clusterId = clusterId, controllerId = controllerId))
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
     * 全部组的 lag：**一次批量** listConsumerGroupOffsets（AdminClient 内部按 group
     * coordinator 拆请求、并发下发 —— 实际是「每个 coordinator 一次 RPC」，而不是
     * 每组一次），单组失败跳过；收集本轮成功组的全部分区后一次 listOffsets(LATEST)
     * 拿末尾位点（避免逐分区 RPC）。
     *
     * 为什么不再逐组串行：旧实现每组一次 `.get(timeoutMs)`，N 个组就是 N 次 RTT
     * 首尾相接，50 组 × 100ms 就是 5 秒全堆在同一轮心跳里。批量版的 fan-out 由
     * AdminClient 自己做（受 max.in.flight 约束），既不用手写并发度，也不会把
     * broker 的连接摊爆。
     */
    private suspend fun scanAllGroupLags(
        admin: AdminClient,
        timeoutMs: Long,
    ): Map<String, KafkaGroupLagSnapshot> {
        val groupIds = admin.listConsumerGroups(ListConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()))
            .all()
            .get(timeoutMs, TimeUnit.MILLISECONDS)
            .map { it.groupId() }
        if (groupIds.isEmpty()) return emptyMap()

        // 空 spec = 「该组的全部分区」，与原来逐组不带 spec 的语义一致。
        val offsets = admin.listConsumerGroupOffsets(
            groupIds.associateWith { ListConsumerGroupOffsetsSpec() },
            ListConsumerGroupOffsetsOptions().timeoutMs(timeoutMs.toInt()),
        )
        val committed = groupIds.mapNotNull { groupId ->
            try {
                // 按组分期的 future：单组无权 / 不存在只跳过它自己，不连坐整轮。
                groupId to offsets.partitionsToOffsetAndMetadata(groupId)
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
                    .mapValues { (_, m) -> m.offset() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                kafkaLog.warn("group offsets skipped: group=$groupId error=${errorMessage(error)}")
                null
            }
        }.toMap()
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
        // 原子追加：前面的 `existing` 只用于「该查哪些」的决策（跨两次 RPC，必然可能过时），
        // 真正的写入以写入时的最新快照为基础，并按名字去重 —— 否则会把并发的慢扫 /
        // 另一个 createTopic 刚写进去的条目整段覆盖。
        cache.updateAs<List<KafkaTopicSnapshot>>(KEY_TOPICS, objectsTtl) { current ->
            val base = current ?: emptyList()
            val present = base.mapTo(mutableSetOf()) { it.name }
            (base + appended.filter { present.add(it.name) }).sortedBy { it.name }
        }
        return appended.size
    }

    // ---------------------------------------------------------------- 内部：杂项

    private data class ScanResult(
        val brokerScan: ClusterScan,
        val topics: List<KafkaTopicSnapshot>,
        val groups: List<KafkaGroupSnapshot>,
    )

    /** 单类扫描兜底：任何失败 → [empty]（取消除外），并把该类登记进 [failures]。 */
    /** 快扫 TTL 的模块内别名（基类公式见 [fastTtlMs]）。 */
    private fun lagTtlMs(ctx: KafkaConnectionContext): Long = fastTtlMs(ctx)

    /**
     * 请求入口的**统一守卫**：只看「有没有 AdminClient」，不拿 status 当否决票。
     *
     * 这是与 RabbitMQ 同口径的修正。`status` 是**扫描结论**（可能因一次抖动被误标），
     * `adminClient` 才是**能力事实**（客户端在 = 可以发请求）。用结论否决能力，就会
     * 出现「明明连着却报未连接」；反过来让请求自己去试，真断了它会抛连接异常，
     * 那时展示的才是真实原因。
     */
    private data class ScanTarget(
        val ctx: KafkaConnectionContext,
        val admin: AdminClient,
    )

    /** 有客户端就返回；真的没有则抛带具体原因的异常。 */
    private fun targetOrThrow(connectionId: String): ScanTarget {
        val ctx = KafkaModule.connectionOrNull(connectionId)
            ?: throw IllegalStateException("连接未登记：该连接已被删除或模块未初始化")
        val admin = ctx.adminClient ?: throw IllegalStateException(connectionRefusalText(ctx))
        return ScanTarget(ctx, admin)
    }

    /**
     * 定时扫描专用的宽松版：只要 AdminClient 还在就返回，`status` 不参与判定。
     *
     * 为什么定时扫描必须忽略 status：只有这样才能**自愈** —— 被判 Failed 之后心跳
     * 继续跑，第一次成功即可恢复成 Connected（见
     * [KafkaConnectionManager.onScanSucceeded]）。
     */
    private fun scanTargetOrNull(connectionId: String): ScanTarget? {
        val ctx = KafkaModule.connectionOrNull(connectionId) ?: return null
        val admin = ctx.adminClient ?: return null
        return ScanTarget(ctx, admin)
    }

    /**
     * 写操作审计包裹：操作前计时，成功 / 失败各发一条 core.mq.command.executed
     * 事件（CommandAudit 内部 runCatching，审计失败不影响本操作）。[command] 是
     * 拼装好的伪命令；[resultNote] 成功后追加结果注记（如 "(partitions=6)"）。
     * 消息生产（sendMessage）不是管理操作，不经此包裹 —— 与 IBM MQ 的 MqscExecutor
     * 咽喉点口径一致。
     */
    private suspend fun <T> audited(
        connectionId: String,
        command: String,
        resultNote: ((T) -> String?)? = null,
        block: suspend () -> T,
    ): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            CommandAudit.emit(
                connectionId = connectionId,
                command = command + (resultNote?.invoke(result)?.let { note -> " $note" } ?: ""),
                status = "SUCCESS",
                response = null,
                requestTime = start,
                responseTime = System.currentTimeMillis(),
            )
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CommandAudit.emit(
                connectionId = connectionId,
                command = command,
                status = "FAILED",
                response = CommandAudit.describeError(e),
                requestTime = start,
                responseTime = System.currentTimeMillis(),
            )
            throw e
        }
    }

}

/**
 * 消费组重置位移的档位（[KafkaScanService.resetGroupOffsets] 入参，UI 三选的
 * service 投影；对标消息域 [KafkaReadStart] 的 sealed interface 模式）：
 *  - [Earliest] / [Latest]：各分区重置到最早 / 最新；
 *  - [AtTimestamp]：重置到该时刻之后第一条记录；查不到（该时刻后无消息）的分区
 *    兜底到 latest。
 */
sealed interface KafkaGroupResetTo {
    data object Earliest : KafkaGroupResetTo
    data object Latest : KafkaGroupResetTo
    data class AtTimestamp(val epochMillis: Long) : KafkaGroupResetTo
}
/**
 * 对象慢扫的三类查询。
 *
 * 失败必须**按类记**：不能因为 topics 失败了就拿空表覆盖 `topics` 缓存 —— 那等于
 * 告诉 UI「这个集群没有 topic」。
 *
 * 顶层公开（不再是 `KafkaScanService` 的 internal 嵌套）：UI 的「部分数据未刷新」
 * 提示条要按类型拼文案。枚举不含实现，暴露它比在 UI 里用字符串标签匹配安全。
 */
enum class KafkaScanKind(val label: String) {
    BROKERS("brokers"),
    TOPICS("topics"),
    GROUPS("groups"),
}