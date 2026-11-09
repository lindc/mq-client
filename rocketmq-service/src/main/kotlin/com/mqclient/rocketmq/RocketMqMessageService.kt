package com.mqclient.rocketmq

import com.mqclient.core.ServiceException
import com.mqclient.message.interpreter.isProbablyBinary
import com.mqclient.message.interpreter.previewTextOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.comparisons.compareByDescending
import kotlin.comparisons.thenBy
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer
import org.apache.rocketmq.client.consumer.PullStatus
import org.apache.rocketmq.client.producer.DefaultMQProducer
import org.apache.rocketmq.client.producer.SendResult
import org.apache.rocketmq.common.message.Message
import org.apache.rocketmq.common.message.MessageConst
import org.apache.rocketmq.common.message.MessageExt
import org.apache.rocketmq.common.message.MessageQueue
import org.apache.rocketmq.remoting.exception.RemotingException
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException

/**
 * RocketMQ 消息读写服务：浏览（读）/ 发送（写），与 Kafka 的 KafkaMessageService
 * 同职责、同「即时查询、不进缓存、不发事件」的定位。
 *
 * **句柄生命周期：Producer / PullConsumer 每次操作临时创建、finally 里 shutdown**
 * （对标 KafkaMessageService 的临时 consumer/producer 模式）—— 不挂
 * [RocketMqConnectionContext]（临时实例用完即走，断开连接时没有游散句柄要追杀）。
 * ACL 与 TLS 经 [RocketMqTlsStart]（AdminExt / Producer / PullConsumer 共用同一份
 * 证书准备与 start 窗口）。
 *
 * **浏览不消费**：PullConsumer 只调 `pull`（按 MessageQueue + offset 定点拉），
 * 全程**不调 updateConsumeOffset** —— 位点不落服务端、不进本地 offset store，
 * 不影响任何消费组的进度（RocketMQ 天然只读浏览，连 Kafka 的「不提交」纪律
 * 都不需要刻意维持）。subExpression 固定 `*`（全 Tag 拉取，Tag 过滤交给 UI 本地做）。
 *
 * 续读游标（`PullResult.nextBeginOffset`）由调用方持有 —— service 无状态化，每次
 * 调用自包含。全程 Dispatchers.IO，与 [RocketMqScanService] 的纪律一致；
 * CancellationException 一律原样上抛。
 */
open class RocketMqMessageService {

