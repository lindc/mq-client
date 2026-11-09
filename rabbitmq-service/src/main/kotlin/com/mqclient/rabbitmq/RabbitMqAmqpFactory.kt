package com.mqclient.rabbitmq

import com.mqclient.core.LocalizedException
import com.mqclient.core.ServiceException
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/*
 * AMQP 客户端的三个超时（毫秒）。**这是本次重构的承重墙**，不能省。
 *
 * （本段是文件级说明，不是某个声明的 KDoc —— 每个常量各自带说明。）
 *
 * 为什么要显式设：amqp-client 5.22.0 的默认值对交互式界面完全不可接受 ——
 *
 *  - `channelRpcTimeout` 默认 **10 分钟**
 *    （`ConnectionFactory.DEFAULT_CHANNEL_RPC_TIMEOUT = TimeUnit.MINUTES.toMillis(10)`）。
 *    `basicGet` 走的是 `AMQChannel.privateRpc(Method)`，字节码里 `_rpcTimeout != 0` 时
 *    才用带超时的 `getReply(timeout)`，为 0 就无限等。所以「卡住」的真实形态不是永不
 *    返回，而是**卡 10 分钟** —— 对界面来说没有区别。
 *  - `shutdownTimeout` 默认 **10 秒**（`DEFAULT_SHUTDOWN_TIMEOUT`）：`close()` 等
 *    close-ok 的上限，正是「关闭时卡 10 秒」的来源。
 *  - `handshakeTimeout` 默认 10 秒（`DEFAULT_HANDSHAKE_TIMEOUT`），保持默认但显式写出，
 *    免得日后有人以为它没配。
 *
 * 关键点：这些是**底层 RPC 自己的超时**，到点抛 `ChannelContinuationTimeoutException`
 * 或 `TimeoutException`，调用栈正常返回、锁正常释放。这与协程层 `withTimeout` 有本质
 * 区别 —— 后者只让等待方放弃，杀不掉阻塞在 socket 上的调用，被留下的锁就成了死锁。
 */

/**
 * 单次 channel RPC 的上限。**它同时也是「拉取一条消息」的上限**，因为
 * `basicGet` 的返回包含整个消息体 —— 这个事实决定了取值。
 *
 * 真机实测（chillbear，公网，2026-11-09）的传输速率约 **700 KB/s**：
 *
 * | body | basicGet 耗时 |
 * |------|---------------|
 * | 128 KB | 168 ms |
 * | 1 MB | 1.4 s |
 * | 2 MB | 2.6 s |
 * | 4 MB | **5.97 s** |
 * | 8 MB | 11.6 s |
 *
 * 原来取 5s，于是**约 3.5 MB 以上的消息根本浏览不了**，而且报「broker 未及时响应」——
 * 把「链路慢」冤枉成了「broker 死了」，还会顺带把 channel 报废。
 *
 * 30s 在实测带宽下覆盖到 ~20 MB，能包住常见的 16 MB 上限；同时仍比 amqp-client
 * 默认的 10 分钟快 20 倍 —— 真卡死时用户最多等 30s，而不是十分钟。
 *
 * 死连接的**主要**检测手段是心跳（[AMQP_REQUESTED_HEARTBEAT_S]），不是这个值：
 * 心跳发现对端不说话后 `isOpen` 翻假，下一次取用直接重建连接。
 */
internal const val AMQP_CHANNEL_RPC_TIMEOUT_MS = 30_000
internal const val AMQP_SHUTDOWN_TIMEOUT_MS = 3_000
internal const val AMQP_HANDSHAKE_TIMEOUT_MS = 10_000

