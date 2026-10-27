package com.mqclient.rabbitmq

import com.rabbitmq.client.ConnectionFactory

/**
 * AMQP 客户端的三个超时（毫秒）。**这是本次重构的承重墙**，不能省。
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
internal const val AMQP_CHANNEL_RPC_TIMEOUT_MS = 5_000
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
 *    `basicGet` 都要硬等满 `channelRpcTimeout`（5s）才报超时 —— 界面重现「每次点
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
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

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
        // ← 让 basicGet / basicAck / basicPublish / channel.close 自己在 5s 后抛，
        //   而不是靠协程 withTimeout 放弃等待。
        channelRpcTimeout = AMQP_CHANNEL_RPC_TIMEOUT_MS
        // 心跳：探测静默死连接（断网 / 休眠 / 中间设备丢包），详见 [AMQP_REQUESTED_HEARTBEAT_S]。
        // 不开心跳时，这种连接的 isOpen 永远是 true，每次 basicGet 都硬等满 5s。
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
            IllegalStateException("无法连接 AMQP $target：检查地址与端口（默认 5672）", e)
        e is java.util.concurrent.TimeoutException || e.cause is java.util.concurrent.TimeoutException ->
            IllegalStateException("连接超时（${config.connectionTimeout}s）：AMQP $target 无响应", e)
        text.contains("ACCESS_REFUSED") || text.contains("authentication", ignoreCase = true) ->
            IllegalStateException("认证失败：检查用户名 / 密码（AMQP 与管理 API 凭据相同）", e)
        text.contains("404") || text.contains("NOT_FOUND") ->
            IllegalStateException("队列不存在（404）：检查队列名与 vhost", e)
        else -> IllegalStateException(e.message ?: e::class.simpleName ?: "unknown", e)
    }
}
