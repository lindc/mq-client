package com.mqclient.rocketmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.cache.CacheManager
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * RocketMQ 扫描服务：定时快扫 / 慢扫 + 缓存写入 + 事件通知，与 kafka 的
 * KafkaScanService 同一套模式（write-through 缓存、事件驱动 UI、失败分级）。
 *
 *  - **对象慢扫** [scanObjects]（默认 5min）：clusterInfo / topics（逐 topic 路由，
 *    受限并发 8）/ groups（含在线成员数，best-effort）三类并发查询；单类失败跳过
 *    （受限账号 ACL 是预期内现象），clusterInfo 失败（连接大概率已断）才整轮失败。
 *    结果全量替换写缓存。
 *  - **心跳快扫** [scanGroupLags]（默认 15s）：全部组的 examineConsumeStats → 组级
 *    Lag；**单组失败只跳过**；整轮失败 → 连接转 FAILED；跑通 → reportStatus(Connected)
 *    保活（与 kafka / MQ 心跳同款）。
 *
 * 缓存：该连接专属的 [CacheManager]（挂 [RocketMqConnectionContext.cache]，连接成功
 * 创建、断开清空）。key：`cluster` / `topics` / `groups` / `groupLags`。读口
 * [cachedTopics] 等只查缓存、不碰 AdminExt、不挂起。
 *
 * 事件：完成 / 失败都发（RocketMqEvents.OBJECTS_SCANNED 等），订阅方（UI）读缓存
 * 刷新 —— 缓存优先、事件驱动的统一消费模式。全程不碰 UI 线程（AdminExt 调用在
 * Dispatchers.IO）。
 */
class RocketMqScanService(private val context: ServiceContext) {

    private companion object {
        /** 缓存 key：集群元信息（nameServers + brokers）。 */
        const val KEY_CLUSTER = "cluster"

        /** 缓存 key：topic 列表（含路由摘要）。 */
        const val KEY_TOPICS = "topics"

        /** 缓存 key：consumer group 列表（含在线成员数）。 */
        const val KEY_GROUPS = "groups"

        /** 缓存 key：组级 Lag（Map<group, RocketMqGroupLagSnapshot>）。 */
        const val KEY_GROUP_LAGS = "groupLags"

        /** 慢扫数据的 TTL 倍率：慢扫间隔 × 1.5（与 kafka 同一口径）。 */
        const val OBJECTS_TTL_FACTOR = 1.5f

        /** 快扫数据的 TTL 倍率：心跳间隔 × 2。 */
        const val LAG_TTL_FACTOR = 2f

        /** 逐 topic 路由查询的受限并发：NameServer 单点别打太狠。 */
        const val ROUTE_CONCURRENCY = 8
    }

    /** 同一模块内多次扫描串行执行，避免重叠扫描互相覆盖结果。 */
    private val scanLock = Mutex()

    /**
     * 「至少完成过一轮对象慢扫」标记（connectionId → true）。UI 用它区分「还在等
     * 首扫」（保持 Loading）与「扫完了但确实没有对象」（显示空态）——**不依赖
     * 事件是否恰好被收到**：事件先于页面订阅到达时，页面读这个标记同样能出空态。
     * 慢扫整轮失败（markFailed）也算完成过——失败路径由连接状态 Failed → UI Error
     * 承担，不再挂 Loading。断开时由 ConnectionManager 调 [clearScanState] 移除。
     */
    private val objectsScannedOnce = ConcurrentHashMap<String, Boolean>()

    /** 该连接是否至少完成过一轮对象慢扫（成功或部分失败都算）。 */
    fun hasScannedObjects(connectionId: String): Boolean =
        objectsScannedOnce.containsKey(connectionId)

    /** 断开 / 清缓存时同步清除扫描标记（ConnectionManager.disconnectNow 调用）。 */
    fun clearScanState(connectionId: String) {
        objectsScannedOnce.remove(connectionId)
    }

    // ---------------------------------------------------------------- 对象慢扫