    private companion object {
        /** 单次读取的条数上限（UI 侧同样约束）。 */
        const val MAX_READ_COUNT = 500

        /**
         * 单次 `pull` / `maxOffset` 的**协议层**超时（毫秒）。
         *
         * 必须传给客户端本身，而不是只在外层包 `withTimeout`：`pull` 是阻塞的
         * socket 调用，协程取消打断不了它。默认的 4 参重载用
         * `getConsumerPullTimeoutMillis()`（10 秒）并可能重试，而这条调用**期间
         * 一直持着共享 consumer 的锁**（见 readMessages）—— 卡一次，后续每次读取
         * 都要排队，表现就是「越开越慢」。
         */
        const val READ_TIMEOUT_MS = 5_000L

        /**
         * 单次读取的整体超时（毫秒）。路由指向坏地址（如 BenchmarkTest 注册到了
         * RabbitMQ 的 11207 端口）时，逐队列的失败往返会把读取拖到百秒级 —— 这里
         * 到点强制取消。pull / maxOffset 都经 runInterruptible，取消能真正打断。
         */
        const val READ_TOTAL_TIMEOUT_MS = 15_000L

        /** 「全部 Queue」启用并行遍历的队列数阈值（小 topic 单实例顺序，行为不变）。 */
        const val PARALLEL_THRESHOLD = 64

        /** 并行遍历的临时 PullConsumer 实例数（= 分片数）。 */
        const val READ_QUEUE_PARALLELISM = 8

        /** 并行路径下每队列的耗时预算（实测 ~32ms/队列含 3 次 RPC，50ms 留余量）。 */
        const val PER_QUEUE_BUDGET_MS = 50L

        /** 临时浏览实例的 group 前缀（broker 侧可见，标识本工具的只读浏览）。 */
        const val BROWSE_GROUP_PREFIX = "CID_mqclient_browse_"

        /** 临时发送实例的 group 前缀（RocketMQ 惯例生产组以 PID_ 开头）。 */
        const val SEND_GROUP_PREFIX = "PID_mqclient_"

        /**
         * `MessageExt.properties` 里的系统属性：浏览详情的「用户属性」小节不展示
         * （发送方没 putUserProperty 过它们 —— 是客户端 / broker 自动附加的）。
         */
        val SYSTEM_PROPERTY_KEYS: Set<String> = setOf(
            MessageConst.PROPERTY_TAGS,
            MessageConst.PROPERTY_KEYS,
            MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX,
            MessageConst.PROPERTY_WAIT_STORE_MSG_OK,
            MessageConst.PROPERTY_DELAY_TIME_LEVEL,
            MessageConst.PROPERTY_RETRY_TOPIC,
            MessageConst.PROPERTY_REAL_TOPIC,
            MessageConst.PROPERTY_REAL_QUEUE_ID,
            MessageConst.PROPERTY_TRANSACTION_ID,
            MessageConst.PROPERTY_TRANSACTION_PREPARED,
            MessageConst.PROPERTY_PRODUCER_GROUP,
            MessageConst.PROPERTY_MAX_OFFSET,
            MessageConst.PROPERTY_MIN_OFFSET,
            MessageConst.PROPERTY_CONSUME_START_TIMESTAMP,
            MessageConst.PROPERTY_ORIGIN_MESSAGE_ID,
            MessageConst.PROPERTY_BORN_HOST,
            MessageConst.PROPERTY_BORN_TIMESTAMP,
            MessageConst.PROPERTY_CLUSTER,
            MessageConst.PROPERTY_TRACE_SWITCH,
            MessageConst.PROPERTY_TRACE_CONTEXT,
            MessageConst.PROPERTY_MSG_REGION,
            MessageConst.PROPERTY_TRANSFER_FLAG,
            MessageConst.PROPERTY_CORRECTION_FLAG,
            MessageConst.PROPERTY_INNER_MULTI_DISPATCH,
            MessageConst.PROPERTY_INNER_MULTI_QUEUE_OFFSET,
            MessageConst.PROPERTY_POP_CK,
            MessageConst.PROPERTY_POP_CK_OFFSET,
            MessageConst.PROPERTY_FIRST_POP_TIME,
            MessageConst.PROPERTY_RECONSUME_TIME,
            MessageConst.PROPERTY_CRC32,
        )
    }

    // ---------------------------------------------------------------- 队列查询

    /**
     * topic 的可读队列号列表（浏览页 Queue 下拉的数据源）：走 AdminExt 的 topic
     * 路由（`readQueueNums`），不启动临时实例。多 broker 各报各的队列数，取最大
     * —— 下拉多出来的队列号 pull 时会得到可读的「队列不存在」错误，不静默吞。
     */
    open suspend fun fetchQueueIds(connectionId: String, topic: String): List<Int> {
        val ctx = connectedContext(connectionId)
        val adminClient = ctx.adminClient
            ?: throw ServiceException(
                "rocketmq_err_not_connected_messages",
                fallbackMessage = "Connection not established or already lost; cannot read or send messages",
            )
        val route = adminClient.fetchTopicRoute(topic)
        val maxReadQueues = route.queueDatas
            .maxOfOrNull { it.readQueueNums }
            ?: 0
        return (0 until maxReadQueues.coerceAtLeast(0)).toList()
    }

    // ---------------------------------------------------------------- 浏览（读）

