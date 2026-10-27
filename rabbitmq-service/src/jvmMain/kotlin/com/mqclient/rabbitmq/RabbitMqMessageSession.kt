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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 队列消息会话的**能力契约**（UI 只依赖这一层）。
 *
 * 抽接口而不是让 UI 直接持有 [RabbitMqMessageSession]：真实实现需要 AMQP 的
 * `Connection` / `Channel`，测试里造不出来；而对话框要验证的「重开前先关旧会话」
 * 只有在能注入替身、观察 `close()` 何时被调用时才成立。
 *
 * 刻意只含 UI 用得到的成员 —— `publish` / `buildProps`（签名带 AMQP 类型）留在实现类上，
 * 不把 amqp-client 的类型泄到 UI 模块。
 */
interface RabbitMqQueueSession {

    /** 拉取下一批（同一 channel 上继续往后取，不 requeue 已拉取的）。 */
    suspend fun fetchNext(limit: Int): List<RabbitMqFetchedMessage>

    /** 当前手上未 ack 的条数。 */
    suspend fun heldCount(): Int

    /** 彻底消费一条（basicAck）。 */
    suspend fun ack(deliveryTag: Long)

    /** 全部重新入队（basicNack requeue），供「重新拉取」用。 */
    suspend fun requeueAll()

    /** 经默认交换机直发当前队列。 */
    suspend fun publishDirect(body: ByteArray, headers: Map<String, String>)

    /** 转发一条已拉取的消息到任意 exchange + routing key（元数据保留）。 */
    suspend fun forward(message: RabbitMqFetchedMessage, exchange: String, routingKey: String)

    /** 关闭会话：先显式 requeue 未 ack，再关 channel / connection。重复调用安全。 */
    suspend fun close()
}

/**
 * 打开队列消息会话的工厂端口（可注入）。
 *
 * 生产实现 [Default] 走模块的真实 AMQP 链路；测试注入替身即可观察「先关旧的、再开新的」
 * 的调用次序，不需要真的连 broker。
 */
fun interface RabbitMqSessionOpener {

    /**
     * 为 [connectionId] 上的 [queueName] 开一个只读会话（autoAck = false）。
     * 连接未建立时抛 [IllegalStateException]，由调用方转「加载失败」。
     */
    suspend fun open(connectionId: String, queueName: String): RabbitMqQueueSession

    companion object {
        /** 默认实现：交给模块（用该连接已建立的真实 AMQP 配置）。 */
        val Default: RabbitMqSessionOpener = RabbitMqSessionOpener { connectionId, queueName ->
            RabbitMqModule.openMessageSession(connectionId, queueName)
        }
    }
}

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
 * 线程纪律：channel 非线程安全，所有 channel 调用经 [channelOps] 串行、跑在
 * Dispatchers.IO；会话状态（[heldTags] / [closed]）另用 [stateLock] 保护，
 * 从不在它们上面等 IO。
 * 拉取上限由 UI 控制（≤ 100），本类不重复设限。
 */