/**
 * 心跳间隔（秒）。**这是「静默死连接」的唯一探测手段**。
 *
 * amqp-client 默认 `requestedHeartbeat = 0`（禁用），而本项目的共享连接是长期存活的。
 * 两个后果叠加起来就是灾难：
 *
 *  - 断网 / 休眠 / 中间设备静默丢包时，TCP 不会主动告知，`Connection.isOpen` 仍为
 *    `true`；
 *  - 于是 [RabbitMqConnectionManager.amqpConnection] 认为连接可用、不会重建，每次
 *    `basicGet` 都要硬等满 `channelRpcTimeout` 才报超时 —— 界面重现「每次点
 *    都转 5 秒」的卡顿。
 *
 * 开启心跳后，服务端或客户端在 1–2 个周期（60–120s）内发现对方不应答，主动关闭
 * 连接，使 `isOpen` 翻假。之后下一次取用会走「旧连接已死 → 关掉并重建」的路径
 * （见 `amqpConnection`）。
 *
 * 60s 是 RabbitMQ 官方文档的推荐值：太短会增加空转流量，太长（如 600s）则死连接
 * 要十分钟才能被发现，用户体验上等于没有。
 */
internal const val AMQP_REQUESTED_HEARTBEAT_S = 60

/**
 * AMQP 操作超时的**领域异常**，公开给 UI 层。
 *
 * 为什么需要它：[ChannelContinuationTimeoutException] 来自 `amqp-client`，而它在
 * `rabbitmq-service` 里是 `implementation` 依赖（不外泄）。让 UI 去 catch 它就得把
 * AMQP 类型泄到 UI 模块，或者靠匹配异常类名 / 文案（脆）。所以 service 层在这里
 * 翻译一次：底层超时 → 本类型，UI 只认这一个。
 *
 * 继承 `IllegalStateException`：调用点本来就用「普通异常」表达失败，这样既有的
 * `failureText` / `catch (Exception)` 收尾逻辑无需分支。
 */
class RabbitMqOperationTimeoutException(
    errorKey: String,
    fallbackMessage: String,
    cause: Throwable? = null,
) : LocalizedException(errorKey, emptyList(), fallbackMessage, cause)

/**
 * 统一的 [ConnectionFactory]。**所有** AMQP 连接都从这里出（共享连接与独占连接），
 * 超时配置只有一份 —— 不会出现「某条路径忘了设 channelRpcTimeout」这种漏洞。
 */
internal fun rabbitMqConnectionFactory(config: RabbitMqConfig): ConnectionFactory =
    ConnectionFactory().apply {
        host = config.host.trim()
        port = config.amqpPort
        username = config.username
        password = config.password
        virtualHost = config.vhost
        connectionTimeout = config.connectionTimeout * 1_000
        handshakeTimeout = AMQP_HANDSHAKE_TIMEOUT_MS
        shutdownTimeout = AMQP_SHUTDOWN_TIMEOUT_MS
        // ← 让 basicGet / basicAck / basicPublish / channel.close 自己在上限后抛，
        //   而不是靠协程 withTimeout 放弃等待。
        channelRpcTimeout = AMQP_CHANNEL_RPC_TIMEOUT_MS
        // 心跳：探测静默死连接（断网 / 休眠 / 中间设备丢包），详见 [AMQP_REQUESTED_HEARTBEAT_S]。
        // 不开心跳时，这种连接的 isOpen 永远是 true，每次 basicGet 都硬等满上限。
        requestedHeartbeat = AMQP_REQUESTED_HEARTBEAT_S
        // 会话生命周期由对话框 / 连接上下文显式管理，不走自动恢复
        // （自动重连会让 close 语义变得不可预测）。
        // 注意：关自动恢复 + 开心跳 是刻意搭配 —— 心跳负责「发现死连接」，
        // 重建由 amqpConnection 的显式逻辑做，两者分工，不能让自动恢复插手。
        isAutomaticRecoveryEnabled = false
        if (config.useTls) {
            rabbitMqSslContext(config.tlsTruststorePath, config.tlsTruststorePassword)
                ?.let { useSslProtocol(it) }
                ?: run { useSslProtocol() }
        }
    }