    /**
     * 从指定队列的 [offset] 起读一页消息。**只 pull、不提交任何位点**，不影响消费
     * 进度。
     *
     * @param queueId 队列号（fetchQueueIds 给出的范围）
     * @param offset 起点（队列内 offset；续读传上页的 nextBeginOffset）
     * @param count 读取条数（1..500，超范围收敛）
     */
    open suspend fun readMessages(
        connectionId: String,
        topic: String,
        queueId: Int?,
        start: RocketMqReadStart,
        count: Int,
        cursor: Map<Int, Long>? = null,
    ): RocketMqMessagePage {
        val ctx = connectedContext(connectionId)
        val perfStart = System.currentTimeMillis()
        rocketMqLog.info(
            "[MQ-Perf][RocketMQ] Service 开始处理拉取请求: topic=$topic, queueId=$queueId, count=$count, connectionId=$connectionId",
        )
        val capped = count.coerceIn(1, MAX_READ_COUNT)
        val clients = ctx.clients()
        // 路由查询：共享 PullConsumer，锁内一次（consumer 非线程安全）；自身有
        // Remoting 层超时兜底。遍历段见下方并行化说明。
        return withContext(Dispatchers.IO) {
            val queues = withTimeout(READ_TOTAL_TIMEOUT_MS) {
            clients.access.withLock {
                // 实例获取：复用共享 PullConsumer 时 ≈0ms；首次调用含 start（建连）。
                val instanceStart = System.currentTimeMillis()
                val consumer = clients.pullConsumer()
                val instanceMs = System.currentTimeMillis() - instanceStart
                rocketMqLog.info(
                    "[MQ-Perf][RocketMQ] 获取/初始化客户端实例耗时=${instanceMs}ms" +
                        if (instanceMs > 100) " (首次调用：含 client start 建连)" else " (共享实例复用)",
                )
                val allQueues = consumer.fetchSubscribeMessageQueues(topic).toList()
                val queues = if (queueId == null) {
                    allQueues
                } else {
                    allQueues.filter { it.queueId == queueId }
                        .ifEmpty {
                            throw ServiceException(
                                "rocketmq_err_queue_not_found",
                                args = listOf(queueId),
                                fallbackMessage = "Queue %1\$d not found (topic routing may have changed — click Read to refresh)",
                            )
                        }
                }
                queues
            }
            }

            // ---- 遍历并行化：benchmark 类 topic 队列数巨大（实测 BenchmarkTest 有
            //     3072 个队列），顺序遍历分钟级。共享 consumer 非线程安全不能并发，
            //     并行路径改用**一次性临时 PullConsumer**（每分片一个，对标发送链路的
            //     临时 producer 模式）：pull 全程手动 begin / 不提交位点，实例间无共享
            //     状态。小 topic（< PARALLEL_THRESHOLD）仍单实例顺序，行为不变。
            val parallelism = if (queues.size >= PARALLEL_THRESHOLD) READ_QUEUE_PARALLELISM else 1
            // 动态超时：正常 topic 固定 15s；大 topic 按 队列数 × 每队列预算 ÷ 并发度
            // 放大（BenchmarkTest：3072 × 50ms / 8 ≈ 19s，总 ~34s）。
            val totalTimeoutMs = READ_TOTAL_TIMEOUT_MS + queues.size * PER_QUEUE_BUDGET_MS / parallelism
            rocketMqLog.info(
                "[MQ-Perf][RocketMQ] 队列数=${queues.size}, 并发度=$parallelism, 本次超时=${totalTimeoutMs}ms",
            )
            val ioTotal = AtomicLong()
            val parseTotal = AtomicLong()
            val collected = Collections.synchronizedList(mutableListOf<RocketMqMessage>())
            val nextCursor = ConcurrentHashMap<Int, Long>()

            /** 单队列拉取（临时 / 共享实例均可）：begin -> pull -> 解析 -> maxOffset 判尾。 */
            suspend fun pullOne(consumer: DefaultMQPullConsumer, mq: MessageQueue) {
                // 每队列起点 offset：续读用 cursor；首页按起点策略算。
                val begin = cursor?.get(mq.queueId) ?: when (start) {
                    is RocketMqReadStart.Latest -> {
                        // 回退 N 条：maxOffset - count，落底到 0（防 OutOfRange）。
                        val max = maxOffsetOf(consumer, mq)
                        (max - capped).coerceAtLeast(0L)
                    }
                    is RocketMqReadStart.Earliest -> 0L
                    is RocketMqReadStart.AtOffset -> start.offset.coerceAtLeast(0L)
                }
                // runInterruptible（而非裸调用）：UI 取消（关对话框 / 点重试）时对阻塞
                // 线程 Thread.interrupt()，pull 立刻抛返回。协程取消本身打不断阻塞
                // socket，裸调用下僵尸 pull 要等满 5s 协议超时才放锁 —— 期间所有后续
                // 读取都排队，「越开越慢」的直接来源。
                var t = System.currentTimeMillis()
                val pullResult = runInterruptible {
                    consumer.pull(mq, "*", begin, capped, READ_TIMEOUT_MS)
                }
                ioTotal.addAndGet(System.currentTimeMillis() - t)
                // 数据解析：MessageExt -> RocketMqMessage（DTO -> Domain）。
                t = System.currentTimeMillis()
                if (pullResult.pullStatus == PullStatus.FOUND) {
                    collected += pullResult.msgFoundList.orEmpty().map { it.toMessage() }
                }
                parseTotal.addAndGet(System.currentTimeMillis() - t)
                // 队列尾判定：nextBeginOffset >= maxOffset → 无更多。maxOffset 查询
                // 失败（-1）时不误判「已耗尽」，把游标交回调用方再试一轮。
                t = System.currentTimeMillis()
                val maxOffset = maxOffsetOf(consumer, mq)
                ioTotal.addAndGet(System.currentTimeMillis() - t)
                val next = pullResult.nextBeginOffset
                val hasMore = pullResult.pullStatus == PullStatus.FOUND &&
                    (maxOffset < 0 || next < maxOffset)
                if (hasMore) nextCursor[mq.queueId] = next
            }

            withTimeout(totalTimeoutMs) {
                // 均分队列，每分片一个临时实例（并行创建 start）。
                val chunkSize = ceil(queues.size / parallelism.toDouble()).toInt().coerceAtLeast(1)
                val chunks = queues.chunked(chunkSize)
                val deferred = chunks.map { async { clients.tempPullConsumer() } }
                val consumers = ArrayList<DefaultMQPullConsumer>(deferred.size)
                try {
                    // 顺序 await，不用 awaitAll：后者任一分片失败就整个抛出，
                    // `consumers` 从未赋值，已启动的兄弟实例再也没人关。
                    for (d in deferred) consumers += d.await()
                    coroutineScope {
                        chunks.forEachIndexed { index, chunk ->
                            launch {
                                for (mq in chunk) pullOne(consumers[index], mq)
                            }
                        }
                    }
                } finally {
                    // 取消 / 超时下 finally 仍必须完成收尾。
                    withContext(NonCancellable) {
                        deferred.forEach { it.cancel() }
                        consumers.forEach { consumer -> clients.shutdownTemp(consumer) }
                    }
                }
            }

                rocketMqLog.info(
                    "[MQ-Perf][RocketMQ] read done: topic=$topic, queues=${queues.size}, parallelism=$parallelism, " +
                        "ioMs=${ioTotal.get()}, parseMs=${parseTotal.get()}, messages=${collected.size}, " +
                        "totalMs=${System.currentTimeMillis() - perfStart}",
                )
                // 按 storeTimestamp 降序合并（同刻回退 queueId / offset，保证稳定排序）。
                val merged = collected.sortedWith(
                    compareByDescending<RocketMqMessage> { it.storeTimestamp }
                        .thenBy { it.queueId }
                        .thenBy { it.offset },
                )
                RocketMqMessagePage(
                    messages = merged,
                    nextBeginOffset = nextCursor.toMap().ifEmpty { null },
                )
        }
    }

