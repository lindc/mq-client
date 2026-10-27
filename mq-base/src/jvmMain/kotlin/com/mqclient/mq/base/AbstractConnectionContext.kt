package com.mqclient.mq.base

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.cache.CacheManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一条连接的运行时状态：**所有 MQ 模块上下文的最小公共部分**。
 *
 * 抽取理由：四个 `*ConnectionContext` 里的 `status` / `lastError` / `cache` /
 * `scanFailures` / `cleanup()` 曾经是逐字副本（连注释都一样）。副本本身还不是最大的
 * 问题 —— 问题在于「连续失败计数」这种**有语义的**逻辑复制四份时，修一处忘三处
 * （本项目上一轮刚在 RabbitMQ / Kafka / RocketMQ 上各补过一遍）。
 *
 * 子类只放**本协议独有**的东西（MQQueueManager / AdminClient / AMQP Connection …）。
 *
 * 线程可见性：所有可变字段都是 `@Volatile` —— 写在连接 / 扫描线程，读在 UI 线程。
 */
abstract class AbstractConnectionContext<C>(
    val connectionId: String,
    /** 连接时的配置快照；`connection.updated` 到来时由 manager 原地换新。 */
    @Volatile
    var config: C,
) {

    /** 连接状态；写入口在 [AbstractConnectionManager.setStatus]（同时上报核心）。 */
    @Volatile
    var status: ConnectionStatus = ConnectionStatus.Disconnected

    /** 最近一次失败的原因（连接或扫描），UI 可以展示；成功连接时清空。 */
    @Volatile
    var lastError: String? = null

    /**
     * 本连接专属的缓存：**连接成功时才创建**，断开时清空并置 null ——
     * 「没连上就没有数据」在缓存层也成立。扫描链路写、读入口只读缓存不碰 MQ。
     */
    @Volatile
    var cache: CacheManager? = null

    /**
     * 连续失败的心跳次数。成功一次归零。
     *
     * 为什么计数而不是「失败一次就判死」：单次请求撞上被代理回收的连接、或某个
     * broker 短暂不答，都是可恢复的请求失败。单次就置 `Failed` 会把健康连接误判为
     * 死亡，而扫描入口又要求 `Connected` —— 判死之后心跳再也不跑，**永远不会自愈**。
     */
    private val scanFailures = AtomicInteger(0)

    /** 记一次心跳失败，返回累计连续失败次数。 */
    fun recordScanFailure(): Int = scanFailures.incrementAndGet()

    /** 心跳成功：连续失败计数归零（下一次失败重新从 1 数起）。 */
    fun resetScanFailures() {
        scanFailures.set(0)
    }

    /** 当前连续失败次数（日志 / 恢复提示用）。 */
    fun scanFailureCount(): Int = scanFailures.get()

    /**
     * 断开 / 删除时的清理：清空缓存、丢弃句柄引用。
     *
     * 句柄本身的关闭是 suspend + 阻塞 IO 的，不能进这个同步清理 —— 由
     * [AbstractConnectionManager] 的 `closeResources` 负责（「谁创建谁清理」）。
     */
    open fun cleanup() {
        cache?.clear()
        cache = null
        resetScanFailures()
    }
}