/**
 * 报废 / 关闭一条 channel 的后台工人池：**有界**，满就丢，绝不调回调用方线程。
 *
 * 为什么需要它（一次真机故障的根因）：
 *
 * `ChannelN.abort(int, String)` 并不是「发一帧就返回」那么无害 —— 它走
 * `close(...)` → `startProcessShutdownSignal(...)` → `quiescingRpc(Channel.Close, null)`
 * → `quiescingTransmit` → `Frame.writeTo(DataOutputStream)`，最后落到
 * `SocketFrameHandler.writeFrame`，那里拿的是**每连接一把**的 `_outputStreamLock`，
 * 直接写裸 socket。Java 的 `SO_TIMEOUT` 只作用于**读**，socket 写没有超时：
 * 对端不读（broker 进入 flow control / 端口转发不再转发 / 发送缓冲区满）时，
 * 这次 `writeTo` 会**永久阻塞**。
 *
 * 于是旧写法（在 `channelOps.withLock { ... }` 里调 abort）会造成：
 *
 * ```
 * basicGet 等不到回复 → RPC 超时（用户看到「超时」）
 *   → discardChannel() → abort() 阻塞在 socket 写上
 *   → channelOps（Mutex）永不释放
 *   → 此后每次拉取 / ack / 发布都挤在同一把锁上 → 「超时一次，之后彻底不动」
 * ```
 *
 * 而 `_outputStreamLock` 是连接级的，一条卡死的 abort 还会把**整条共享连接**毒掉。
 *
 * 所以 abort 只能作为**尽力而为的善后**扔到后台：调用方立刻拿到锁与线程。真卡住时
 * 损失的只是后台工人的一条线程（池上限 2）与这条连接上的一个 channel 号 —— 由心跳 /
 * 重连回收，而不再是整个会话的死锁。
 *
 * 有界 + 丢任务（而不是 `CallerRunsPolicy`）：后者会把阻塞重新搬回调用方线程，正是
 * 要治的那个病。
 *
 * ## 什么时候「等一会儿」而不是直接丢后台
 *
 * 默认是纯后台（`waitMs = 0`）。但会话 `close()` 不同：它跑在模块 scope / daemon 线程 / 
 * `runBlocking` 里，**不在 UI 线程**，而「close 返回时 channel 真的关掉了」是个有用的
 * 性质 —— 共享连接不会因为快速开关对话框而瞬时堆一串 channel 号（合并门禁也在测它）。
 * 所以那里给一个**有界**等待（[ABORT_WAIT_ON_CLOSE_MS]）：健康的 abort 是微秒级，
 * 立刻返回；卡住时超时就走人，任务继续在后台跑，与阻塞无关。
 */
private const val ABORT_POOL_THREADS = 2
private const val ABORT_POOL_QUEUE = 8
private const val ABORT_POOL_KEEPALIVE_S = 30L

/**
 * `close()` 等 abort 的上限（毫秒）：只为了让正常路径下「关了就是关了」。
 * 超过它不管了 —— 后台线程继续跑，调用方拿走。
 */
internal const val ABORT_WAIT_ON_CLOSE_MS = 500L

private val abortPool: ThreadPoolExecutor =
    ThreadPoolExecutor(
        ABORT_POOL_THREADS,
        ABORT_POOL_THREADS,
        ABORT_POOL_KEEPALIVE_S,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(ABORT_POOL_QUEUE),
        ThreadFactory { runnable ->
            Thread(runnable, "rabbitmq-channel-abort").apply { isDaemon = true }
        },
        // 队列也满 → 丢。丢掉的只是一次 best-effort 的善后，连接断开时 broker 会自己
        // 回收 channel 与其中的 unacked 消息；而把它跑在调用方线程上会直接死锁。
        { _, _ -> rabbitMqLog.warn("channel abort dropped: 后台工人池已满（socket 写可能已卡死）") },
    ).apply { allowCoreThreadTimeOut(true) }

/**
 * 在**后台**报废一条 channel（尽力而为，不阻塞调用方）。
 *
 * 用于所有「已经证明不可用 / 不再需要」的 channel：RPC 超时后的 [discardChannel]、
 * `queueDeclarePassive` 失败、会话已关时迟到的 channel、会话 close 本身。
 * **绝不得**在任何 `channelOps` 临界区内同步调用它 —— 那正是它存在的原因。
 *
 * @param waitMs 最多等多久确认 abort 完成（0 = 纯丢后台）。只有 `close()` 用非 0 值：
 *   它跑在非 UI 线程上，稍等一会儿能换来「关闭返回时 channel 已真关」这个性质。
 *   超时不会取消后台任务，只是调用方不等了。
 *
 * 每次调用会记一行日志（含耗时）：耗时远超 `channelRpcTimeout` 说明 socket 写真的
 * 卡住了，这是下次排查「越用越慢 / 卡死」的第一手证据。
 */