    /**
     * maxOffset 查询：失败兜回 -1（上层据此不误判「队列已空」）。
     *
     * **必须可打断**：真机实测，BenchmarkTest 这类路由指向坏地址（broker 地址注册成了
     * RabbitMQ 的 11207 端口）时，12 个队列顺序 maxOffset 各撞 ~9s 且 `runCatching`
     * 静默吞错 —— 一次读取拖成 107 秒黑洞，且旧实现不可打断，关闭对话框也没响应。
     * runInterruptible 让取消（关对话框 / 整体超时）立刻 Thread.interrupt() 返回。
     */
    private suspend fun maxOffsetOf(consumer: DefaultMQPullConsumer, mq: MessageQueue): Long =
        try {
            runInterruptible { consumer.maxOffset(mq) }
        } catch (e: Throwable) {
            // 关键：被取消时（withTimeout 到点 / 关对话框）RocketMQ 会把
            // InterruptedException 包装成 MQClientException 抛出 —— 不是
            // CancellationException，直接返回 -1 会丢掉取消语义，循环继续撞下一个
            // 队列。用 ensureActive 把取消翻译回来；正常业务失败才 -1 兜底。
            currentCoroutineContext().ensureActive()
            rocketMqLog.warn(
                "[MQ-Perf][RocketMQ] maxOffset failed: queue=${mq.queueId}, " +
                    "异常类型=${e::class.simpleName}, 详情=${e.message}",
            )
            -1L
        }


