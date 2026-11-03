package com.mqclient.rocketmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ServiceException
import com.mqclient.core.concurrent.HandleSlot
import com.mqclient.mq.base.AbstractConnectionContext

/**
 * 「为什么拒结」的唯一判定点：请求入口只在**真的没有客户端**时才拒结，并且按
 * 状态给出可区分的原因（与 RabbitMQ / Kafka 同口径）。
 *
 *  - `Connecting` → 让用户等一下，不是错；
 *  - `Failed` → 必须把上次的原因带出来，否则用户不知道去改什么；
 *  - `Disconnected` → 指去侧边栏；
 *  - `Connected` 但客户端已释放 → 只发生在连接 / 断开的一颠之间，提示重连即可。
 *
 * 返回 [ServiceException]（errorKey = `rocketmq_err_refusal_*`）：文案由展示端在
 * 渲染时点翻译，message 是英文兜底 —— 见 app-core 的 ServiceException。
 */
internal fun connectionRefusal(ctx: RocketMqConnectionContext): ServiceException = when (ctx.status) {
    ConnectionStatus.Connecting -> ServiceException(
        "rocketmq_err_refusal_connecting",
        fallbackMessage = "Connecting — please wait",
    )
    ConnectionStatus.Failed -> ServiceException(
        "rocketmq_err_refusal_failed",
        args = listOf(ctx.lastError?.takeIf(String::isNotBlank) ?: "unknown reason"),
        fallbackMessage = "Not connected: the last request failed (%1\$s)",
    )
    ConnectionStatus.Disconnected -> ServiceException(
        "rocketmq_err_refusal_disconnected",
        fallbackMessage = "Not connected: connect this RocketMQ from the sidebar first",
    )
    ConnectionStatus.Connected -> ServiceException(
        "rocketmq_err_refusal_released",
        fallbackMessage = "Not connected: the admin client was released — reconnect first",
    )
}

/**
 * 一条 RocketMQ 连接的运行时上下文：显式连接模型下它就是这个连接的全部可变状态。
 * 生命周期与 kafka 的 KafkaConnectionContext 同构（由 [RocketMqConnectionManager]
 * 驱动）：程序启动 / 连接新增时创建（Disconnected、无句柄）；用户显式连接成功后
 * [adminExt] 才被赋值；断开 / 删除 / 模块关闭经 [cleanup] 收走引用，回到 Disconnected。
 *
 * 状态的事实来源有两份但各管各的：[status] 供模块内部守卫（防重复连接），侧边栏
 * 显示走 `ConnectionStore.reportStatus` —— [RocketMqConnectionManager.setStatus]
 * 每次改状态都同时喂这两处。
 */
class RocketMqConnectionContext(
    connectionId: String,
    config: RocketMqConfig,
) : AbstractConnectionContext<RocketMqConfig>(connectionId, config) {

    /** 连接成功后的管理客户端；断开 / 失败 / 未连接时为 null。 */
    @Volatile
    var adminClient: RocketMqAdminClient? = null

    /**
     * 本连接的**共享消息客户端**（PullConsumer + Producer + TLS 持有），懒建、随连接存活。
     *
     * 为什么要挂在上下而而不是每次新建：见 [RocketMqMessageClients] 的 KDoc —— 两者
     * 构造 + start 都要拉路由、注册组、起线程，且旧实现每次用新组名，broker 侧会
     * 稳定积累幽灵组。
     *
     * 用 [HandleSlot] 而不是裸的 `@Volatile var`：创建（读 / 发路径）与关闭（断开路径）
     * 曾用**不同的锁**，交错会产生孤儿句柄。这里后果比其他模块更重：句柄持有 TLS
     * 构造产生的临时 PEM，**未加密私钥**文件只能靠 `close()` 删——孤儿就意味着它永久
     * 留在临时目录。槽把「检查 + 创建 + 赋值」与「标记断开 + 取走」放在同一监视器里。
     */
    val messageClients = HandleSlot<RocketMqMessageClients>("${connectionId} 消息句柄")

    /**
     * 断开 / 删除时的清理：丢弃 AdminExt 引用；缓存与失败计数由基类
     * [AbstractConnectionContext.cleanup] 处理（messageClients 的 shutdown 是
     * suspend + 要删临时 PEM，由 manager 的 closeResources 通过 take() 取走再关）。
     */
    override fun cleanup() {
        adminClient = null
        super.cleanup()
    }
}