    /** 定时任务入口（`rocketmq.<connectionId>.scan.objects`）。 */
    suspend fun scanObjects(connectionId: String) = scanLock.withLock {
        val ctx = connectedContext(connectionId) ?: return@withLock
        val admin = ctx.adminClient ?: return@withLock
        try {
            val objectsTtl = objectsTtlMs(ctx)
            val result = withContext(Dispatchers.IO) {
                coroutineScope {
                    val cluster = async {
                        runCatching { admin.fetchClusterInfo() }
                            .map { clusterSnapshot(it) }
                            .getOrElse { RocketMqClusterInfoSnapshot(null, emptyList(), emptyList()) }
                    }
                    val topics = async {
                        runCatching { scanTopics(admin) }.getOrElse { emptyList() }
                    }
                    val groups = async {
                        runCatching { scanGroups(admin) }.getOrElse { emptyList() }
                    }
                    Triple(cluster.await(), topics.await(), groups.await())
                }
            }
            val (cluster, topics, groups) = result

            val cache = ctx.cache
            // clusterInfo 只在扫到货时落缓存：兜底的空快照不覆盖上一轮。
            if (cluster.brokers.isNotEmpty() || cluster.clusterName != null) {
                cache?.put(KEY_CLUSTER, cluster, ttlMs = objectsTtl)
            }
            cache?.put(KEY_TOPICS, topics, ttlMs = objectsTtl)
            cache?.put(KEY_GROUPS, groups, ttlMs = objectsTtl)
            objectsScannedOnce[connectionId] = true

            context.emit(
                name = RocketMqEvents.OBJECTS_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "brokerCount" to cluster.brokers.size,
                    "topicCount" to topics.size,
                    "groupCount" to groups.size,
                ),
            )
            rocketMqLog.info(
                "objects scanned: connection=$connectionId brokers=" + cluster.brokers.size +
                    " topics=" + topics.size + " groups=" + groups.size,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val message = errorMessage(error)
            rocketMqLog.error("objects scan failed: connection=$connectionId error=$message")
            context.emit(
                name = RocketMqEvents.OBJECTS_SCAN_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to message),
            )
            markFailed(connectionId, message)
        }
    }

    // ---------------------------------------------------------------- 心跳快扫

    /** 定时任务入口（`rocketmq.<connectionId>.scan.lag`）：全部组的 Lag 统计。 */
    suspend fun scanGroupLags(connectionId: String) = scanLock.withLock {
        val ctx = connectedContext(connectionId) ?: return@withLock
        val admin = ctx.adminClient ?: return@withLock
        try {
            val groups = withContext(Dispatchers.IO) { admin.fetchConsumerGroupList() }
            val lags = withContext(Dispatchers.IO) {
                val sem = Semaphore(ROUTE_CONCURRENCY)
                coroutineScope {
                    groups.map { group ->
                        async {
                            sem.withPermit {
                                runCatching {
                                    val stats = admin.fetchConsumerProgress(group)
                                    RocketMqGroupLagSnapshot(
                                        group = group,
                                        totalLag = stats.offsetTable.values.sumOf { wrapper ->
                                            (wrapper.brokerOffset - wrapper.consumerOffset).coerceAtLeast(0)
                                        },
                                        queueCount = stats.offsetTable.size,
                                    )
                                }.getOrNull()
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }

            ctx.cache?.put(KEY_GROUP_LAGS, lags.associateBy { it.group }, ttlMs = lagTtlMs(ctx))

            context.emit(
                name = RocketMqEvents.LAG_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "groupCount" to lags.size,
                    "totalLag" to lags.sumOf { it.totalLag },
                ),
            )
            // 心跳跑通了即这条连接是活的，按「连接状态上报」约定报给核心（唯一入口）。
            ConnectionStore.reportStatus(connectionId, ConnectionStatus.Connected, context.moduleId)
            rocketMqLog.debug(
                "lag scanned: connection=$connectionId groups=" + lags.size +
                    " totalLag=" + lags.sumOf { it.totalLag },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val message = errorMessage(error)
            rocketMqLog.warn("lag scan failed: connection=$connectionId error=$message")
            context.emit(
                name = RocketMqEvents.LAG_SCAN_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to message),
            )
            markFailed(connectionId, message)
        }
    }

    // ---------------------------------------------------------------- 只读口

    /** 当前缓存的集群元信息；未扫过返回 null。 */
    fun cachedClusterInfo(connectionId: String): RocketMqClusterInfoSnapshot? =
        cacheOf(connectionId)?.getAs(KEY_CLUSTER)

    /** 当前缓存的 topic 列表（含路由摘要）；未连接 / 未扫过返回空列表。 */
    fun cachedTopics(connectionId: String): List<RocketMqTopicSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_TOPICS) ?: emptyList()

    /** 当前缓存的 consumer group 列表（含在线成员数）。 */
    fun cachedGroups(connectionId: String): List<RocketMqGroupSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_GROUPS) ?: emptyList()

    /** 当前缓存的组级 Lag（快扫产物）；key = groupId。 */
    fun cachedGroupLags(connectionId: String): Map<String, RocketMqGroupLagSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_GROUP_LAGS) ?: emptyMap()

    // ---------------------------------------------------------------- Topic 属性 / 删除

    /**
     * Topic 消息类型现查（不进缓存 —— 属性对话框要新鲜数据）：经 Master 地址 examine
     * TopicConfig 并提取 attributes["message.type"]（5.x；4.x / 未设置返回 null，
     * UI 回落 Normal）。TopicConfig 不出本模块（rocketmq-ui 无 rocketmq 类路径）。
     */
    suspend fun fetchTopicMessageType(connectionId: String, topic: String): String? =
        withContext(Dispatchers.IO) {
            val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
            val admin = ctx.adminClient ?: throw IllegalStateException("未连接")
            admin.fetchTopicConfig(topic)?.attributes
                ?.entries
                ?.firstOrNull { it.key.trimStart('+') == "message.type" }
                ?.value
                ?.takeIf(String::isNotBlank)
        }

    /**
     * 删除主题：deleteTopicInBroker（全部 broker 地址）+ deleteTopicInNameServer
     * 两步清元数据；成功后剪掉慢扫缓存里的 topic 并发 OBJECTS_SCANNED 驱动列表 /
     * 拓扑刷新。
     */
    suspend fun deleteTopic(connectionId: String, topic: String) {
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.adminClient ?: throw IllegalStateException("未连接")
        // 命令历史审计：RocketMQ 阶段 1 未接入（与 kafka/rabbitmq 的 audited 同款，
        // 后续按 f25cc1a 的 CommandAudit 模式补）。
        withContext(Dispatchers.IO) { admin.deleteTopic(topic) }
        // 缓存剪除 + 事件驱动刷新（与 RabbitMQ 的 deleteExchange 同一模式）。
        ctx.cache?.let { cache ->
            cache.getAs<List<RocketMqTopicSnapshot>>(KEY_TOPICS).orEmpty()
                .filter { it.name != topic }
                .let { cache.put(KEY_TOPICS, it, ttlMs = objectsTtlMs(ctx)) }
            // groupLags 不动：消费组与 topic 删除解耦（组仍可能存在，只是无订阅）。
        }
        context.emit(
            name = RocketMqEvents.OBJECTS_SCANNED,
            payload = mapOf("connectionId" to connectionId),
        )
        rocketMqLog.info("topic deleted: connection=$connectionId topic=$topic")
    }

    // ---------------------------------------------------------------- 内部

    /** 慢扫：逐 topic 路由（受限并发）+ 消费组（含在线成员数，best-effort）。 */
    private suspend fun scanTopics(admin: RocketMqAdminClient): List<RocketMqTopicSnapshot> {
        val names = admin.fetchTopicList().sorted()
        val sem = Semaphore(ROUTE_CONCURRENCY)
        return coroutineScope {
            names.map { name ->
                async {
                    sem.withPermit {
                        runCatching { admin.fetchTopicRoute(name) }.getOrNull()?.let { route ->
                            RocketMqTopicSnapshot(
                                name = name,
                                readQueueNums = route.queueDatas.sumOf { it.readQueueNums },
                                writeQueueNums = route.queueDatas.sumOf { it.writeQueueNums },
                                perm = route.queueDatas.firstOrNull()?.perm ?: 0,
                                routes = route.queueDatas.map { qd ->
                                    RocketMqTopicRouteSnapshot(
                                        brokerName = qd.brokerName,
                                        readQueueNums = qd.readQueueNums,
                                        writeQueueNums = qd.writeQueueNums,
                                        perm = qd.perm,
                                    )
                                },
                            )
                        }
                    }
                }
            }.awaitAll().filterNotNull().sortedBy { it.name }
        }
    }

    private suspend fun scanGroups(admin: RocketMqAdminClient): List<RocketMqGroupSnapshot> {
        val groups = admin.fetchConsumerGroupList().sorted()
        return groups.map { group ->
            // 逐组容错：成员数是 best-effort（组不在线 / broker 慢都只降级该行，
            // 不能让一个组的 RemotingTimeoutException 炸掉整轮组列表）。
            val memberCount = runCatching { admin.fetchConsumerConnection(group) }
                .onFailure { rocketMqLog.warn("group member count failed: group=" + group + " error=" + it.message) }
                .getOrNull()
                ?.connectionSet?.size
            RocketMqGroupSnapshot(name = group, memberCount = memberCount)
        }
    }

    /** 慢扫数据 TTL：配置的慢扫间隔 × 1.5。 */
    private fun objectsTtlMs(ctx: RocketMqConnectionContext): Long =
        ctx.config.objectScanIntervalMinutes * 60_000L * OBJECTS_TTL_FACTOR.toLong()

    /** 快扫数据 TTL：配置的心跳间隔 × 2。 */
    private fun lagTtlMs(ctx: RocketMqConnectionContext): Long =
        ctx.config.statusScanIntervalSeconds * 1_000L * LAG_TTL_FACTOR.toLong()

    /** 连接处于 CONNECTED 时的上下文；否则 null —— 显式连接模型的扫描守卫。 */
    private fun connectedContext(connectionId: String): RocketMqConnectionContext? =
        RocketMqModule.connectionOrNull(connectionId)
            ?.takeIf { it.status == ConnectionStatus.Connected }

    private fun cacheOf(connectionId: String): CacheManager? =
        RocketMqModule.connectionOrNull(connectionId)?.cache

    /**
     * 整轮扫描失败即这条连接大概率已断：上下文与核心状态一起转 FAILED（UI 显示
     * 「连接失败」），定时任务下个轮次由状态守卫跳过，等用户重连。守卫 Connected：
     * 与 connect() 并发时不能把刚建好的连接踩成 Failed —— 与 kafka 的处理一致。
     */
    private fun markFailed(connectionId: String, message: String) {
        RocketMqModule.connectionOrNull(connectionId)?.let { ctx ->
            if (ctx.status == ConnectionStatus.Connected) {
                ctx.lastError = message
                ctx.status = ConnectionStatus.Failed
                ConnectionStore.reportStatus(connectionId, ConnectionStatus.Failed, context.moduleId)
                rocketMqLog.warn("connection marked FAILED after scan error: connection=$connectionId")
            }
        }
    }

    /** ClusterInfo（5.x 在 remoting.protocol.body）→ 模块快照（角色按 brokerId 推导）。 */
    private fun clusterSnapshot(ci: ClusterInfo): RocketMqClusterInfoSnapshot =
        RocketMqClusterInfoSnapshot(
            clusterName = ci.clusterAddrTable.keys.firstOrNull(),
            nameServers = emptyList(),
            brokers = ci.brokerAddrTable.values.flatMap { bd ->
                bd.brokerAddrs.map { (id, addr) ->
                    RocketMqBrokerSnapshot(
                        cluster = bd.cluster,
                        brokerName = bd.brokerName,
                        brokerId = id,
                        address = addr,
                        role = if (id == 0L) "Master" else "Slave",
                    )
                }
            },
        )

    /** 给日志 / 事件的失败原因：解掉 ExecutionException 包装，拿根因的消息。 */
    private fun errorMessage(error: Throwable): String =
        (error as? ExecutionException)?.cause?.message
            ?: error.message
            ?: error::class.simpleName
            ?: "unknown"
}
