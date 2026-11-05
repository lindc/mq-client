package com.mqclient.rabbitmq

import com.mqclient.core.ServiceException
import com.mqclient.core.log.Log
import com.mqclient.message.interpreter.isProbablyBinary
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.ChannelContinuationTimeoutException
import com.rabbitmq.client.Connection
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 本文件的日志句柄，与模块同 tag（`rabbitmq`）。
 *
 * 为什么要加：这个类以前**一行日志都没有**。真机出问题时（拉取卡住 / 超时 / 连接
 * 无声地不再应答），日志里只能看到管理 API 的扫描与最后的 shutdown，会话层是黑盒 ——
 * 排查只能靠猜。现在最少能回答四个问题：会话何时开的、拉取拉到了几条 / 卡了多久、
 * 哪条 channel 被报废与原因、close 走了哪些步。
 */
private val sessionLog = Log.withTag(MODULE_ID)

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

    /** 最近一次 passive 声明看到的队列消费者数（「影子消费者」排查证据；未知为 null）。 */
    fun lastKnownConsumerCount(): Int?

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
 *  - [close]：关 channel —— 未 ack 的消息由 broker 自动重入队（RabbitMQ 原生机制），
 *    close 前仍显式 requeue 一遍，双保险。
 *
 * ## 与上一版的三个本质差异
 *
 *  1. **channel 是一次性的**：`basicGet` 一旦抛错（含 5s RPC 超时），当前 channel
 *     立即 [discardChannel] 报废，绝不复用。坏掉的 channel 会污染后续所有调用，
 *     复用它是「越用越慢」的推手之一。
 *  2. **不存在「关不掉的锁」**：持锁期间唯一的阻塞调用有
 *     [AMQP_CHANNEL_RPC_TIMEOUT_MS] 上限（见 [rabbitMqConnectionFactory]），超时后
 *     异常正常穿出 `withLock`，锁由协程原语在 `finally` 里归还。上一版靠协程层
 *     `withTimeout` 放弃等待 —— 等待方走了，阻塞调用还在，锁就永远回不来。
 *  3. **close 不排队**：`tryLock` + `abort()`（不等 close-ok），全程不等待任何可能
 *     被占用的资源。
 *
 * ## 连接所有权
 *
 * [ownsConnection] = false（生产路径）：channel 开在 ConnectionManager 的**共享**
 * `Connection` 上，close 只关 channel —— 关掉共享连接会把别的对话框一起断掉。
 * [ownsConnection] = true（测试 / 独占路径）：连接由本会话创建，close 一并关闭，
 * 且用 `close(AMQP_SHUTDOWN_TIMEOUT_MS)` 给握手上限。
 *
 * 线程纪律：channel 非线程安全，所有 channel 调用经 [channelOps] 串行、跑在
 * Dispatchers.IO；会话状态（[heldTags] / [channel] 引用 / 标志位）另用
 * [stateLock] 保护 —— 临界区全是微秒级内存操作，从不在它们上面等 IO。
 * 拉取上限由 UI 控制（≤ 100），本类不重复设限。
 *
 * ## ⚠️ 未 Ack 消息的生命周期与**共享长连接**绑定
 *
 * `basicGet(autoAck = false)` 拉到的消息处于 unacked，凭据（`deliveryTag`）是
 * **channel 级**的。共享连接之后，这些 unacked 消息的生命周期不再只取决于本会话 ——
 * 只要 channel 还在，消息就一直卡在 unacked（对其他消费者不可见）。
 *
 * 正常路径下 `close()` 会把它们 requeue 回去（并关闭 channel），而 `close()` 由
 * 对话框的 `onDispose` → `RabbitMqModule.closeMessageSessionAsync` 触发。但那依赖
 * **组合真的走到 dispose**。极端情况下（进程被强杀、Compose 异常导致组合未正常
 * 拆束、`onDispose` 里的 close 协程被取消）channel 可能没关，这些消息就会一直卡在
 * unacked，直到连接本身断掉。
 *
 * **兜底手段：主动断开该 MQ 连接**（侧边栏断开 / 删除连接 / 退出程序）。
 * 连接一断，broker 会把该连接上所有未 ack 消息自动重入队 —— 这是 RabbitMQ 的
 * 原生语义，不依赖客户端做任何事。心跳（[AMQP_REQUESTED_HEARTBEAT_S]）保证死连接
 * 不会无限期拖着这些消息。
 */
