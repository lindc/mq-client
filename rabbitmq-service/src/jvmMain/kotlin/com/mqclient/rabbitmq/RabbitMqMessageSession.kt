package com.mqclient.rabbitmq

import com.mqclient.message.interpreter.isProbablyBinary
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import java.io.IOException
import java.net.ConnectException
import java.util.Date
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 一条拉取到的消息（basicGet 结果投影）：[deliveryTag] 是 ack（彻底消费）与
 * nack（重新入队）的唯一凭据，[body] 是原始字节（交解释器 SPI 渲染）。
 */
data class RabbitMqFetchedMessage(
    val deliveryTag: Long,
    val body: ByteArray,
    val size: Long,
    /** 疑似二进制（message-interpreter-api 判定）：列表与详情据此提供 hex 预览。 */
    val probablyBinary: Boolean,
    val messageId: String?,
    val timestamp: Date?,
    /** deliveryMode == 2（持久化）；1/缺省 = 瞬态。 */
    val persistent: Boolean,
    val contentType: String?,
    val contentEncoding: String?,
    val priority: Int?,
    val expiration: String?,
    val correlationId: String?,
    val replyTo: String?,
    /** 自定义 headers（值统一 toString —— LongString 等原生类型的可读投影）。 */
    val headers: Map<String, String>,
)

/**
 * RabbitMQ 队列消息会话（AMQP 0.9.1，basicGet 拉取模式）：队列消息对话框的
 * 底层句柄，**有状态、由对话框持有、随对话框关闭**。
 *
 * 只读浏览机制：basicGet(autoAck = false) 把消息置于 unacked —— 消息仍在队列
 * （对其他消费者不可见），确认凭据在本会话手上：
 *  - [ack]：用户点删除 —— 彻底消费掉；
 *  - [requeueAll]：用户点「重新拉取」—— 全部 basicNack(requeue) 放回队列；
 *  - [close]：关 channel/connection —— 未 ack 的消息由 broker 自动重入队
 *    （RabbitMQ 原生机制），close 前仍显式 requeue 一遍，双保险。
 *
 * 线程纪律：channel 非线程安全，所有操作经 [lock] 串行、跑在 Dispatchers.IO。
 * 拉取上限由 UI 控制（≤ 100），本类不重复设限。
 */
