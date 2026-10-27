package com.mqclient.rocketmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.cache.CacheManager

/**
 * 「为什么拒结」的唯一判定点：请求入口只在**真的没有客户端**时才拒结，并且按
 * 状态给出可区分的原因（与 RabbitMQ / Kafka 同口径）。
 *
 *  - `Connecting` → 让用户等一下，不是错；
 *  - `Failed` → 必须把上次的原因带出来，否则用户不知道去改什么；
 *  - `Disconnected` → 指去侧边栏；
 *  - `Connected` 但客户端已释放 → 只发生在连接 / 断开的一颠之间，提示重连即可。
 */
internal fun connectionRefusalText(ctx: RocketMqConnectionContext): String = when (ctx.status) {
    ConnectionStatus.Connecting -> "正在连接，请稍候"
    ConnectionStatus.Failed ->
        "未连接：上次请求失败（${ctx.lastError?.takeIf(String::isNotBlank) ?: "原因未知"}）"
    ConnectionStatus.Disconnected -> "未连接：请先在侧栏连接该 RocketMQ"
    ConnectionStatus.Connected -> "未连接：管理客户端已释放，请重新连接"
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
    val connectionId: String,
    @Volatile
    var config: RocketMqConfig,
) {

    /** 连接状态；写入口在 [RocketMqConnectionManager.setStatus]（同时上报核心）。 */
    @Volatile
    var status: ConnectionStatus = ConnectionStatus.Disconnected

    /** 连接成功后的管理客户端；断开 / 失败 / 未连接时为 null。 */
    @Volatile
    var adminClient: RocketMqAdminClient? = null

    /**
     * 本连接专属的扫描缓存（[CacheManager]，扫描链路 write-through、读口只读）。
     * 连接成功时创建、断开 / 失败时置空。
     */
    @Volatile
    var cache: CacheManager? = null

    /**
     * 本连接的**共享消息客户端**（PullConsumer + Producer + TLS 持有），懒建、随连接存活。
     *
     * 为什么要挂在上下而而不是每次新建：见 [RocketMqMessageClients] 的 KDoc —— 两者
     * 构造 + start 都要拉路由、注册组、起线程，且旧实现每次用新组名，broker 侧会
     * 稳定积累幽灵组。生命周期：首次读 / 发时创建，`disconnect` / 删除 / 模块关闭时
     * 由 [RocketMqConnectionManager.closeQuietly] 关闭并置 null。
     */
    @Volatile
    var messageClients: RocketMqMessageClients? = null

    /** 最近一次失败的原因（连接或后续操作），UI 可以展示；成功连接时清空。 */
    @Volatile
    var lastError: String? = null

    /**
     * 连续失败的心跳（Lag 快扫）次数。成功一次归零，见 [resetScanFailures]。
     *
     * 为什么要计数而不是「失败一次就判死」：Remoting 请求经代理 / NameServer 路由时，
     * 单次超时或某个 broker 短暂不答是常事，下一次就好。单次失败直接置 `Failed` 会把
     * 健康连接误判为死亡，而扫描入口又要求 `Connected` —— 判死之后心跳再也不跑，
     * **永远不会自愈**。
     */
    private val scanFailures = java.util.concurrent.atomic.AtomicInteger(0)

    /** 记一次心跳失败，返回累计连续失败次数。 */
    fun recordScanFailure(): Int = scanFailures.incrementAndGet()

    /** 心跳成功：连续失败计数归零（下一次失败重新从 1 数起）。 */
    fun resetScanFailures() {
        scanFailures.set(0)
    }

    /** 当前连续失败次数（日志 / 恢复提示用）。 */
    fun scanFailureCount(): Int = scanFailures.get()

    /**
     * 断开 / 删除时的清理：丢弃 AdminExt 引用并**清空缓存**（连接断开不留历史）。
     * AdminExt 本身的 close 由 [RocketMqConnectionManager] 负责（「谁创建谁清理」）。
     */
    fun cleanup() {
        adminClient = null
        // messageClients 的 shutdown 是 suspend + 要删临时 PEM，必须在 IO 上做，
        // 不能进这个同步清理；由 RocketMqConnectionManager.closeQuietly 负责关闭并置空。
        messageClients = null
        cache?.clear()
        cache = null
        resetScanFailures()
    }
}