class RabbitMqMessageSession private constructor(
    private val queueName: String,
    private val connection: Connection,
    private val channel: Channel,
) : RabbitMqQueueSession {
    /**
     * 串行化 channel 访问的互斥量（AMQP channel 非线程安全）。
     *
     * **只包住 channel 调用本身**，每一处都写成
     * `withContext(IO) { channelOps.withLock { ... } }` —— IO 切线程在**外**，锁在**内**。
     * 原先反着写（`lock.withLock { withContext(IO) { ... } }`），`basicGet` 卡在阻塞
     * socket 上时这把锁永不释放，`close()` 就排在后面等死，Connection / Channel 一个
     * 都关不掉 —— 每开一次对话框漏一条连接。
     */
    private val channelOps = Mutex()

    /**
     * 会话内部状态（[heldTags] / [closed]）的监视器。
     *
     * 用 JVM 的 `synchronized` 而不是 [channelOps]：临界区只有「加一个 tag」「读 size」
     * 这种微秒级操作，从不在里面等 IO，因此**不可能**被卡住的网络调用拖住。
     */
    private val stateLock = Any()

    /** 本会话手上未 ack 的 deliveryTag（按拉取顺序）。受 [stateLock] 保护。 */
    private val heldTags = mutableListOf<Long>()

    @Volatile
    private var closed = false

    /** 保证 [close] 的收尾只跑一次（幂等），且不依赖任何 suspended 锁。 */
    private val closeStarted = AtomicBoolean(false)

    private inline fun <T> withState(block: () -> T): T = synchronized(stateLock, block)

    /** 拉取下一批（**不 requeue 已拉取的** —— 同一 channel 的 basicGet 天然继续
     *  往后发；已拉取的仍挂在手上未 ack）。返回空表 = 队列当前没有更多可见消息。 */
    override suspend fun fetchNext(limit: Int): List<RabbitMqFetchedMessage> =
        withContext(Dispatchers.IO) {
            channelOps.withLock {
                checkOpen()
                val out = mutableListOf<RabbitMqFetchedMessage>()
                while (out.size < limit) {
                    val get = channel.basicGet(queueName, false) ?: break
                    val tag = get.envelope.deliveryTag
                    withState { heldTags += tag }
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

    /**
     * 当前手上未 ack 的条数（对话框底部「M 条待处理」用）。
     *
     * 不取 [channelOps]：只读状态监视器。这样即使有协程正卡在 `basicGet` 里，
     * 「刷新计数」也不会跟着卡死 —— 旧实现 `lock.withLock { heldTags.size }` 会。
     */
    override suspend fun heldCount(): Int = withState { heldTags.size }

    /** 彻底消费一条（用户点删除）：basicAck 从队列移除。 */
    override suspend fun ack(deliveryTag: Long) =
        withContext(Dispatchers.IO) {
            channelOps.withLock {
                checkOpen()
                channel.basicAck(deliveryTag, false)
                withState { heldTags.remove(deliveryTag) }
                // 显式 Unit：heldTags.remove 返回 Boolean，不写的话整个 lambda 会推出 Boolean。
                Unit
            }
        }

    /** 全部重新入队（「重新拉取」用）：basicNack(requeue = true) 放回队列。 */
    override suspend fun requeueAll() =
        withContext(Dispatchers.IO) {
            channelOps.withLock {
                checkOpen()
                requeueHeldOnChannel()
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
        withContext(Dispatchers.IO) {
            channelOps.withLock {
                checkOpen()
                channel.basicPublish(exchange, routingKey, props ?: AMQP.BasicProperties.Builder().build(), body)
            }
        }

    /** 直发当前队列：经默认交换机（""，routing key = 队列名），自拼 headers 的重载。 */
    override suspend fun publishDirect(body: ByteArray, headers: Map<String, String>) {
        val props = AMQP.BasicProperties.Builder().headers(headers).build()
        publish("", queueName, body, props)
    }

    /**
     * 转发（重发）消息：把已拉取的消息经任意 exchange + routing key 重新发布，
     * 属性经 [buildProps] 保留原元数据。搬移语义 = 本方法 + [ack]（由调用方组合）。
     */
    override suspend fun forward(message: RabbitMqFetchedMessage, exchange: String, routingKey: String) =
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

    /**
     * 优雅关闭：显式 requeue 全部未 ack（broker 本也会自动重入队，双保险）→
     * 关 channel → 关 connection。
     *
     * **绝不在 [channelOps] 上等待**。这是本轮最关键的一处：如果另一个协程正卡在
     * `channel.basicGet` 的阻塞 socket 上（协程层的 `withTimeout` 只能让**等待方**放弃，
     * 杀不掉那个调用），[channelOps] 会一直被它持有。旧实现在这里 `withLock` 排同一把
     * 锁，于是 `close()` 永久挂起，Connection / Channel 一个都关不掉 ——
     * 每开一次对话框漏一条连接，这就是「第一次快、第二次慢、第三次更慢」。
     *
     * 现在：
     *  1. 先用 [AtomicBoolean] 抢占（幂等），不碰任何 suspended 锁；
     *  2. 立刻置 [closed]，后续操作快速失败而不是排队；
     *  3. requeue 只在**能立刻拿到** [channelOps] 时做（`tryLock`），拿不到就跳过 ——
     *     关闭的语义优先于 requeue，而 broker 在连接断开时本来也会重入队；
     *  4. 无条件关 channel / connection —— 这也是把卡在 `basicGet` 里的线程**唯一**
     *     能放出来的手段。
     */
    override suspend fun close(): Unit =
        withContext(Dispatchers.IO) {
            if (!closeStarted.compareAndSet(false, true)) return@withContext
            closed = true
            // best-effort requeue：只在锁空闲时做，绝不为它等待。
            if (channelOps.tryLock()) {
                try {
                    runCatching { requeueHeldOnChannel() }
                } finally {
                    channelOps.unlock()
                }
            }
            runCatching { channel.close() }
            runCatching { connection.close() }
        }

    private fun checkOpen() {
        if (closed) throw IllegalStateException("消息会话已关闭")
    }

    /** 已持 [channelOps] 调用。先把 tag 摘出来再 nack，避免 nack 期间占着状态监视器。 */
    private fun requeueHeldOnChannel() {
        val tags = withState { heldTags.toList().also { heldTags.clear() } }
        for (tag in tags) {
            // 单条 nack 失败不中断（channel 断开时 broker 会整体重入队）。
            runCatching { channel.basicNack(tag, false, true) }
        }
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
                    if (config.useTls) {
                        rabbitMqSslContext(config.tlsTruststorePath, config.tlsTruststorePassword)
                            ?.let { useSslProtocol(it) }
                            ?: run { useSslProtocol() }
                    }
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
