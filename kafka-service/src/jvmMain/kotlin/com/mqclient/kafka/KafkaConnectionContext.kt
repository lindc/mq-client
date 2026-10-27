package com.mqclient.kafka

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.concurrent.HandleSlot
import com.mqclient.mq.base.AbstractConnectionContext
import org.apache.kafka.clients.admin.AdminClient

/**
 * 「为什么拒结」的唯一判定点：请求入口只在**真的没有客户端**时才拒结，并且按
 * 状态给出可区分的原因。
 *
 * 为什么不合并成一句「未连接」（与 RabbitMQ 同口径）：
 *  - 刚启动 / 刚点连接（`Connecting`）→ 让用户等一下，不是错；
 *  - 连接真的失败（`Failed`）→ 必须把上次的原因带出来，否则用户不知道去改什么；
 *  - 用户没连（`Disconnected`）→ 指去侧边栏；
 *  - `Connected` 但客户端已释放 → 只发生在连接 / 断开的一颠之间，提示重连即可。
 */
internal fun connectionRefusalText(ctx: KafkaConnectionContext): String = when (ctx.status) {
    ConnectionStatus.Connecting -> "正在连接，请稍候"
    ConnectionStatus.Failed ->
        "未连接：上次请求失败（${ctx.lastError?.takeIf(String::isNotBlank) ?: "原因未知"}）"
    ConnectionStatus.Disconnected -> "未连接：请先在侧栏连接该 Kafka"
    ConnectionStatus.Connected -> "未连接：AdminClient 已释放，请重新连接"
}

/**
 * 一条 Kafka 连接的运行时上下文：显式连接模型下它就是这个连接的全部可变状态。
 * 生命周期与 ibm-mq 的 IbmMQConnectionContext 同构（由 [KafkaConnectionManager] 驱动）：
 *  - 程序启动 / 连接新增时创建：状态 [ConnectionStatus.Disconnected]，没有 AdminClient；
 *  - 用户显式连接成功：状态 Connected，[adminClient] 才被赋值；
 *  - 断开 / 删除 / 模块关闭：[cleanup] 收走句柄引用，回到 Disconnected。
 *
 * 状态的事实来源有两份但各管各的：[status] 供模块内部守卫（防重复连接），侧边栏
 * 显示走 `ConnectionStore.reportStatus` —— [KafkaConnectionManager.setStatus] 每次
 * 改状态都同时喂这两处。
 *
 * [config] 是连接时的配置快照；`connection.updated` 事件到来时由 manager 原地换新
 * （val 是为了构造期不可变，运行期换快照经 `@Volatile var` 保证可见性）。
 */
class KafkaConnectionContext(
    connectionId: String,
    config: KafkaConfig,
) : AbstractConnectionContext<KafkaConfig>(connectionId, config) {

    /** 连接成功后的 AdminClient；断开 / 失败 / 未连接时为 null。 */
    @Volatile
    var adminClient: AdminClient? = null

    /**
     * 本连接的**共享消息客户端**（浏览 consumer + producer），懒建、随连接存活。
     *
     * 为什要挂在上下而而不是每次新建：见 [KafkaMessageClients] 的 KDoc ——
     * consumer 构造要拉元数据、producer 构造要起 sender 线程，逐次新建是“越用越慢”
     * 的直接原因。
     *
     * 用 [HandleSlot] 而不是裸的 `@Volatile var`：创建发生在读 / 发路径，关闭发生在
     * 断开路径，两者曾用**不同的锁**。交错下会出现「建好了却挂在一个已断开的连接上」
     * 的孤儿句柄 —— 只有应用退出时才会被兜底关掉。槽把「检查 + 创建 + 赋值」与
     * 「标记断开 + 取走」放在同一监视器里，孤儿不可能产生。
     *
     * 关闭的**执行**仍在 [KafkaConnectionManager.closeQuietly]：那一步是 suspend +
     * 阻塞 IO（wakeup 后 close），不能进监视器。
     */
    val messageClients = HandleSlot<KafkaMessageClients>("${connectionId} 消息句柄")

    /**
     * 断开 / 删除时的清理：丢弃 AdminClient 引用；缓存与失败计数由基类
     * [AbstractConnectionContext.cleanup] 处理。messageClients 的关闭是 suspend +
     * 带超时的（还要先 wakeup 打断 poll），由 manager 的 closeResources 用 take() 取走。
     */
    override fun cleanup() {
        adminClient = null
        super.cleanup()
    }
}
