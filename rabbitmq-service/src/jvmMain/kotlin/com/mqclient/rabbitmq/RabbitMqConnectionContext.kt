package com.mqclient.rabbitmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.cache.CacheManager

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
internal fun connectionRefusalText(ctx: RabbitMqConnectionContext): String = when (ctx.status) {
    ConnectionStatus.Connecting -> "正在连接，请稍候"
    ConnectionStatus.Failed ->
        "未连接：上次请求失败（${ctx.lastError?.takeIf(String::isNotBlank) ?: "原因未知"}）"
    ConnectionStatus.Disconnected -> "未连接：请先在侧栏连接该 RabbitMQ"
    ConnectionStatus.Connected -> "未连接：管理 API 客户端已释放，请重新连接"
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
    val connectionId: String,
    @Volatile
    var config: RabbitMqConfig,
) {

    /** 连接状态；写入口在 RabbitMqConnectionManager.setStatus（同时上报核心）。 */
    @Volatile
    var status: ConnectionStatus = ConnectionStatus.Disconnected

    /**
     * 连接成功后的管理 API 客户端（Ktor HttpClient 挂在它内部）；断开 / 失败 / 未连接
     * 时为 null。RabbitMQ 第一阶段没有常驻 AMQP 连接 —— 唯一的「句柄」就是它。
     */
    @Volatile
    var admin: RabbitMqAdminClient? = null

    /**
     * 本连接专属的扫描缓存（[CacheManager]，扫描链路 write-through、读口只读）。
     * 连接成功时创建、断开 / 失败时置空 —— 与 kafka / ibm-mq 同一生命周期。
     */
    @Volatile
    var cache: CacheManager? = null

    /** 最近一次失败的原因（连接或后续操作），UI 可以展示；成功连接时清空。 */
    @Volatile
    var lastError: String? = null

    /**
     * 连续失败的心跳（状态快扫）次数。成功一次归零，见 [resetScanFailures]。
     *
     * 为什么要计数而不是「失败一次就判死」：管理 API 走的是长连接池，代理 / 端口
     * 转发会回收空闲 socket，偶尔一次请求会拿到已被对端关掉的那条（日志里表现为
     * `HTTP/1.1 header parser received no bytes`）。那不是连接断了，下一次请求
     * 就好 —— 单次失败直接置 `Failed` 会把一条完全健康的连接误判为死亡，而且
     * 由于扫描入口本身要求 `Connected`，判死之后心跳再也不跑，**永远不会自愈**。
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
     * 断开 / 删除时的清理：丢弃 admin 引用并**清空缓存**（连接断开不留历史）。
     * HttpClient 本身的 close 由 [RabbitMqConnectionManager] 负责（「谁创建谁清理」：
     * 句柄是它连的）。
     */
    fun cleanup() {
        admin = null
        cache?.clear()
        cache = null
        resetScanFailures()
    }
}
