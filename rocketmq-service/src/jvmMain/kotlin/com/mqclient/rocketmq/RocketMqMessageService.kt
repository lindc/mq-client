package com.mqclient.rocketmq

import com.mqclient.message.interpreter.isProbablyBinary
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer
import org.apache.rocketmq.client.consumer.PullStatus
import org.apache.rocketmq.client.producer.DefaultMQProducer
import org.apache.rocketmq.client.producer.SendResult
import org.apache.rocketmq.common.message.Message
import org.apache.rocketmq.common.message.MessageConst
import org.apache.rocketmq.common.message.MessageExt
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
class RocketMqMessageService {

    private companion object {
        /** 单次读取的条数上限（UI 侧同样约束）。 */
        const val MAX_READ_COUNT = 500

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
    suspend fun fetchQueueIds(connectionId: String, topic: String): List<Int> {
        val ctx = connectedContext(connectionId)
        val adminClient = ctx.adminClient
            ?: throw IllegalStateException("连接未建立或已断开，无法读写消息")
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
    suspend fun readMessages(
        connectionId: String,
        topic: String,
        queueId: Int,
        offset: Long,
        count: Int,
    ): RocketMqMessagePage {
        val config = connectedContext(connectionId).config
        val capped = count.coerceIn(1, MAX_READ_COUNT)
        return withContext(Dispatchers.IO) {
            RocketMqTlsStart(config).use { tls ->
                val consumer =
                    DefaultMQPullConsumer(BROWSE_GROUP_PREFIX + UUID.randomUUID(), tls.rpcHook).apply {
                        setNamesrvAddr(config.namesrvAddr.trim())
                        setUseTLS(config.tlsEnabled)
                    }
                tls.start { consumer.start() }
                try {
                    val mq = consumer.fetchSubscribeMessageQueues(topic)
                        .firstOrNull { it.queueId == queueId }
                        ?: throw IllegalStateException("队列 $queueId 不存在（topic 路由可能已变化，请重新「读取」）")
                    val pullResult = consumer.pull(mq, "*", offset, capped)
                    val messages = if (pullResult.pullStatus == PullStatus.FOUND) {
                        pullResult.msgFoundList.orEmpty().map { it.toMessage() }
                    } else {
                        emptyList()
                    }
                    // 队列尾判定：nextBeginOffset >= maxOffset → 无更多。maxOffset 查询
                    // 失败（-1）时不误判「已耗尽」，把游标交回调用方再试一轮。
                    val maxOffset = runCatching { consumer.maxOffset(mq) }.getOrDefault(-1L)
                    val next = pullResult.nextBeginOffset
                    val hasMore = pullResult.pullStatus == PullStatus.FOUND && (maxOffset < 0 || next < maxOffset)
                    RocketMqMessagePage(
                        messages = messages,
                        nextBeginOffset = if (hasMore) next else null,
                    )
                } finally {
                    runCatching { consumer.shutdown() }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 发送（写）

    /**
     * 发送一条消息（同步 send，阻塞等 broker 确认）。每次临时建 producer
     * （group 随机生成，用完 shutdown）—— 对标 KafkaMessageService 的独立句柄模式。
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
        val config = connectedContext(connectionId).config
        val timeoutMs = config.connectionTimeout * 1_000L
        return withContext(Dispatchers.IO) {
            RocketMqTlsStart(config).use { tls ->
                val producer =
                    DefaultMQProducer(SEND_GROUP_PREFIX + UUID.randomUUID(), tls.rpcHook).apply {
                        setNamesrvAddr(config.namesrvAddr.trim())
                        setUseTLS(config.tlsEnabled)
                    }
                tls.start { producer.start() }
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
                    RocketMqSendResult.failure(sendErrorMessage(error, timeoutMs))
                } finally {
                    runCatching { producer.shutdown() }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 内部零件

    /**
     * 消息会话的上下文：只要管理客户端还在（即这条连接真的连上过）就允许，不拿
     * `status` 当否决票 —— 与扫描 / 请求入口同一口径。否则一次抖动误标的 `Failed`
     * 会把消息浏览 / 发送也一并堵死，而底层连接其实还能用。
     */
    private fun connectedContext(connectionId: String): RocketMqConnectionContext =
        RocketMqModule.connectionOrNull(connectionId)
            ?.takeIf { it.adminClient != null }
            ?: throw IllegalStateException(
                RocketMqModule.connectionOrNull(connectionId)?.let { connectionRefusalText(it) }
                    ?: "连接未登记：该连接已被删除或模块未初始化",
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
            valuePreview = previewOf(bodyBytes),
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

    /** UTF-8 容错解码（非法字节出替换符）+ 截断到 [MESSAGE_PREVIEW_BYTES] 字节。 */
    private fun previewOf(bytes: ByteArray): String {
        val end = minOf(bytes.size, MESSAGE_PREVIEW_BYTES)
        return String(bytes, 0, end, Charsets.UTF_8)
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
    private fun sendErrorMessage(error: Throwable, timeoutMs: Long): String = when (error) {
        is org.apache.rocketmq.client.exception.MQClientException ->
            "发送失败：" + (error.errorMessage ?: error.message ?: error::class.simpleName ?: "unknown") +
                "（若提示找不到路由，检查 topic 是否存在 / NameServer 是否可达）"
        is org.apache.rocketmq.client.exception.MQBrokerException ->
            "Broker 拒绝（code " + error.responseCode + "）：" +
                (error.errorMessage ?: error.message ?: "unknown")
        is RemotingTimeoutException ->
            "发送超时（" + timeoutMs / 1000 + "s）：broker 未在时限内确认"
        is RemotingException ->
            "发送失败（网络异常）：" + (error.message ?: error::class.simpleName ?: "unknown")
        else -> error.message ?: error::class.simpleName ?: "unknown"
    }
}