    // ---------------------------------------------------------------- 发送（写）

    /**
     * 发送一条消息（同步 send，阻塞等 broker 确认）。使用连接级共享 Producer
     * （[RocketMqMessageClients.producer]，懒建一次、整段持 access 锁、绝不单次
     * shutdown）—— 与 [RocketMqAdminClient] 同为重量级连接句柄，随连接存活。
     * 轻量句柄隔离由 [RocketMqMessageClients.tempPullConsumer] 在浏览链路负责。
     *
     * 消息生产**不进写操作审计**（mq_command 表语义是管理操作，且高频批量会膨胀
     * 审计表 —— 与 Kafka / IBM MQ 的发送口径一致）。
     *
     * @param tags 消息 Tag（可选，null/空 = 不设）
     * @param keys 业务键（可选，多值空格分隔；null/空 = 不设）
     * @param userProperties 用户属性（Headers 编辑器的键值对；key 空白的行由 UI 过滤）
     */
    suspend fun sendMessage(
        connectionId: String,
        topic: String,
        tags: String? = null,
        keys: String? = null,
        body: ByteArray,
        userProperties: List<Pair<String, String>> = emptyList(),
    ): RocketMqSendResult {
        val ctx = connectedContext(connectionId)
        val timeoutMs = ctx.config.connectionTimeout * 1_000L
        val clients = ctx.clients()
        return withContext(Dispatchers.IO) {
            // 共享 Producer：整段持锁（与 pull consumer 共用同一把 access 锁）。
            clients.access.withLock {
                val producer = clients.producer()
                try {
                    val message = Message(
                        topic,
                        tags?.trim()?.takeIf(String::isNotEmpty),
                        keys?.trim()?.takeIf(String::isNotEmpty),
                        body,
                    )
                    userProperties.forEach { (key, value) ->
                        if (key.isNotBlank()) message.putUserProperty(key.trim(), value)
                    }
                    val result = producer.send(message, timeoutMs)
                    rocketMqLog.info(
                        "message sent: topic=" + topic +
                            " broker=" + result.messageQueue.brokerName +
                            " queueId=" + result.messageQueue.queueId +
                            " offset=" + result.queueOffset +
                            " msgId=" + result.msgId +
                            " size=" + body.size,
                    )
                    RocketMqSendResult.success(
                        msgId = result.msgId,
                        brokerName = result.messageQueue.brokerName,
                        queueId = result.messageQueue.queueId,
                        offset = result.queueOffset,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    RocketMqSendResult.failure(sendError(error, timeoutMs))
                }
                // 不 shutdown：producer 是连接级共享句柄，随连接断开 / 程序退出统一关。
            }
        }
    }

    // ---------------------------------------------------------------- 内部零件

    /**
     * 取本连接的共享消息客户端（懒建，挂在 context 上）。
     *
     * 创建点**只有这里一处**。守卫用 `adminClient != null` —— 与 [connectedContext]
     * 同口径：管理客户端还在（即这条连接真的连上过）就允许建消息句柄，不拿 `status`
     * 当否决票。否则一次抖动误标的 `Failed` 会把消息浏览 / 发送一并堵死，而底层连接
     * 其实还能用。
     *
     * 再加第二个创建点就会出现两把锁、两份句柄 —— 孤儿句柄正是这么来的。
     */
    private fun RocketMqConnectionContext.clients(): RocketMqMessageClients =
        messageClients.acquire(canCreate = { adminClient != null }) {
            RocketMqMessageClients(config)
        }

    private fun connectedContext(connectionId: String): RocketMqConnectionContext =
        RocketMqModule.connectionOrNull(connectionId)
            ?.takeIf { it.adminClient != null }
            ?: throw RocketMqModule.connectionOrNull(connectionId)?.let { connectionRefusal(it) }
                ?: ServiceException(
                    "rocketmq_err_not_registered",
                    fallbackMessage = "Connection not registered: it was deleted or the module is not initialized",
                )

    /** `MessageExt` → 浏览/详情一体模型（body 全量随行，见 [RocketMqMessage] KDoc）。 */
    private fun MessageExt.toMessage(): RocketMqMessage {
        val bodyBytes = body ?: ByteArray(0)
        return RocketMqMessage(
            topic = topic,
            queueId = queueId,
            offset = queueOffset,
            msgId = msgId,
            tags = tags,
            keys = keys,
            bornTimestamp = bornTimestamp,
            storeTimestamp = storeTimestamp,
            bornHost = bornHostString,
            valuePreview = previewTextOf(bodyBytes, MESSAGE_PREVIEW_BYTES),
            valueHex = hexOf(bodyBytes),
            valueProbablyBinary = isProbablyBinary(bodyBytes, codedCharSetId = 1208),
            valueSize = storeSize,
            body = bodyBytes,
            userProperties = properties
                ?.filterKeys { it !in SYSTEM_PROPERTY_KEYS }
                ?.toSortedMap()
                ?: emptyMap(),
        )
    }

    /** hex 串，截断到 2×[MESSAGE_PREVIEW_BYTES]（与预览字节对齐）。 */
    private fun hexOf(bytes: ByteArray): String {
        val end = minOf(bytes.size, MESSAGE_PREVIEW_BYTES)
        val sb = StringBuilder(end * 2)
        for (i in 0 until end) {
            sb.append("%02x".format(bytes[i]))
        }
        return sb.toString()
    }

    /** 给失败结果的错误原因：常见发送异常 → 人类可读文案（UI 直出）。 */
    /**
     * 发送失败 → [ServiceException]（errorKey = `rocketmq_err_send_*`，展示端在
     * 渲染时点翻译；message 是英文兜底）。经 [RocketMqSendResult.failure] 带给 UI。
     */
    private fun sendError(error: Throwable, timeoutMs: Long): ServiceException = when (error) {
        is org.apache.rocketmq.client.exception.MQClientException ->
            ServiceException(
                "rocketmq_err_send_client_error",
                args = listOf(error.errorMessage ?: error.message ?: error::class.simpleName ?: "unknown"),
                fallbackMessage = "Send failed: %1\$s (if the route is missing, check that the topic exists and the NameServer is reachable)",
            )
        is org.apache.rocketmq.client.exception.MQBrokerException ->
            ServiceException(
                "rocketmq_err_send_broker_rejected",
                args = listOf(error.responseCode, error.errorMessage ?: error.message ?: "unknown"),
                fallbackMessage = "Broker rejected (code %1\$d): %2\$s",
            )
        is RemotingTimeoutException ->
            ServiceException(
                "rocketmq_err_send_timeout",
                args = listOf(timeoutMs / 1000),
                fallbackMessage = "Send timed out (%1\$ss): the broker did not acknowledge in time",
            )
        is RemotingException ->
            ServiceException(
                "rocketmq_err_send_network",
                args = listOf(error.message ?: error::class.simpleName ?: "unknown"),
                fallbackMessage = "Send failed (network error): %1\$s",
            )
        else -> ServiceException(
            "rocketmq_err_send_client_error",
            args = listOf(error.message ?: error::class.simpleName ?: "unknown"),
            fallbackMessage = "Send failed: %1\$s",
        )
    }
}
