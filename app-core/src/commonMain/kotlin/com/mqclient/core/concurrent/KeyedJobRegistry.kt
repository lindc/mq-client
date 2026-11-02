package com.mqclient.core.concurrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 按 key 登记的「手动触发的后台协程」：同一 key 只保留一条，新的一来就顶掉旧的。
 *
 * 为什么需要它：UI 的刷新 / 重试按钮都是 fire-and-forget —— 起一条协程就返回。不登记的话，
 * 这条协程的生命周期只跟着模块 scope 走：连接断开了、句柄关了，它还在跑，而且手里还占着
 * 该连接的扫描锁（[KeyedMutex]），重连后的第一次刷新要排在它后面等（表现是「点刷新没反应」）。
 *
 * 与 `JobScheduler` 的分工：那个管「按间隔重复跑」的定时任务，这个管「用户点出来的那一次」。
 * 两者都有「同一 ID 顶掉旧的」语义，但前者还要循环、延迟、事件上报，后者只需要一次执行
 * 加一个取消句柄 —— 所以不复用同一个组件。
 *
 * 线程安全：注册表是 [MutableStateFlow] + CAS 整表替换 —— commonMain 里拿不到
 * `java.util.concurrent`，这是它在 common 下的等价物（与 `EventBus` 的订阅注册表、
 * `JobScheduler` 的任务表同一套做法）。
 *
 * ```
 * private val manualScans = KeyedJobRegistry()
 * fun scanNow(id: String) = manualScans.launch(scope, id) { scanObjects(id) }
 * fun cancelScan(id: String) { manualScans.cancel(id) }
 * ```
 */
class KeyedJobRegistry {

    /** key → 正在跑的协程。读侧拿到的是不可变快照。 */
    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    /** 当前登记中的 key（调试 / 测试用）。 */
    val activeKeys: Set<String> get() = jobs.value.keys

    /**
     * 为 [key] 启动一条协程（立刻开始执行），并取消同 key 上的上一条。
     *
     * 登记与启动的顺序是刻意的：先 `launch` 拿到 [Job]、再登记、再挂完成回调。
     * 协程可能在「launch」与「登记」之间就跑完 —— 此时完成回调会立即执行，而表里正是
     * 它自己，于是被摘掉，不会留下「已完成却没人清」的僵尸条目。
     *
     * @return 新启动的协程（调用方一般不需要持有，取消走 [cancel]）。
     */
    fun launch(scope: CoroutineScope, key: String, block: suspend CoroutineScope.() -> Unit): Job {
        require(key.isNotBlank()) { "key must not be blank: it is the unique id of this background job" }
        val job = scope.launch(block = block)
        val previous = swap(key, job)
        // remove(key, job) 只在「表里还是这一条」时删，不会误删同一 key 上后注册的新协程。
        job.invokeOnCompletion { removeIfCurrent(key, job) }
        previous?.cancel()
        return job
    }

    /**
     * 取消 [key] 上登记的协程，返回是否真的取消了。
     *
     * 只 `cancel()` 不 `join()`：阻塞中的 IO 调用（MQ / HTTP / JDBC）不会被立刻打断，
     * 等它返回毫无意义 —— 断开路径不能被一次慢查询拖住。取消生效后协程不会再开下一轮，
     * 也不再写缓存。
     */
    fun cancel(key: String): Boolean {
        val job = swap(key, null) ?: return false
        job.cancel()
        return true
    }

    /** 取消全部登记的协程（模块关闭 / 缓存清空等收尾场景）。 */
    fun cancelAll() {
        takeAll().forEach { it.cancel() }
    }

    /** 原子上换成 [job]（null = 摘除），返回被换下来的那条。 */
    private fun swap(key: String, job: Job?): Job? {
        while (true) {
            val current = jobs.value
            val previous = current[key]
            val updated = if (job == null) current - key else current + (key to job)
            if (jobs.compareAndSet(current, updated)) return previous
        }
    }

    /** 只在「表里还是 [job]」时摘除，避免误删同一 key 上更新的登记。 */
    private fun removeIfCurrent(key: String, job: Job) {
        while (true) {
            val current = jobs.value
            if (current[key] !== job) return
            if (jobs.compareAndSet(current, current - key)) return
        }
    }

    /** 原子地「取出并清空」整表。 */
    private fun takeAll(): List<Job> {
        while (true) {
            val current = jobs.value
            if (current.isEmpty()) return emptyList()
            if (jobs.compareAndSet(current, emptyMap())) return current.values.toList()
        }
    }
}