internal fun abortInBackground(channel: Channel?, reason: String, waitMs: Long = 0) {
    if (channel == null) return
    val number = runCatching { channel.channelNumber }.getOrDefault(-1)
    val future = runCatching {
        abortPool.submit {
            val startedAt = System.currentTimeMillis()
            val failure = runCatching { channel.abort(AMQP.REPLY_SUCCESS, reason) }.exceptionOrNull()
            val elapsed = System.currentTimeMillis() - startedAt
            when {
                failure != null -> rabbitMqLog.warn(
                    "channel abort failed: channel=$number reason=$reason elapsed=${elapsed}ms " +
                        "error=${failure.message ?: failure::class.simpleName}",
                )
                // 超过 RPC 超时说明卡在 socket 写上 —— 值得单独抬高到 WARN。
                elapsed > AMQP_CHANNEL_RPC_TIMEOUT_MS -> rabbitMqLog.warn(
                    "channel abort blocked on write: channel=$number reason=$reason elapsed=${elapsed}ms " +
                        "（socket 写无超时，对端可能已停止读取）",
                )
                else -> rabbitMqLog.debug(
                    "channel aborted: channel=$number reason=$reason elapsed=${elapsed}ms",
                )
            }
        }
    }.getOrNull()
    if (future == null) {
        rabbitMqLog.warn("channel abort rejected: channel=$number reason=$reason（后台工人池已停止）")
        return
    }
    if (waitMs <= 0) return
    try {
        future.get(waitMs, TimeUnit.MILLISECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
        // 正常情况不会到这里（健康 abort 是微秒级）。到了就说明这次写帧卡住了，
        // 调用方不再等 —— 后台线程会继续尝试，失败由它自己的日志交代。
        rabbitMqLog.debug("channel abort still running after ${waitMs}ms: channel=$number reason=$reason")
    } catch (e: Exception) {
        // 执行器已关 / 任务被取消：都是收尾阶段的正常噪声。
    }
}

/**
 * AMQP 建连 / 队列校验类错误 → 人类可读文案（与 [RabbitMqAdminClient] 的错误口径一致）。
 *
 * 抽成顶层函数是因为它现在有两个调用方：共享连接的懒建（ConnectionManager）与独占
 * 连接的 [RabbitMqMessageSession.open]（测试 / 旧路径）。两份文案必须一致，否则同一
 * 个故障在两条路径上给出不同解释。
 */
internal fun mapAmqpOpenError(e: Throwable, config: RabbitMqConfig): Exception {
    val text = (e.message ?: "") + (e.cause?.message?.let { " " + it } ?: "")
    val target = "${config.host}:${config.amqpPort}"
    return when {
        e is java.net.ConnectException ->
            ServiceException(
                "rabbitmq_err_amqp_connect",
                args = listOf(target),
                fallbackMessage = "Cannot connect to AMQP %1\$s: check the address and port (default 5672)", cause = e,
            )
        e is java.util.concurrent.TimeoutException || e.cause is java.util.concurrent.TimeoutException ->
            ServiceException(
                "rabbitmq_err_amqp_connect_timeout",
                args = listOf(config.connectionTimeout, target),
                fallbackMessage = "Connection timed out (%1\$ss): AMQP %2\$s is not responding", cause = e,
            )
        text.contains("ACCESS_REFUSED") || text.contains("authentication", ignoreCase = true) ->
            ServiceException(
                "rabbitmq_err_amqp_auth_failed",
                fallbackMessage = "Authentication failed: check the username / password (AMQP and the management API share credentials)", cause = e,
            )
        text.contains("404") || text.contains("NOT_FOUND") ->
            ServiceException(
                "rabbitmq_err_queue_not_found",
                fallbackMessage = "Queue not found (404): check the queue name and vhost", cause = e,
            )
        else -> IllegalStateException(e.message ?: e::class.simpleName ?: "unknown", e)
    }
}