class RabbitMqMessageSession private constructor(
    private val queueName: String,
    private val connection: Connection,
    private val ownsConnection: Boolean,
    initialChannel: Channel,
    /** createChannel 失败（共享连接已死 / 半开）时通知 ConnectionManager 作废重建。 */
    private val onConnectionBroken: () -> Unit = {},
) : RabbitMqQueueSession {

    /**
     * 会话状态的监视器：[heldTags] / [channel] 引用 / 标志位。
     *
     * 用 JVM 的 `synchronized` 而不是 [channelOps]：临界区只有「加一个 tag」「换个
     * 引用」这种微秒级操作，从不在里面等 IO，因此**不可能**被卡住的网络调用拖住。
     */
    private val stateLock = Any()

    /** 本会话手上未 ack 的 deliveryTag（按拉取顺序）。受 [stateLock] 保护。 */
    private val heldTags = mutableListOf<Long>()

    /** 当前 channel；[discardChannel] 会把它置 null，下次调用自动重建。 */
    @Volatile
    private var channel: Channel? = initialChannel

    /** 最近一次 passive 声明看到的队列消费者数（「影子消费者」排查证据）。 */
    @Volatile
    private var declaredConsumerCount: Int? = null

    @Volatile
    private var closed = false

    /** channel 访问串行化（AMQP channel 非线程安全）。 */
    private val channelOps = Mutex()

    /** [close] 的收尾只跑一次；不依赖任何 suspended 锁。 */
    private val closeStarted = AtomicBoolean(false)

    /**
     * 单飞守卫：同一会话同时只允许一批 `basicGet` 在路上。
     *
     * 为什么用 CAS 而不是只靠 UI 的 `fetching` 标志：UI 标志在协程体里才置位，
     * 连点两下的第二下可能在它置位前就进来了 —— 两批 `basicGet` 一起排在
     * [channelOps] 后面，就是「排队雪崩」的起点。这里直接拒绝第二次。
     */
    private val fetchInFlight = AtomicBoolean(false)

    private inline fun <T> withState(block: () -> T): T = synchronized(stateLock, block)

    /* ------------------------------ 拉取 ------------------------------ */

    override suspend fun fetchNext(limit: Int): List<RabbitMqFetchedMessage> {
        checkOpen()
        if (!fetchInFlight.compareAndSet(false, true)) {
            throw ServiceException(
                "rabbitmq_err_fetch_in_progress",
                fallbackMessage = "The previous fetch is still in progress — please wait",
            )
        }
        val startedAt = System.currentTimeMillis()
        sessionLog.info("[MQ-Perf][RabbitMQ] Service 开始处理拉取请求: queue=$queueName, limit=$limit")
        return try {
            withContext(Dispatchers.IO) {
                channelOps.withLock { drain(limit) }
            }.also { batch ->
                // io/parse 细分由 [drain] 打点；这里汇总整次 fetch 的端到端耗时。
                sessionLog.info(
                    "[MQ-Perf][RabbitMQ] fetch done: queue=$queueName, messages=${batch.size}, " +
                        "totalMs=${System.currentTimeMillis() - startedAt}ms",
                )
            }
        } catch (e: RabbitMqOperationTimeoutException) {
            // 超时是「会话可能已坏」的第一信号，必须留下记录。RabbitMQ 无 UI 侧
            // withTimeout（见对话框 KDoc），这是底层 channelRpcTimeout=5s 到点抛出。
            sessionLog.warn(
                "[MQ-Perf][RabbitMQ] 拉取超时, 耗时达到=${System.currentTimeMillis() - startedAt}ms, " +
                    "已被底层 RPC 超时强制取消: queue=$queueName, 异常类型=${e::class.simpleName}, 详情=${e.message}",
            )
            throw e
        } finally {
            fetchInFlight.set(false)
        }
    }

    /**
     * 已持 [channelOps]、已在 IO 上。任一 `basicGet` 抛错 → 当前 channel 立刻报废。
     *
     * 不做「失败重试」：超时说明这条 channel 的 RPC 队列已经乱了，重试只会再等 5s。
     * 报废后下次点击由 [ensureChannel] 从共享连接上开一条新的 —— 自愈但可控。
     */
    private fun drain(limit: Int): List<RabbitMqFetchedMessage> {
        checkOpen()
        val ch = ensureChannel()
        val out = ArrayList<RabbitMqFetchedMessage>(limit)
        var ioMs = 0L
        var parseMs = 0L
        try {
            while (out.size < limit) {
                // channelRpcTimeout 到这里生效：broker 不应答时 5s 抛
                // ChannelContinuationTimeoutException，而不是挂 10 分钟。
                // 网络 IO：basicGet 本体。
                var t = System.currentTimeMillis()
                val get = channelCall("rabbitmq_err_timeout_fetch", "Fetch") { ch.basicGet(queueName, false) } ?: break
                ioMs += System.currentTimeMillis() - t
                val tag = get.envelope.deliveryTag
                withState { heldTags += tag }
                val body = get.body ?: ByteArray(0)
                val props = get.props
                // 数据解析：GetResult -> RabbitMqFetchedMessage（DTO -> Domain）。
                t = System.currentTimeMillis()
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
                parseMs += System.currentTimeMillis() - t
            }
            sessionLog.info(
                "[MQ-Perf][RabbitMQ] drain done: queue=$queueName, ioMs=$ioMs, parseMs=$parseMs, messages=${out.size}",
            )
            return out
        } catch (e: Exception) {
            sessionLog.error(
                "[MQ-Perf][RabbitMQ] 拉取失败, 异常类型=${e::class.simpleName}, " +
                    "详情=${e.message ?: "unknown"}, 已耗时=${ioMs + parseMs}ms",
            )
            // channel 级协议错误：报废这条 channel（共享 Connection 不受影响，
            // 下次点击会从它上面新建 channel 继续工作）。
            discardChannel(ch, e)
            // Stream 队列不支持 basic.get（540 NOT_IMPLEMENTED）：翻译成业务提示，
            // 而不是把一坨底层 AMQP 报错糊到界面上。
            if (isStreamUnsupported(e)) {
                throw ServiceException(
                    "rabbitmq_err_stream_browse_unsupported",
                    fallbackMessage = "This is a Stream queue; single-message preview (basic.get) is not supported",
                )
            }
            throw e
        }
    }

    /** Stream 队列的 basic.get 被 broker 以 540 NOT_IMPLEMENTED 拒绝（含 cause 链）。 */
    private fun isStreamUnsupported(error: Throwable): Boolean {
        val text = buildString {
            var current: Throwable? = error
            while (current != null) {
                append(current.message.orEmpty())
                append(' ')
                current = current.cause
            }
        }
        return text.contains("540") ||
            text.contains("NOT_IMPLEMENTED", ignoreCase = true) ||
            text.contains("basic.get not supported", ignoreCase = true)
    }

    /* ------------------------------ 计数 ------------------------------ */

    /**
     * 当前手上未 ack 的条数（对话框底部「M 条待处理」用）。
     *
     * 不碰 channel、不碰 [channelOps]：纯内存读，永远立即返回。
     * 旧实现 `lock.withLock { heldTags.size }` 会让「刷新待处理计数」排在卡住的
     * `basicGet` 后面一起死 —— 这正是「正文出来了、界面还在转圈」的形态之一。
     */
    override suspend fun heldCount(): Int = withState { heldTags.size }

    override fun lastKnownConsumerCount(): Int? = declaredConsumerCount

    /* ------------------------------ 写操作 ------------------------------ */

    /** 彻底消费一条（用户点删除）：basicAck 从队列移除。 */
    override suspend fun ack(deliveryTag: Long) =
        withContext(Dispatchers.IO) {
            channelOps.withLock {
                checkOpen()
                val ch = ensureChannel()
                try {
                    channelCall("rabbitmq_err_timeout_ack", "Acknowledge") { ch.basicAck(deliveryTag, false) }
                    withState { heldTags.remove(deliveryTag) }
                    // 显式 Unit：heldTags.remove 返回 Boolean，不写的话 lambda 会推出它。
                    Unit
                } catch (e: Exception) {
                    discardChannel(ch, e)
                    throw e
                }
            }
        }

    /** 全部重新入队（「重新拉取」用）：basicNack(requeue = true) 放回队列。 */
    override suspend fun requeueAll() =
        withContext(Dispatchers.IO) {
            channelOps.withLock {
                checkOpen()
                val ch = ensureChannel()
                try {
                    requeueHeldOn(ch)
                } catch (e: Exception) {
                    discardChannel(ch, e)
                    throw e
                }
            }
        }

    /**
     * 通用发布（消息重发 / 搬移用）：任意 exchange + routing key；[props] 传
     * [buildProps] 的重建属性可保留原消息的 messageId / headers 等元数据，null = 空属性。
     *
     * 消息发布**不进写操作审计**（mq_command 表语义是管理操作，且高频批量会膨胀
     * 审计表 —— 与 IBM MQ 的 MqscExecutor 咽喉点口径一致）。
     */
    suspend fun publish(exchange: String, routingKey: String, body: ByteArray, props: AMQP.BasicProperties?) =
        withContext(Dispatchers.IO) {
            channelOps.withLock {
                checkOpen()
                // 每次发布都新建独立 Channel，try-finally 无条件释放（见
                // [withOperationChannel]）。发送失败只毁这一条 channel。
                withOperationChannel("Publish") { ch ->
                    // Default exchange 直发：routing key 就是队列名。队列不存在（被删 /
                    // 从未声明）时 basicPublish **不会报错** —— unroutable 消息被 broker
                    // 静默丢弃，UI 却显示「发送成功」（真机踩过：demo.chain.q1 已被删除，
                    // 批量发送 2 个文件提示成功但消息凭空消失）。发布前 passive 声明一次，
                    // 404 立刻抛出，让「队列不存在」成为显式错误。
                    if (exchange.isEmpty()) {
                        channelCall("rabbitmq_err_timeout_declare_queue", "Declare queue") {
                            ch.queueDeclarePassive(routingKey)
                        }
                    }
                    channelCall("rabbitmq_err_timeout_publish", "Publish") {
                        ch.basicPublish(
                            exchange,
                            routingKey,
                            props ?: AMQP.BasicProperties.Builder().build(),
                            body,
                        )
                    }
                }
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

    /* ------------------------------ 关闭 ------------------------------ */

    /**
     * 关闭会话。**全程不等待可能被占用的资源**，所以 `onDispose → close()` 不会挂 UI。
     *
     *  1. [AtomicBoolean] 抢占做幂等，不碰任何 suspended 锁；
     *  2. requeue 只在 `tryLock` **立刻拿到**锁时做（best-effort）；拿不到就跳过 ——
     *     关闭的语义优先于 requeue，而且 broker 在连接断开时本来也会重入队；
     *  3. channel 交给 [abortInBackground] **后台** 关闭（不等 close-ok；而且它的写帧
     *     没有超时，绝不能占着调用方线程或 `channelOps`）。这里给一个有界等待
     *     [ABORT_WAIT_ON_CLOSE_MS]：正常路径下「关闭返回 = channel 已关」，共享连接
     *     不会瞬时堆一串 channel 号；卡住时超时就返回，后台线程继续。
     *  4. 独占连接才关，且用 `close(AMQP_SHUTDOWN_TIMEOUT_MS)` 给握手上限；
     *     共享连接的所有权在 ConnectionManager，这里不能动它。
     *
     * 最坏情况：abort 卡在 socket 写上时，`close()` 最多停 [ABORT_WAIT_ON_CLOSE_MS] 就返回。
     * 未 ack 的消息不受影响 —— [requeueHeldOn] 已经显式 nack 回去，何况 broker 在 channel
     * 真正关闭时自己也会重入队。
     *
     * ## 为什么整段跑在 [NonCancellable] 上
     *
     * **清理不能被取消。** 调用方（对话框的 `onDispose`、模块的异步兜底、daemon 线程）
     * 都可能处在一个正在被取消的协程里 —— 普通 `withContext` 会在**入口**就抛
     * `CancellationException`，下面四步一步都不会执行：channel 不 abort、未 ack 的消息
     * 不回队。取消是「调用方不要等结果了」，不是「别关了」。
     *
     * 与 kafka / ibm-mq 的 `closeResources` 同一口径（那两个也把关闭包在
     * `NonCancellable` 里）。代价可控：本方法只做 best-effort requeue + abort，
     * 没有任何 `await` 式的无限等待，底层 RPC 一律受 `channelRpcTimeout`（5s）约束。
     */
    override suspend fun close(): Unit =
        withContext(NonCancellable + Dispatchers.IO) {
            if (!closeStarted.compareAndSet(false, true)) return@withContext
            closed = true

            val ch = synchronized(stateLock) { channel.also { channel = null } }
            // 先取计数再 requeue —— 不然日志里永远是 0，等于没记。
            val heldBefore = withState { heldTags.size }
            if (ch != null && channelOps.tryLock()) {
                try {
                    runCatching { requeueHeldOn(ch) }
                } finally {
                    channelOps.unlock()
                }
            }
            ch?.let { abortInBackground(it, "session closed", waitMs = ABORT_WAIT_ON_CLOSE_MS) }
            if (ownsConnection) {
                runCatching { connection.close(AMQP_SHUTDOWN_TIMEOUT_MS) }
            }
            sessionLog.debug(
                "session closed: queue=$queueName channel=${ch?.let { runCatching { it.channelNumber }.getOrNull() } ?: "-"} " +
                    "ownedConnection=$ownsConnection heldBefore=$heldBefore",
            )
        }

    /* ------------------------------ 内部 ------------------------------ */

    /**
     * 把 `amqp-client` 的两种超时异常翻译成 [RabbitMqOperationTimeoutException]。
     *
     * 底层超时有两种形态，都来自 `channelRpcTimeout` 到点：
     *  - [ChannelContinuationTimeoutException]：RPC 继续等待超时（`basicGet` 走这条）；
     *  - `java.util.concurrent.TimeoutException`：`channel.close()` 一类的握手上限。
     *
     * 翻译而不是直接抛出：`amqp-client` 是 service 模块的 `implementation` 依赖，
     * UI 看不到它的类型。领域异常让 UI 只 catch 一个类型就能给出「操作超时」文案。
     *
     * **它只覆盖「等回复」**：写帧（`Frame.writeTo` → `SocketFrameHandler.writeFrame`）
     * 走的是裸 socket 写，没有任何超时 —— 所以任何 abort / 关闭都不能在持锁路径上同步
     * 做，见 [abortInBackground]。
     */
    /**
     * channel RPC 统一超时翻译：[errorKey] 是本操作的超时文案键（展示端渲染时点
     * 翻译），[op] 是英文兜底里的操作名 —— 两份信息都给，缺键也不丢语义。
     */
    private inline fun <T> channelCall(errorKey: String, op: String, block: () -> T): T =
        try {
            block()
        } catch (e: ChannelContinuationTimeoutException) {
            throw RabbitMqOperationTimeoutException(errorKey, "$op timed out: the broker did not respond in time", e)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw RabbitMqOperationTimeoutException(errorKey, "$op timed out: the broker did not respond in time", e)
        }

    private fun checkOpen() {
        if (closed) {
            throw ServiceException(
                "rabbitmq_err_session_closed",
                fallbackMessage = "The message session is closed",
            )
        }
    }

    /**
     * 无状态 AMQP 操作（publish / forward）的**一次性 Channel**：每次从共享
     * [Connection] 上 createChannel()，无论成功或异常都在 finally 里立刻释放。
     *
     * AMQP 黄金法则：Connection 长连共享、Channel 短命独占。publish 遇到任何
     * channel 级协议错误（404 / 540 / 超时）只会毁掉这一条新建的 channel，既不会
     * 连坐会话里持有 unacked 消息的浏览 channel，也不会污染共享 Connection。
     *
     * 释放统一走 [abortInBackground]：channel.close() 的写帧无超时，同步调用会把
     * 持锁的调用方永久钉住；abort 是同一语义但不等待 close-ok。
     */
    private inline fun <T> withOperationChannel(op: String, block: (Channel) -> T): T {
        val ch = channelCall("rabbitmq_err_timeout_open_channel", "Open channel") {
            connection.createChannel()
        }
        try {
            return block(ch)
        } finally {
            // Connection 已被 broker 断开时，channel 的 close/abort 可能抛异常；
            // 必须 runCatching 吞掉，绝不能掩盖真实的业务错误（540 / 超时 / 404）。
            runCatching { abortInBackground(ch, "$op: one-shot channel released") }
                .onFailure { sessionLog.warn("one-shot channel release failed (connection may be closed): ${it.message}") }
        }
    }

    /**
     * 已持 [channelOps]、已在 IO 上。channel 被报废过就重建一条。
     *
     * `createChannel` 与 `queueDeclarePassive` 都是 RPC，同样受 `channelRpcTimeout`
     * 约束 —— 不会在这里静默挂住。队列已被删除时会抛 404，由调用方转「加载失败」。
     */
    private fun ensureChannel(): Channel {
        checkOpen()
        channel?.let { return it }
        // createChannel 失败说明共享 Connection 已死或半开（broker 关过 channel 后
        // 客户端 isOpen 仍可能为 true）。强制作废重建，避免「一次协议错误之后所有
        // 请求都复用同一条死连接」的全瘫。
        val fresh = try {
            channelCall("rabbitmq_err_timeout_open_channel", "Open channel") { connection.createChannel() }
        } catch (e: Exception) {
            onConnectionBroken()
            throw e
        }
        try {
            // passive 声明：队列不存在立刻 404，而不是等第一次 basicGet 才发现。
            // 返回值里的 consumerCount 是「影子消费者」排查的直接证据（>0 = 有别的
            // 消费者挂在该队列上，消息发进去会被瞬间消费）。
            val declared = channelCall("rabbitmq_err_timeout_declare_queue", "Declare queue") {
                fresh.queueDeclarePassive(queueName)
            }
            declaredConsumerCount = declared?.consumerCount
        } catch (e: Exception) {
            // 半成品 channel 不能留在连接上（会占 channel 号）。**后台** abort：
            // 这里持着 channelOps，同步写 socket 会把整把锁一起卡死（见 abortInBackground）。
            abortInBackground(fresh, "declare failed")
            throw e
        }
        // **双检 + 就地回收**：`createChannel` 与 `queueDeclarePassive` 都是 RPC，
        // 这期间 `close()` 完全可能已经跑完 —— 它当时看到 `channel == null`，什么都没
        // abort。若这条迟到的 channel 照旧发布上去，就再没有人关它：共享连接上多一条
        // 死 channel，之后拉到的消息永久 unacked（对其他消费者不可见）。
        // 发布与 `closed` 读在同一个 stateLock 里，与 close() 的摘引用互斥：
        // 要么 close 看见它并 abort，要么这里看见 closed 并自己 abort。
        val published = withState {
            if (closed) {
                false
            } else {
                channel = fresh
                true
            }
        }
        if (!published) {
            // 同样必须在后台 abort：本方法只在 channelOps 临界区里被调用。
            abortInBackground(fresh, "session closed while opening channel")
            throw ServiceException(
                "rabbitmq_err_session_closed",
                fallbackMessage = "The message session is closed",
            )
        }
        sessionLog.debug("channel ready: queue=$queueName channel=${fresh.channelNumber}")
        return fresh
    }

    /**
     * 报废一条坏 channel：先摘引用（后续调用立刻拿不到它），再**后台** `abort()`。
     *
     * 为什么是 abort 而不是 close：`close()` 要等 close-ok，而这条 channel 刚刚
     * 证明它的 RPC 通道不会应答。abort 只发一帧，是唯一有机会不阻塞的关闭方式。
     *
     * 为什么必须异步：本方法的所有调用点都在 `channelOps` 临界区里（drain / ack /
     * requeueAll / publish 的 catch），而 abort 的写帧没有超时 —— 对端不读 socket 时
     * 它永久阻塞，同步调用就会把 `channelOps` 永久钉住，于是「超时一次、之后彻底不动」。
     * 挪到后台后，最坏只损失后台一条工人线程与一个 channel 号（心跳 / 重连会回收）。
     */
    private fun discardChannel(dead: Channel, cause: Throwable? = null) {
        synchronized(stateLock) { if (channel === dead) channel = null }
        val number = runCatching { dead.channelNumber }.getOrDefault(-1)
        sessionLog.warn(
            "channel discarded: queue=$queueName channel=$number " +
                "cause=${cause?.let { it::class.simpleName } ?: "unknown"} " +
                "detail=${cause?.message ?: "-"}",
        )
        abortInBackground(dead, "discarded after failure")
    }

    /** 已持 [channelOps] 调用。先把 tag 摘出来再 nack，避免 nack 期间占着状态监视器。 */
    private fun requeueHeldOn(ch: Channel) {
        val tags = withState { heldTags.toList().also { heldTags.clear() } }
        for (tag in tags) {
            // 单条 nack 失败不中断（channel 断开时 broker 会整体重入队）。
            runCatching { channelCall("rabbitmq_err_timeout_nack", "Requeue") { ch.basicNack(tag, false, true) } }
        }
    }

    companion object {
        /**
         * 独占连接版本：自己建连、自己关。**仅限测试 / 诊断**。
         *
         * 生产路径走 [onSharedConnection] —— 复用 ConnectionManager 的共享连接，
         * 每次打开对话框只 createChannel，不再 newConnection。
         *
         * 为什么标 `@Deprecated`：集中搜过，**没有任何生产代码**调用它（只有
         * rabbitmq-service 的 diag 真机测试在用，因为那些测试没有 ConnectionManager
         * 可以拿共享连接）。它很容易被误用 —— 谁在 UI 代码里调一次，就回到「每次
         * newConnection」的老路。标掉之后 IDE 会在调用点直接提示改用
         * [onSharedConnection]。
         */
        @Deprecated(
            message = "Use onSharedConnection to reuse the shared connection; " +
                "this overload opens a new connection per call (tests / diagnostics only)",
            level = DeprecationLevel.WARNING,
        )
        suspend fun open(config: RabbitMqConfig, queueName: String): RabbitMqMessageSession =
            withContext(Dispatchers.IO) {
                val factory = rabbitMqConnectionFactory(config)
                var connection: Connection? = null
                var channel: Channel? = null
                try {
                    connection = factory.newConnection()
                    channel = connection.createChannel()
                    // passive 声明：队列不存在立刻 404，而不是等第一次 basicGet。
                    channel.queueDeclarePassive(queueName)
                    RabbitMqMessageSession(queueName, connection, ownsConnection = true, channel)
                } catch (e: Throwable) {
                    // 半成品句柄就地回收。channel 交后台 abort（这里的写帧同样没超时）；
                    // 独占连接才关，且带握手上限。
                    abortInBackground(channel, "open failed")
                    runCatching { connection?.close(AMQP_SHUTDOWN_TIMEOUT_MS) }
                    throw mapAmqpOpenError(e, config)
                }
            }

        /**
         * 共享连接版本：channel 由调用方从 ConnectionManager 的共享 `Connection` 上开。
         *
         * 连接的所有权不在会话手上，所以 [close] 只关 channel —— 关掉共享连接会把
         * 同一连接上别的对话框一起断掉。
         */
        fun onSharedConnection(
            queueName: String,
            connection: Connection,
            channel: Channel,
            onConnectionBroken: () -> Unit = {},
        ) = RabbitMqMessageSession(queueName, connection, ownsConnection = false, channel, onConnectionBroken)
    }
}
