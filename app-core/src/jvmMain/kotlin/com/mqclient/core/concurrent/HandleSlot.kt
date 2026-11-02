package com.mqclient.core.concurrent

/**
 * 连接级**懒建句柄**的创建 / 关闭互斥槽。
 *
 * ## 它解决的竞态
 *
 * 句柄是懒建的（第一次用到才创建），而关闭发生在另一条路径上（用户断开 / 删除连接 /
 * 模块退出）。如果两条路径用**不同的锁**，就会出现这个交错：
 *
 * ```
 * A: 开始创建（KafkaConsumer 要拉元数据 / RocketMQ 要 start 并注册组，都慢）
 * B: 读引用 → null（A 还没赋值）→ 置 null → 状态改为 Disconnected
 * A: 创建完成 → 赋值      ← 句柄挂在一个已经断开的连接上，无人认领
 * ```
 *
 * 这个孤儿只有在**应用退出**时才会被 `shutdownAll()` 兜底关掉。在 RocketMQ 那条
 * 路径上后果更重：句柄持有 TLS 构造时产生的临时 PEM，其中私钥文件是**未加密**的，
 * 孤儿意味着它永久留在临时目录。
 *
 * ## 为什么它成立
 *
 * 「检查 + 创建 + 赋值」与「标记断开 + 取走引用」落在**同一个监视器**里，于是只有
 * 两种结果：创建先完成（关闭方拿到句柄并关掉），或关闭先发生（创建方被拒并抛错）。
 * 没有第三种。
 *
 * ## 为什么用 `synchronized` 而不是 `Mutex`
 *
 * 临界区是「一次内存赋值 + 一次构造调用」，没有挂起点；而创建路径的调用方
 * （`clients()`）是普通函数，不是 suspend。[Mutex] 需要 suspend 上下文，会把一个
 * 纯同步的查询路径硬掰成 suspend，得不偿失。
 *
 * 代价：`create()` 是在监视器**内**跑的。这对 Kafka / RocketMQ 都可接受（构造是
 * 百毫秒级），而且正是我们想要的 —— 关闭方必须等创建结束才能取走引用，否则又回到
 * 孤儿问题。
 *
 * ## 与 [KeyedMutex] 的分工
 *
 * [KeyedMutex] 管「同一连接上的业务操作串行」（扫描、刷新），[HandleSlot] 管
 * 「句柄生命周期的原子性」。两者不能互相替代：业务锁在关闭路径上必须能被绕过，
 * 否则一次卡住的读取会把断开一起拖死。
 *
 * ## 用法
 *
 * ```
 * // context 里
 * val messageClients = HandleSlot<KafkaMessageClients>("${connectionId} 消息句柄")
 *
 * // 读 / 发路径：需要就建，已建就复用
 * ctx.messageClients.acquire(canCreate = { adminClient != null }) { KafkaMessageClients(config) }
 *
 * // connect 成功后复位（在此之前 closeQuietly 已把它标记为断开）
 * ctx.messageClients.reopen()
 *
 * // 断开：标记 + 取走，在锁外（IO 上）关
 * ctx.messageClients.take()?.let { runCatching { it.closeQuietly() } }
 * ```
 */
class HandleSlot<T : Any>(private val label: String) {

    private val lock = Any()

    private var handle: T? = null

    /** 是否已被判定「不可再创建句柄」。由 [take] 置位、[reopen] 复位。 */
    private var disposed = false

    /**
     * 取句柄；没有且允许创建时调用 [create] 建一个（之后一直复用）。
     *
     * @param canCreate 额外的创建守卫，在**同一个监视器里**求值，因此不会与 [take]
     *   交错。传 `{ adminClient != null }` 这类条件，就同时拿到了「连接活着」与
     *   「没有被并发关闭」两层保证。
     * @throws IllegalStateException 槽已断开，或 [canCreate] 为 false。
     */
    fun acquire(canCreate: () -> Boolean = { true }, create: () -> T): T =
        synchronized(lock) {
            check(!disposed) { "$label is disconnected: refusing to create a new handle (closed or reconnecting)" }
            check(canCreate()) { "$label is in a state that does not allow creating a handle" }
            handle ?: create().also { handle = it }
        }

    /** 只读查看，不触发创建（诊断 / 「是否已建」判断用）。 */
    fun peek(): T? = synchronized(lock) { handle }

    /**
     * 标记断开并取走句柄，交给调用方在监视器**外**关闭。
     *
     * 置 `disposed` 与取引用是原子的：之后任何 [acquire] 都会立刻被拒，不会再有新句柄
     * 挂到一个已经断开的连接上。
     *
     * 为什么必须在锁外关：关闭通常是 suspend + 阻塞 IO（Kafka 要 wakeup 再 close、
     * RocketMQ 要 shutdown 并删临时 PEM），持监视器做这件事会阻塞所有读取线程。
     */
    fun take(): T? =
        synchronized(lock) {
            disposed = true
            handle.also { handle = null }
        }

    /**
     * 重新连接成功后复位，允许再次懒建。
     *
     * 只应由 **connect 成功路径**调用，且要在 `setStatus(Connected)` **之前** ——
     * 否则读路径可能先观察到 Connected、再撞上仍为断开的槽。
     */
    fun reopen() {
        synchronized(lock) { disposed = false }
    }

    /** 断开后到重连之间为 true（测试 / 诊断用）。 */
    val isDisposed: Boolean get() = synchronized(lock) { disposed }
}
