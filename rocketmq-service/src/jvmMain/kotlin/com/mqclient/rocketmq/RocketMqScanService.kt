package com.mqclient.rocketmq

import com.mqclient.core.db.CommandAudit
import com.mqclient.core.cache.CacheManager
import com.mqclient.core.cache.putIfScanned
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats
import com.mqclient.mq.base.AbstractScanService

/**
 * 消费进度查询的**界面等待上限**（毫秒）。
 *
 * 为什么是 5 秒：这是一个「打开对话框」的同步查询，用户对它的耐心远小于扫描。
 * Broker 正常时 `examineConsumeStats` 在几十毫秒内返回；5 秒还没回来说明链路上
 * 某个环节卡住了，让用户继续盯着转圈毫无意义。
 *
 * **诚实的边界**：`examineConsumeStats` 是阻塞的 Java 调用，`withTimeout` 只能
 * 让**协程**不再等它 —— 线程仍会卡到 AdminClient 自己的 socket 超时
 * （`connectionTimeout`，默认 30s）。所以它保证的是「UI 不会永久卡死」，
 * 而不是「底层请求被取消」。想真正取消得用带 timeout 的 API，而
 * `examineConsumeStats` 的带超时重载要求指定 broker + topic，做不了「按组查全部」。
 */
private const val PROGRESS_TIMEOUT_MS = 5_000L

