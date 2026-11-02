package com.mqclient.rabbitmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ServiceException
import com.mqclient.mq.base.AbstractConnectionContext
import com.rabbitmq.client.Connection

/**
 * 「为什么拒绝」的唯一判定点：请求入口只在**真的没有客户端**时才拒绝，并且按
 * 状态给出可区分的原因。
 *
 * 这里的每一条都对应一类真实场景，不能合并成一句「未连接」：
 *  - 刚启动 / 刚点连接（`Connecting`）→ 让用户等一下，不是错；
 *  - 连接真的失败（`Failed`）→ 必须把上次的原因带出来，否则用户不知道去改什么；
 *  - 用户没连（`Disconnected`）→ 指去侧边栏；
 *  - `Connected` 但客户端已释放 → 只发生在连接 / 断开的一颠之间（先置状态后关
 *    句柄），提示重连即可。
 */
internal fun connectionRefusal(ctx: RabbitMqConnectionContext): ServiceException = when (ctx.status) {
    ConnectionStatus.Connecting -> ServiceException(
        "rabbitmq_err_refusal_connecting",
        fallbackMessage = "Connecting — please wait",
    )
    ConnectionStatus.Failed -> ServiceException(
        "rabbitmq_err_refusal_failed",
        args = listOf(ctx.lastError?.takeIf(String::isNotBlank) ?: "unknown reason"),
        fallbackMessage = "Not connected: the last request failed (%1\$s)",
    )
    ConnectionStatus.Disconnected -> ServiceException(
        "rabbitmq_err_refusal_disconnected",
        fallbackMessage = "Not connected: connect this RabbitMQ from the sidebar first",
    )
    ConnectionStatus.Connected -> ServiceException(
        "rabbitmq_err_refusal_released",
        fallbackMessage = "Not connected: the management API client was released — reconnect first",
    )
}

/**
 * 一条 RabbitMQ 连接的运行时上下文：显式连接模型下它就是这个连接的全部可变状态。
 * 生命周期与 KafkaConnectionContext 同构（由 [RabbitMqConnectionManager] 驱动）：
 *  - 程序启动 / 连接新增时创建：状态 [ConnectionStatus.Disconnected]，没有 HTTP 客户端；
 *  - 用户显式连接成功：状态 Connected，[admin] 才被赋值；
 *  - 断开 / 删除 / 模块关闭：[cleanup] 收走句柄引用，回到 Disconnected。
 *
 * 状态的事实来源有两份但各管各的：[status] 供模块内部守卫（防重复连接），侧边栏
 * 显示走 `ConnectionStore.reportStatus` —— RabbitMqConnectionManager.setStatus 每次
 * 改状态都同时喂这两处。
 *
 * [config] 是连接时的配置快照；`connection.updated` 事件到来时由 manager 原地换新
 * （val 是为了构造期不可变，运行期换快照经 `@Volatile var` 保证可见性）。
 */
class RabbitMqConnectionContext(
    connectionId: String,
    config: RabbitMqConfig,
) : AbstractConnectionContext<RabbitMqConfig>(connectionId, config) {

    /**
     * 连接成功后的管理 API 客户端（Ktor HttpClient 挂在它内部）；断开 / 失败 / 未连接
     * 时为 null。RabbitMQ 第一阶段没有常驻 AMQP 连接 —— 唯一的「句柄」就是它。
     */
    @Volatile
    var admin: RabbitMqAdminClient? = null

    /**
     * 管理 API 之外的第二类句柄：**共享的 AMQP 物理连接**（消息浏览 / 发送用）。
     *
     * 为什么放在连接上下文里而不是对话框里：物理连接的建立要一次 TCP + AMQP 握手，
     * 每次打开消息对话框都 `factory.newConnection()` 是「越开越慢」的直接原因。
     * 挂在这里之后，同一个连接下的所有消息对话框共用一条物理连接，各自只
     * `createChannel()`——channel 是轻量级的（一个 channel 号 + 一帧控制消息）。
     *
     * **懒建**：连接成功（`connect()`）时**不**建它，首次需要读 / 发消息时才建
     * （见 `RabbitMqConnectionManager.amqpConnection`）。理由：只想看队列列表、
     * 从不读消息的用户不应该为一次 AMQP 握手买单，也不应该承担 AMQP 失败的风险。
     *
     * 生命周期：断开 / 失败 / 模块关闭时由 ConnectionManager 用
     * `close(AMQP_SHUTDOWN_TIMEOUT_MS)` 关闭（不等 close-ok 太久）。
     */
    @Volatile
    var amqp: Connection? = null

    /**
     * 建连验证（`GET /api/overview`）顺带拿到的集群元信息，等待 [cleanup] / 断开丢弃。
     *
     * 为什么要暂存：cache 由基类在 `open` **之后**创建（「没连上就没有数据」在缓存层
     * 也成立），所以 open 阶段拿到的元信息没地方放；基类的 `onConnected` 钩子正好在
     * cache 创建后、置 Connected 前，那里写进去。
     */
    @Volatile
    var pendingClusterInfo: RabbitMqClusterInfoSnapshot? = null

    /**
     * 断开 / 删除时的清理：丢弃 admin / amqp 引用；缓存与失败计数由基类
     * [AbstractConnectionContext.cleanup] 处理。AMQP 连接的关闭是 suspend + 带超时的，
     * 由 manager 的 closeResources 负责（不能进这个同步清理）。
     */
    override fun cleanup() {
        admin = null
        amqp = null
        pendingClusterInfo = null
        super.cleanup()
    }
}