class RabbitMqMessageSession private constructor(
    private val queueName: String,
    private val connection: Connection,
    private val channel: Channel,
) {
    private val lock = Mutex()

    /** 本会话手上未 ack 的 deliveryTag（按拉取顺序）。 */
    private val heldTags = mutableListOf<Long>()

    private var closed = false

    /** 拉取下一批（**不 requeue 已拉取的** —— 同一 channel 的 basicGet 天然继续
     *  往后发；已拉取的仍挂在手上未 ack）。返回空表 = 队列当前没有更多可见消息。 */
    suspend fun fetchNext(limit: Int): List<RabbitMqFetchedMessage> = lock.withLock {
        withContext(Dispatchers.IO) {
            checkOpen()
            val out = mutableListOf<RabbitMqFetchedMessage>()
            while (out.size < limit) {
                val get = channel.basicGet(queueName, false) ?: break
                val tag = get.envelope.deliveryTag
                heldTags += tag
                val body = get.body ?: ByteArray(0)
                val props = get.props
                out += RabbitMqFetchedMessage(
                    deliveryTag = tag,
                    body = body,
                    size = body.size.toLong(),
                    probablyBinary = isProbablyBinary(body, 1208),
                    messageId = props.messageId,
                    timestamp = props.timestamp,
                    persistent = props.deliveryMode == 2,
                    contentType = props.contentType,
                    contentEncoding = props.contentEncoding,
                    priority = props.priority,
                    expiration = props.expiration,
                    correlationId = props.correlationId,
                    replyTo = props.replyTo,
                    headers = props.headers?.mapValues { (_, v) -> v?.toString().orEmpty() } ?: emptyMap(),
                )
            }
            out
        }
    }

    /** 当前手上未 ack 的条数（对话框底部「M 条待处理」用）。 */
    suspend fun heldCount(): Int = lock.withLock { heldTags.size }

    /** 彻底消费一条（用户点删除）：basicAck 从队列移除。 */
    suspend fun ack(deliveryTag: Long) = lock.withLock {
        withContext(Dispatchers.IO) {
            checkOpen()
            channel.basicAck(deliveryTag, false)
            heldTags.remove(deliveryTag)
        }
    }

    /** 全部重新入队（「重新拉取」用）：basicNack(requeue = true) 放回队列。 */
    suspend fun requeueAll() = lock.withLock {
        withContext(Dispatchers.IO) {
            checkOpen()
            requeueHeldLocked()
        }
    }

    /**
     * 通用发布（消息重发 / 搬移用）：任意 exchange + routing key；[props] 传
     * [buildProps] 的重建属性可保留原消息的 messageId / headers 等元数据，null = 空
     * 属性。
     *
     * 消息发布**不进写操作审计**（mq_command 表语义是管理操作，且高频批量会膨胀
     * 审计表 —— 与 IBM MQ 的 MqscExecutor 咽喉点口径一致）。
     */
    suspend fun publish(exchange: String, routingKey: String, body: ByteArray, props: AMQP.BasicProperties?) =
        lock.withLock {
            withContext(Dispatchers.IO) {
                checkOpen()
                channel.basicPublish(exchange, routingKey, props ?: AMQP.BasicProperties.Builder().build(), body)
            }
        }

    /** 直发当前队列：经默认交换机（""，routing key = 队列名），自拼 headers 的重载。 */
    suspend fun publishDirect(body: ByteArray, headers: Map<String, String>) {
        val props = AMQP.BasicProperties.Builder().headers(headers).build()
        publish("", queueName, body, props)
    }

    /**
     * 转发（重发）消息：把已拉取的消息经任意 exchange + routing key 重新发布，
     * 属性经 [buildProps] 保留原元数据。搬移语义 = 本方法 + [ack]（由调用方组合）。
     */
    suspend fun forward(message: RabbitMqFetchedMessage, exchange: String, routingKey: String) =
        publish(exchange, routingKey, message.body, buildProps(message))

    /** 从已拉取消息重建 AMQP 属性：转发 / 重发时保留原消息的元数据（body 除外）。 */
    fun buildProps(message: RabbitMqFetchedMessage): AMQP.BasicProperties =
        AMQP.BasicProperties.Builder()
            .messageId(message.messageId)
            .timestamp(message.timestamp)
            .deliveryMode(if (message.persistent) 2 else 1)
            .contentType(message.contentType)
            .contentEncoding(message.contentEncoding)
            .priority(message.priority)
            .expiration(message.expiration)
            .correlationId(message.correlationId)
            .replyTo(message.replyTo)
            .headers(message.headers)
            .build()

    /** 优雅关闭：显式 requeue 全部未 ack（broker 本也会自动重入队，双保险）→ 关 channel → 关 connection。 */
    suspend fun close() = lock.withLock {
        if (closed) return@withLock
        withContext(Dispatchers.IO) {
            runCatching { requeueHeldLocked() }
            runCatching { channel.close() }
            runCatching { connection.close() }
            closed = true
        }
    }

    private fun checkOpen() {
        if (closed) throw IllegalStateException("消息会话已关闭")
    }

    /** 已持锁调用。单条 nack 失败不中断（channel 断开时 broker 会整体重入队）。 */
    private fun requeueHeldLocked() {
        for (tag in heldTags) {
            runCatching { channel.basicNack(tag, false, true) }
        }
        heldTags.clear()
    }

    companion object {
        /**
         * 建连 + passive 校验队列存在：AMQP 端口与 vhost 一次验完。连接类错误转
         * 人类可读文案（与 RabbitMqAdminClient 同一口径）。
         */
        suspend fun open(config: RabbitMqConfig, queueName: String): RabbitMqMessageSession =
            withContext(Dispatchers.IO) {
                val factory = ConnectionFactory().apply {
                    host = config.host.trim()
                    port = config.amqpPort
                    username = config.username
                    password = config.password
                    virtualHost = config.vhost
                    connectionTimeout = config.connectionTimeout * 1_000
                    // 会话生命周期由对话框显式管理，不走自动恢复（避免 close 语义被自动重连搅乱）。
                    isAutomaticRecoveryEnabled = false
                    if (config.useTls) useSslProtocol()
                }
                var connection: Connection? = null
                var channel: Channel? = null
                try {
                    connection = factory.newConnection()
                    channel = connection.createChannel()
                    // passive 声明：队列不存在立刻 404，而不是等第一次 basicGet。
                    channel.queueDeclarePassive(queueName)
                    RabbitMqMessageSession(queueName, connection, channel)
                } catch (e: Throwable) {
                    runCatching { channel?.close() }
                    runCatching { connection?.close() }
                    throw mapOpenError(e, config)
                }
            }

        /** 建连错误 → 人类可读文案（与 AdminClient 的错误口径一致）。 */
        private fun mapOpenError(e: Throwable, config: RabbitMqConfig): Exception {
            val text = (e.message ?: "") + (e.cause?.message?.let { " " + it } ?: "")
            return when {
                e is ConnectException -> IllegalStateException(
                    "无法连接 AMQP " + config.host + ":" + config.amqpPort + "：检查地址与端口（默认 5672）", e,
                )
                e is TimeoutException || e.cause is TimeoutException -> IllegalStateException(
                    "连接超时（" + config.connectionTimeout + "s）：AMQP " + config.host + ":" + config.amqpPort + " 无响应", e,
                )
                text.contains("ACCESS_REFUSED") || text.contains("authentication", ignoreCase = true) ->
                    IllegalStateException("认证失败：检查用户名 / 密码（AMQP 与管理 API 凭据相同）", e)
                text.contains("404") || text.contains("NOT_FOUND") ->
                    IllegalStateException("队列不存在（404）：检查队列名与 vhost", e)
                else -> IllegalStateException(e.message ?: e::class.simpleName ?: "unknown", e)
            }
        }
    }
}