/**
 * RocketMQ 扫描服务：定时快扫 / 慢扫 + 缓存写入 + 事件通知，与 kafka 的
 * KafkaScanService 同一套模式（write-through 缓存、事件驱动 UI、失败分级）。
 *
 *  - **对象慢扫** [scanObjects]（默认 5min）：clusterInfo / topics（逐 topic 路由，
 *    受限并发 8）/ groups（含在线成员数，best-effort）三类并发查询；单类失败跳过
 *    （受限账号 ACL 是预期内现象）并**保留该类的上一轮缓存**，clusterInfo 失败
 *    （连接大概率已断）才整轮失败。结果全量替换写缓存。
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
class RocketMqScanService(
    context: ServiceContext,
) : AbstractScanService<RocketMqConnectionContext, RocketMqScanKind>(context, MODULE_ID) {

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
    /**
     * 写操作审计包裹（照 RabbitMQ 的 audited 模板 + RocketMQ 特有的数字原因码）：
     * 计时 → 执行 → 成功 / 失败各发一条 core.mq.command.executed（CommandAudit
     * 内部 runCatching，审计失败不影响本操作）。失败时沿 cause 链取协议响应码进
     * reasonCode 列（17 / 29 等；直接判最外层拿不到 —— ioCall 已包成 IllegalStateException），错误详情进
     * response，随后重抛让 UI 层弹错误框。读操作 / 现查不经此包裹；用户取消确认框
     * 时根本不会走到这里。
     */
    private suspend fun <T> audited(
        connectionId: String,
        command: String,
        block: suspend () -> T,
    ): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            CommandAudit.emit(
                connectionId = connectionId,
                command = command,
                status = "SUCCESS",
                response = null,
                requestTime = start,
                responseTime = System.currentTimeMillis(),
            )
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 沿 cause 链取响应码：`ioCall` 把协议异常包成 IllegalStateException，
            // 只判最外层会让审计日志的 reasonCode 恒为 null（与进度查询同一个坑）。
            val code = rocketMqResponseCode(e)
            CommandAudit.emit(
                connectionId = connectionId,
                command = command,
                status = "FAILED",
                response = CommandAudit.describeError(e),
                requestTime = start,
                responseTime = System.currentTimeMillis(),
                reasonCode = code?.toString(),
            )
            throw e
        }
    }

    /* ------------------------------ 基类钩子 ------------------------------ */

    override fun contextOrNull(connectionId: String): RocketMqConnectionContext? =
        RocketMqModule.connectionOrNull(connectionId)

    override val allKinds: Collection<RocketMqScanKind> = RocketMqScanKind.entries

    override fun describeKind(kind: RocketMqScanKind): String = kind.label

    override fun objectsScanIntervalMs(ctx: RocketMqConnectionContext): Long =
        ctx.config.objectScanIntervalMinutes * 60_000L

    override fun fastScanIntervalMs(ctx: RocketMqConnectionContext): Long =
        ctx.config.statusScanIntervalSeconds * 1_000L

    override fun reportScanFailure(connectionId: String, message: String): Boolean =
        RocketMqModule.reportScanFailure(connectionId, message)

    override fun reportScanFailures(connectionId: String, kinds: Set<RocketMqScanKind>) {
        RocketMqModule.reportScanFailures(connectionId, kinds)
    }

    override fun clearScanFailures(connectionId: String) {
        RocketMqModule.clearScanFailures(connectionId)
    }

    override val objectsScanFailedEvent: String = RocketMqEvents.OBJECTS_SCAN_FAILED

    /** 解掉 `ExecutionException` 包装：Remoting future 的根因藏在 cause 里。 */
    override fun errorMessage(error: Throwable): String =
        (error as? ExecutionException)?.cause?.message ?: super.errorMessage(error)

    /** 快扫 TTL 的模块内别名（基类公式见 [fastTtlMs]）。 */
    private fun lagTtlMs(ctx: RocketMqConnectionContext): Long = fastTtlMs(ctx)

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

    /**
     * 对象慢扫的两类**可局部失败**的查询（cluster 不属于它：NameServer 不可达等于
     * 连接已断，直接让整轮失败）。
     *
     * 单独分类是因为它们必须**按类**兑现到缓存上：topics 失败了就不能拿空表覆盖
     * `topics` 缓存 —— 那等于告诉 UI「这个集群里没有 topic」。
     */

    /** 断开 / 清缓存时同步清除扫描标记（ConnectionManager.disconnectNow 调用）。 */
    fun clearScanState(connectionId: String) {
        objectsScannedOnce.remove(connectionId)
    }

    // ---------------------------------------------------------------- 对象慢扫

    /** 定时任务入口（`rocketmq.<connectionId>.scan.objects`）。 */
    override suspend fun scanObjects(connectionId: String) = withScanLock(connectionId) {
        val (ctx, admin) = scanTargetOrNull(connectionId) ?: return@withScanLock
        val objectsTtl = objectsTtlMs(ctx)
        // 两路 async 并发写，用线程安全容器（每类只写自己的 key）。
        val failures = ConcurrentHashMap<RocketMqScanKind, String>()
        try {
            val result = withContext(Dispatchers.IO) {
                coroutineScope {
                    val cluster = async {
                        // cluster 分支失败 = NameServer 层不可达/被拒 = 连接大概率已断：
                        // **不吞** —— 上抛让整轮失败走 markFailed，各页面 Error + 重试
                        // （异常必须反映到 UI，不能只留日志）。
                        clusterSnapshot(admin.fetchClusterInfo())
                    }
                    val topics = async {
                        scanKindOr(failures, RocketMqScanKind.TOPICS, emptyList()) { scanTopics(admin) }
                    }
                    val groups = async {
                        scanKindOr(failures, RocketMqScanKind.GROUPS, emptyList()) { scanGroups(admin) }
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
            // 失败的类不写：空表写进去等于告诉 UI「这个集群里没有 topic / group」。
            cache.putIfScanned(KEY_TOPICS, RocketMqScanKind.TOPICS, failures, topics, objectsTtl)
            cache.putIfScanned(KEY_GROUPS, RocketMqScanKind.GROUPS, failures, groups, objectsTtl)
            // 首扫标记：部分失败也置位（保持原有契约）。理由：页面靠它区分「扫完了但确实
            // 没有对象（空态）」与「还在等首扫（Loading）」。不置位的话，部分失败会让页面
            // 永远转圈 —— 而连接状态已不再因此转 Failed，没有别的路径去兑现 Error。
            // 部分失败时缓存没被覆盖，所以已有数据仍在，页面显示的是上一轮。
            objectsScannedOnce[connectionId] = true

            // 把「哪几类没取到」交给模块存起来：UI 的提示条要能在页面切来切去后
            // 仍然知道「这份数据是残的」，而事件是瞬时的。
            RocketMqModule.reportScanFailures(connectionId, failures.keys.toSet())

            context.emit(
                name = RocketMqEvents.OBJECTS_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    // 部分结果：UI / 日志据此区分「扫全了」和「缺了几类」。
                    "partial" to failures.isNotEmpty(),
                    "failedKinds" to failures.keys.map { it.label },
                    "brokerCount" to cluster.brokers.size,
                    "topicCount" to topics.size,
                    "groupCount" to groups.size,
                ),
            )
            if (failures.isEmpty()) {
                rocketMqLog.info(
                    "objects scanned: connection=$connectionId brokers=" + cluster.brokers.size +
                        " topics=" + topics.size + " groups=" + groups.size,
                )
            } else {
                rocketMqLog.warn(
                    "objects scanned (partial): connection=$connectionId brokers=" + cluster.brokers.size +
                        " topics=" + topics.size + " groups=" + groups.size +
                        " failed=" + failures.keys.map { it.label },
                )
            }
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
    suspend fun scanGroupLags(connectionId: String) = withScanLock(connectionId) {
        val (ctx, admin) = scanTargetOrNull(connectionId) ?: return@withScanLock
        try {
            val groups = withContext(Dispatchers.IO) { admin.fetchConsumerGroupList() }
            val lags = withContext(Dispatchers.IO) {
                val sem = Semaphore(ROUTE_CONCURRENCY)
                coroutineScope {
                    groups.map { group ->
                        async {
                            sem.withPermit {
                                // 逐组容错 + **兜底 lag=0**：失败（组从未消费 / ACL / 超时）
                                // 不再从结果里抹掉该组 —— UI 没有 "未知" 形态；根因 warn
                                // 日志留痕，属性对话框现查时会看到真实错误。
                                runCatching {
                                    val stats = admin.fetchConsumerProgress(group)
                                    // offsetTable 拍平行（AI 上下文 / 未来队列级展示用）：
                                    // lag 降序截头（同一次 RPC 响应里的字段，零额外请求）。
                                    val rows = stats.offsetTable.entries
                                        .map { (mq, wrapper) ->
                                            RocketMqOffsetRowSnapshot(
                                                topic = mq.topic,
                                                broker = mq.brokerName,
                                                queueId = mq.queueId,
                                                maxOffset = wrapper.brokerOffset,
                                                consumerOffset = wrapper.consumerOffset,
                                                lag = (wrapper.brokerOffset - wrapper.consumerOffset).coerceAtLeast(0),
                                            )
                                        }
                                        .sortedByDescending { it.lag }
                                        .take(RocketMqGroupLagSnapshot.LAG_ROWS_CACHE_CAP)
                                    RocketMqGroupLagSnapshot(
                                        group = group,
                                        totalLag = stats.offsetTable.values.sumOf { wrapper ->
                                            (wrapper.brokerOffset - wrapper.consumerOffset).coerceAtLeast(0)
                                        },
                                        queueCount = stats.offsetTable.size,
                                        rows = rows,
                                    )
                                }.getOrElse {
                                    rocketMqLog.warn(
                                        "group lag failed, fallback to 0: group=" + group +
                                            " error=" + (it.message ?: it::class.simpleName),
                                    )
                                    RocketMqGroupLagSnapshot(group = group, totalLag = 0, queueCount = 0)
                                }
                            }
                        }
                    }.awaitAll()
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
            // 心跳跑通了即这条连接是活的：计数归零，若之前被误判 Failed 则自动恢复。
            RocketMqModule.reportScanSuccess(connectionId)
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

    // ---------------------------------------------------------------- 消费组详情

    /**
     * 消费组详情现查（属性对话框用，不进缓存），**局部降级**设计：
     *  - 基础配置：examineSubscriptionGroupConfig（broker 本地配置，**不依赖
     *    %RETRY% 路由**，组从未消费也可得）；
     *  - 成员 / 订阅 / 消费模式：examineConsumerConnectionInfo，best-effort（离线 /
     *    17 / 失败 → 空，UI 显示未知）；
     *  - 进度：examineConsumeStats，**带 5 秒硬超时**，失败按 [classifyProgressFailure]
     *    归因（CODE 17 / CODE 206 / ACL / 其他），原始摘要进 [RocketMqGroupDetailSnapshot.progressError]
     *    且完整异常栈进日志。进度失败不阻塞基础信息展示。
     * 整体只有「未连接 / clusterInfo 失败 / 无可用 Broker」才抛（对话框整页 Error）。
     *
     * 三个子查询各自独立 try —— **刻意不用 `runCatching`**：它会连同
     * `CancellationException` 一起吞掉，于是关掉对话框后这次查询还会继续跑完
     * （本项目在扫链路里已经踩过一次，见 AbstractScanService.scanKindOr 的注释）。
     */
    suspend fun fetchGroupDetail(connectionId: String, group: String): RocketMqGroupDetailSnapshot {
        val (ctx, admin) = targetOrThrow(connectionId)
        return withContext(Dispatchers.IO) {
            val config = bestEffort("group config", group) { admin.fetchGroupConfig(group) }
            val conn = bestEffort("group connection", group) { admin.fetchConsumerConnection(group) }

            var progressState = RocketMqProgressState.OK
            var progressError: String? = null
            val stats: ConsumeStats? = try {
                withTimeout(PROGRESS_TIMEOUT_MS) { admin.fetchConsumerProgress(group) }
            } catch (timeout: TimeoutCancellationException) {
                // 超时是**我们**给的界，不是协议错误：单独说清楚，别混进「查询失败」。
                progressState = RocketMqProgressState.FAILED
                progressError = "查询超时（${PROGRESS_TIMEOUT_MS / 1000}s 内 Broker 未返回）"
                rocketMqLog.warn(
                    "group progress timed out after ${PROGRESS_TIMEOUT_MS}ms: group=$group " +
                        "(底层阻塞调用不会被协程取消打断，线程会在 AdminClient 自身超时后释放)",
                )
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                val failure = classifyProgressFailure(error)
                progressState = failure.state
                progressError = failure.detail
                // 完整栈进日志（console + 文件）：只有 message 时，CODE 与调用链都丢了，
                // 现场问题只能靠猜。分类已在首行，栈在后面。
                rocketMqLog.warn(
                    "group progress failed: group=$group state=${failure.state} " +
                        "code=${failure.code ?: "-"} error=${error.message}\n" +
                        error.stackTraceToString(),
                )
                null
            }

            val rows = stats?.offsetTable?.entries?.map { (mq, wrapper) ->
                RocketMqOffsetRowSnapshot(
                    topic = mq.topic,
                    broker = mq.brokerName,
                    queueId = mq.queueId,
                    maxOffset = wrapper.brokerOffset,
                    consumerOffset = wrapper.consumerOffset,
                    lag = (wrapper.brokerOffset - wrapper.consumerOffset).coerceAtLeast(0),
                )
            }?.sortedWith(compareBy({ it.topic }, { it.broker }, { it.queueId })) ?: emptyList()
            RocketMqGroupDetailSnapshot(
                name = group,
                configFound = config != null,
                consumeEnable = config?.isConsumeEnable,
                orderly = config?.isConsumeMessageOrderly,
                consumeMode = conn?.messageModel?.name ?: "",
                subscriptions = conn?.subscriptionTable?.keys?.sorted().orEmpty(),
                memberCount = conn?.connectionSet?.size,
                progressState = progressState,
                progressError = progressError,
                rows = rows,
                totalLag = rows.sumOf { it.lag },
            )
        }
    }

    /**
     * best-effort 子查询：失败记日志返回 null，但**取消必须原样上抛**。
     *
     * 等价于 `runCatching { }.getOrNull()`，唯一区别是不吞 `CancellationException`
     * —— 对话框关掉后这几个查询不该继续跑完。
     */
    private suspend fun <T : Any> bestEffort(label: String, group: String, block: suspend () -> T?): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            rocketMqLog.warn("$label failed: group=$group error=${error.message}\n" + error.stackTraceToString())
            null
        }

    // ---------------------------------------------------------------- 主题创建

    /** 创建 / 更新主题：逐 broker 写 TopicConfig（读 / 写队列数 + 权限位）。 */
    suspend fun createTopic(
        connectionId: String,
        topicName: String,
        readQueues: Int,
        writeQueues: Int,
        perm: Int,
    ) {
        val (ctx, admin) = targetOrThrow(connectionId)
        audited(connectionId, "CREATE TOPIC $topicName (read: $readQueues, write: $writeQueues, perm: $perm)") {
            withContext(Dispatchers.IO) { admin.createTopic(topicName, readQueues, writeQueues, perm) }
        }
        rocketMqLog.info(
            "topic created: connection=$connectionId topic=$topicName read=$readQueues write=$writeQueues perm=$perm",
        )
    }

    // ---------------------------------------------------------------- 消费组创建

    /**
     * 创建消费组：经 `createAndUpdateSubscriptionGroupConfig` 写入全部 Master
     * broker 的订阅组配置（update 语义幂等 —— 已存在不会报错，等同"改配置"）。
     * 成功后由 UI 触发一轮慢扫刷新列表（组列表数据源就是订阅组配置）。
     */
    suspend fun createConsumerGroup(connectionId: String, groupName: String, orderly: Boolean) {
        val (ctx, admin) = targetOrThrow(connectionId)
        audited(connectionId, "CREATE GROUP " + groupName.trim() + orderly.takeIf { it }?.let { " [orderly]" }.orEmpty()) {
            withContext(Dispatchers.IO) { admin.createSubscriptionGroup(groupName.trim(), orderly) }
        }
        rocketMqLog.info(
            "consumer group created: connection=$connectionId group=$groupName orderly=$orderly",
        )
    }

    /**
     * 删除消费组：全部 broker 地址移除订阅组配置；成功后剪掉慢扫缓存里的组与
     * Lag 并发 OBJECTS_SCANNED 驱动列表刷新。组的 %RETRY%/%DLQ% topic 不在此
     * 清理（属 topic 生命周期，broker 会自行回收）。
     */
    suspend fun deleteConsumerGroup(connectionId: String, group: String) {
        val (ctx, admin) = targetOrThrow(connectionId)
        audited(connectionId, "DELETE GROUP $group") {
            withContext(Dispatchers.IO) { admin.deleteSubscriptionGroup(group) }
        }
        ctx.cache?.let { cache ->
            // 原子读-改-写：与慢扫 / 心跳并发时，先读后写会把对方刚写入的组整段抹掉。
            cache.updateIfPresentAs<List<RocketMqGroupSnapshot>>(KEY_GROUPS, objectsTtlMs(ctx)) { groups ->
                groups.filter { it.name != group }
            }
            cache.updateIfPresentAs<Map<String, RocketMqGroupLagSnapshot>>(KEY_GROUP_LAGS, lagTtlMs(ctx)) { lags ->
                lags.filterKeys { it != group }
            }
        }
        context.emit(
            name = RocketMqEvents.OBJECTS_SCANNED,
            payload = mapOf("connectionId" to connectionId),
        )
        rocketMqLog.info("consumer group deleted: connection=$connectionId group=$group")
    }

    // ---------------------------------------------------------------- Topic 属性 / 删除

    /**
     * Topic 消息类型现查（不进缓存 —— 属性对话框要新鲜数据）：经 Master 地址 examine
     * TopicConfig 并提取 attributes["message.type"]（5.x；4.x / 未设置返回 null，
     * UI 回落 Normal）。TopicConfig 不出本模块（rocketmq-ui 无 rocketmq 类路径）。
     */
    suspend fun fetchTopicMessageType(connectionId: String, topic: String): String? =
        withContext(Dispatchers.IO) {
            val (ctx, admin) = targetOrThrow(connectionId)
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
        // 第二道防呆（第一道是属性对话框按钮禁用）：绕过 UI 的调用路径也拦下。
        if (isSystemTopic(topic)) {
            throw IllegalStateException("系统内置主题不允许删除：$topic")
        }
        val (ctx, admin) = targetOrThrow(connectionId)
        audited(connectionId, "DELETE TOPIC $topic") {
            withContext(Dispatchers.IO) { admin.deleteTopic(topic) }
        }
        // 缓存剪除 + 事件驱动刷新（与 RabbitMQ 的 deleteExchange 同一模式）。
        ctx.cache?.let { cache ->
            // 原子读-改-写：理由同 deleteConsumerGroup（并发慢扫整表替换会覆盖这次剪除）。
            cache.updateIfPresentAs<List<RocketMqTopicSnapshot>>(KEY_TOPICS, objectsTtlMs(ctx)) { topics ->
                topics.filter { it.name != topic }
            }
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
            // 逐组容错 + **兜底为 0**：组离线（MQClientException，fetchConsumerConnection
            // 内部已转 null）与其他失败一律落 0 —— 卡片区没有 "未知" 形态；失败原因
            // warn 日志留痕（group + 根因），不让 UI 猜。
            val conn = runCatching { admin.fetchConsumerConnection(group) }
                .onFailure {
                    rocketMqLog.warn(
                        "group member count failed, fallback to 0: group=" + group +
                            " error=" + it.message,
                    )
                }
                .getOrNull()
            // 同一次 RPC 响应里顺手带走的字段（零额外请求）：消费模式与订阅列表，
            // 消费组 AI 上下文的 consumed_by 关联与属性展示用；离线 / 失败为 null / 空。
            RocketMqGroupSnapshot(
                name = group,
                memberCount = conn?.connectionSet?.size ?: 0,
                consumeMode = conn?.messageModel?.name,
                subscribedTopics = conn?.subscriptionTable?.keys?.sorted().orEmpty(),
            )
        }
    }

    /**
     * 请求入口的**统一守卫**：只看「有没有管理客户端」，不拿 status 当否决票。
     *
     * 这是与 RabbitMQ / Kafka 同口径的修正。`status` 是**扫描结论**（可能因一次抖动被
     * 误标），`adminClient` 才是**能力事实**。用结论否决能力，就会出现「明明连着却报
     * 未连接」；反过来让请求自己去试，真断了它会抛连接异常，展示的才是真实原因。
     */
    private data class ScanTarget(
        val ctx: RocketMqConnectionContext,
        val admin: RocketMqAdminClient,
    )

    /** 有客户端就返回；真的没有则抛带具体原因的异常。 */
    private fun targetOrThrow(connectionId: String): ScanTarget {
        val ctx = RocketMqModule.connectionOrNull(connectionId)
            ?: throw IllegalStateException("连接未登记：该连接已被删除或模块未初始化")
        val admin = ctx.adminClient ?: throw IllegalStateException(connectionRefusalText(ctx))
        return ScanTarget(ctx, admin)
    }

    /**
     * 定时扫描专用的宽松版：只要管理客户端还在就返回，`status` 不参与判定。
     *
     * 为什么定时扫描必须忽略 status：只有这样才能**自愈** —— 被判 Failed 之后心跳
     * 继续跑，第一次成功即可恢复成 Connected（见
     * [RocketMqConnectionManager.onScanSucceeded]）。
     */
    private fun scanTargetOrNull(connectionId: String): ScanTarget? {
        val ctx = RocketMqModule.connectionOrNull(connectionId) ?: return null
        val admin = ctx.adminClient ?: return null
        return ScanTarget(ctx, admin)
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

}

/**
 * 系统保留主题判定（删除防呆 + 拓扑/列表过滤共用，顶层函数供 service 与 UI 双侧
 * import）：%. 前缀（重试 / 死信 / POP 回调等）、调度 / 弹幂 / 事务 / 轨迹 / 位点
 * 漂移 / 自测 / TBW102 / 默认主题，大小写两种形态的 RMQ_SYS_ 都覆盖（服务端实测
 * 两种均有）。
 */
fun isSystemTopic(name: String): Boolean =
    name.startsWith("%") ||
        name.startsWith("SCHEDULE_TOPIC_") ||
        name.startsWith("TBW102") ||
        name.startsWith("SELF_TEST_TOPIC") ||
        name.startsWith("OFFSET_MOVED_EVENT") ||
        name.startsWith("DEFAULT_TOPIC") ||
        name.startsWith("RMQ_SYS_", ignoreCase = true)

/**
 * 系统保留消费组判定（消费组属性对话框删除防呆用）：broker 系统组件注册的
 * 订阅组前缀 —— CID_RMQ_SYS_（服务端内部组件）/ CID_ONS_（云原生组件）。
 */
fun isSystemGroup(name: String): Boolean =
    name.startsWith("CID_RMQ_SYS_") || name.startsWith("CID_ONS_")

/**
 * 对象慢扫里两类**可局部失败**的查询（cluster 不属于它：NameServer 不可达等于连接
 * 已断，直接让整轮失败）。
 *
 * 顶层公开（不再是 `RocketMqScanService` 的 internal 嵌套）：UI 的「部分数据未刷新」
 * 提示条要按类型拼文案。枚举不含实现，暴露它比在 UI 里用字符串标签匹配安全。
 */
enum class RocketMqScanKind(val label: String) {
    TOPICS("topics"),
    GROUPS("groups"),
}
