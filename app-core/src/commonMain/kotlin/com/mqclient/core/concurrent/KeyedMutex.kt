package com.mqclient.core.concurrent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按 key 分组的互斥锁：**同 key 串行、不同 key 互不相干**。
 *
 * 为什么不是一把全局 [Mutex]：各模块的扫描服务是单例（每个 service 一个实例），
 * 一把锁意味着「连接 A 上的一次慢扫会挡住连接 B 的扫描」—— 扫描是彼此独立的
 * IO，UI 上表现为「另一个连接一直不刷新」。锁本来要防的只是**同一条连接**上
 * 重叠扫描互相覆盖结果，粒度就应该是连接。
 *
 * 条目按需创建、最后一个持有者离开时删除（见 [withLock] 的 finally），所以连接
 * 断开后不会留下空 Mutex；key 数量等于「正在扫描的连接数」，不需要额外的清理任务。
 *
 * 实现：一张普通 [MutableMap] 由内部 [Mutex] 保护（entries 只在这个守护锁里读写），
 * 业务锁则是每个 key 自己的 [Mutex]。**不要**把守护锁和业务锁合并 —— 那样又回到
 * 全局串行。
 *
 * 取消语义：等待业务锁期间被取消时，finally 仍会执行并归还引用计数，不会把条目
 * 永久留在表里。
 *
 * ```
 * private val scanLocks = KeyedMutex()
 * suspend fun scanObjects(connectionId: String) = scanLocks.withLock(connectionId) { … }
 * ```
 */
class KeyedMutex {

    /** 一个 key 的锁 + 持有者计数（计数归零即从表里摘除）。 */
    private class Entry {
        val mutex = Mutex()
        var holders = 0
    }

    /** 保护 [entries] 自身的增删，不参与业务串行。 */
    private val guard = Mutex()

    private val entries = mutableMapOf<String, Entry>()

    /** 当前有持有者（或等待者）的 key 数量，调试 / 测试用。 */
    val activeKeyCount: Int get() = entries.size

    /**
     * 在 [key] 对应的锁里执行 [block]；不同 key 的 block 可以并发执行。
     *
     * 重入不安全（与 [Mutex] 一致）：同一个协程对同一个 key 再调一次会死锁。
     */
    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        val entry = guard.withLock {
            entries.getOrPut(key) { Entry() }.also { it.holders++ }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            guard.withLock {
                entry.holders--
                if (entry.holders == 0) entries.remove(key)
            }
        }
    }
}
