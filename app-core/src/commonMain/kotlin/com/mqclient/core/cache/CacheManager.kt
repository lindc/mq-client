package com.mqclient.core.cache

import com.mqclient.core.event.systemTimeMillis
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 通用缓存：一个**普通工具类**，可以按需创建任意多个实例，实例之间互不相干。
 *
 * 设计约定（和「全局缓存组件」相反的那一套）：
 *  1. **不是单例**：谁需要谁创建，谁创建谁清理，生命周期由创建者负责
 *     （典型用法：每条连接一个实例，见 `IbmMQConnectionContext`）；
 *  2. **实例隔离数据**：所以 key 不需要模块 / 连接前缀 —— `"queues"`、
 *     `"queue.<name>.messageCount"` 就是完整语义；
 *  3. **惰性删除**：过期条目在下次 [get] 时被顺手清掉，没有扫描线程、没有清理任务；
 *  4. **不做淘汰策略**：个人桌面软件数据量小，不需要 LRU；
 *  5. **零依赖**：只用 stdlib + coroutines（[MutableStateFlow] 是 commonMain 的并发手段）。
 *
 * 线程安全：底层存储是 [MutableStateFlow] + CAS 整表替换，读侧拿到的永远是
 * 不可变快照 —— commonMain 里拿不到 `java.util.concurrent`（`ConcurrentHashMap`
 * 是 JVM 的），这是它在 common 下的等价物，与 `EventBus` 的订阅注册表、
 * `JobScheduler` 的任务表是同一套做法。[lastUpdatedAt] 用 `@Volatile` 保证可见性。
 *
 * ```
 * val cache = CacheManager()                        // 每条连接一个
 * cache.put("queues", queueList, ttlMs = 10 * 60_000)
 * val queues = cache.getAs<List<QueueSnapshot>>("queues")
 * cache.removeByPrefix("queue.order-")              // 批量清理某一类数据
 * if (!cache.isFresh(10 * 60_000)) { /* 太久没更新，触发重新扫描 */ }
 * ```
 */
class CacheManager {

    /**
     * key → 条目。写侧 CAS 整表替换（[update]），读侧拿到的是不可变快照。
     *
     * 每次写出会复制整张表，个人桌面场景（几十到几百条目）完全够用 ——
     * 写入极频繁、容量很大的场景才需要更细的结构，本类刻意不预支这份复杂度。
     */
    private val cache = MutableStateFlow<Map<String, CacheEntry>>(emptyMap())

    /**
     * 整个缓存的最后更新时间（epoch 毫秒），每次 [put] 刷新；从没写过时是 0。
     *
     * 与单条目的 [CacheEntry.expiresAt] 分工不同：TTL 是「这一条什么时候过期」，
     * 它回答的是「整个缓存什么时候动过」—— [isFresh] 靠它做「要不要重新扫描」的判断。
     */
    @Volatile
    private var lastUpdatedAt: Long = 0L

    /**
     * 写入缓存。
     *
     * @param key 缓存 key。实例本身隔离数据，所以不需要模块 / 连接前缀；
     *   点分命名 `<对象>.<标识>.<属性>`（如 `queue.order.properties`），便于按前缀清理。
     * @param value 缓存的值（任意类型；读取方用 [getAs] 取回具体类型）。
     * @param ttlMs 过期时间（毫秒），0（默认）表示**永不过期**。
     */
    fun put(key: String, value: Any?, ttlMs: Long = 0) {
        require(key.isNotBlank()) { "缓存 key 不能为空白：它是条目的唯一标识" }
        val now = systemTimeMillis()
        cache.update { current ->
            current + (key to CacheEntry(value, expiresAt = if (ttlMs > 0) now + ttlMs else 0))
        }
        lastUpdatedAt = now
    }

    /**
     * 读取缓存：过期返回 null，并**顺手删掉**这一条（惰性删除，不需要扫描线程）。
     */
    fun get(key: String): Any? {
        val entry = cache.value[key] ?: return null
        if (entry.expiresAt > 0 && systemTimeMillis() > entry.expiresAt) {
            // 只删「还是刚才读到的那一条」（=== 判同）：读与删之间可能已经有人
            // 写入了新值，无脑按 key 删会把刚写进去的新鲜数据一起误删。
            cache.update { current -> if (current[key] === entry) current - key else current }
            return null
        }
        return entry.value
    }

    /**
     * 泛型读取：不存在 / 已过期 / 类型不符都返回 null。
     *
     * ⚠️ `as? T` 的运行时检查受泛型擦除限制：`getAs<List<QueueSnapshot>>("queues")`
     * 只能验证到 `List`，元素类型不检查（与 `AppEvent.get` 同一约定）。
     * 本类不替元素类型背书 —— 谁写进去的谁保证。
     */
    inline fun <reified T> getAs(key: String): T? = get(key) as? T

    /** 删除单个 key（不存在时是安静的 no-op）。 */
    fun remove(key: String) {
        cache.update { it - key }
    }

    /**
     * 按前缀删除（批量清理某一类数据）。
     * 例：`removeByPrefix("queue.")` 清掉所有队列相关条目。
     *
     * 前缀按**字面**匹配，不做任何分隔符魔法：`"queue.a."` 不会误伤 `"queue.ab.x"`
     * —— 结尾的分隔点要自己带上。清空整表用 [clear]，空白前缀会被拒绝。
     */
    fun removeByPrefix(prefix: String) {
        require(prefix.isNotBlank()) { "前缀不能为空白：清空整表用 clear()" }
        cache.update { current -> current.filterKeys { !it.startsWith(prefix) } }
    }

    /**
     * 清空所有缓存，并把 [getLastUpdatedAt] 归零。
     *
     * 连接关闭 / 模块卸载时的收尾动作（「谁创建谁清理」里的清理）。
     */
    fun clear() {
        cache.value = emptyMap()
        lastUpdatedAt = 0L
    }

    /**
     * 当前条目数（调试用）。
     *
     * 是**尚未惰性清理**的原始计数：包含已过期、但还没被 [get] 碰到的条目。
     */
    fun size(): Int = cache.value.size

    /** 当前所有 key（调试用），同样包含已过期但还没被惰性清理的条目。 */
    fun keys(): List<String> = cache.value.keys.toList()

    /**
     * 整个缓存的最后更新时间（epoch 毫秒）；从没写过时是 0。
     * 用于判断缓存是不是「太久没更新了」。
     */
    fun getLastUpdatedAt(): Long = lastUpdatedAt

    /**
     * 缓存是否在最近 [withinMs] 毫秒内被写过（「整体新鲜度」）。
     *
     * 典型用法是「要不要重新扫描」的决策：
     * ```
     * if (!cache.isFresh(10 * 60_000L)) scanObjects(connectionId)
     * ```
     * 从没写过（[getLastUpdatedAt] == 0）永远算「不新鲜」。
     *
     * 与单 key TTL 的分工：TTL 是每条数据的兜底过期，isFresh 用于整体决策，两者互补。
     */
    fun isFresh(withinMs: Long): Boolean {
        return systemTimeMillis() - lastUpdatedAt < withinMs
    }

    private data class CacheEntry(
        /** 缓存值，任意类型。 */
        val value: Any?,
        /** 过期时刻（epoch 毫秒）；0 表示永不过期。 */
        val expiresAt: Long,
    )
}
