package com.mqclient.core.scheduler

import com.mqclient.core.event.EventBus
import com.mqclient.core.event.EventNames
import com.mqclient.core.log.Log
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 本组件的日志 tag。 */
private const val LOG_TAG = "job-scheduler"

/**
 * 轻量级定时任务调度器：各业务模块把自己的周期性任务注册进来，协程生命周期由这里统一管。
 *
 * ⚠️ **它不是任务队列**：任务之间不排队、不共享执行槽位，也没有「错过的轮次补跑」——
 * 每个任务自己一条固定延迟的循环，谁都不挡谁。要限并发 / 排队 / 失败重试，就在 action
 * 里自己解决（或者另起一个组件），本类不掺和；它只负责「隔多久跑一次」和「谁负责停掉它」。
 *
 * 任务的来源与归宿由**注册方**决定：调度器只认 id，不判断某个任务「该不该存在」。
 * 应用的用法是「连接出现 → 注册一组任务；连接消失 → 按前缀收掉」（见 `IbmMQModule`），
 * 退出时再兜两层：模块自己的 `shutdown`（由 `ModuleRegistry.disposeServices` 触发）
 * 按前缀取消，`CoreModule.shutdown` 最后 `cancelAll` 收尾。
 *
 * 设计要点：
 *  1. **一个任务一条协程**：任务之间互不影响。某个任务的 action 抛异常只会上报、
 *     不会终止它自己的循环，更不会波及其它任务；
 *  2. **不做任何线程切换**：任务跑在构造时传入的 [scope] 的调度器上。真正会阻塞的
 *     活儿（JDBC、MQ 客户端调用）应该由 action 自己 `withContext(...)` 切出去，
 *     调度器不替它猜；
 *  3. **固定延迟**：循环是「执行 → 等 [ScheduledJob.intervalMs] → 执行」，所以同一个
 *     任务不会和自己重叠。想要「不管跑多久都按点触发」的任务类型，本调度器不提供；
 *  4. **不持有业务状态**：唯一的内部状态是 id → 协程的注册表（为了取消和调试）；
 *  5. **事件驱动**：执行结果走 [EventBus] 广播（[EventNames.SCHEDULER_JOB_STARTED] /
 *     [EventNames.SCHEDULER_JOB_COMPLETED] / [EventNames.SCHEDULER_JOB_FAILED]，失败同时
 *     走统一错误口 [EventNames.SCHEDULER_ERROR]），调度器不认识任何订阅方，模块之间
 *     也不互相依赖。
 *
 * 线程安全：注册表是 [MutableStateFlow] + CAS 整表替换。commonMain 里拿不到
 * `java.util.concurrent`（`ConcurrentHashMap` 是 JVM 的），这是它在 common 下的等价物，
 * 与 [EventBus] 的订阅注册表是同一套做法。
 *
 * ```
 * val scheduler = JobScheduler(appScope, eventBus)
 * scheduler.schedule(
 *     ScheduledJob(id = "ibm-mq.$connId.scan.messages", intervalMs = 5_000, runOnStart = true) {
 *         service.scanMessageCounts(connId)
 *     },
 * )
 * ```
 */
class JobScheduler(
    private val scope: CoroutineScope,
    private val eventBus: EventBus,
) {

    /**
     * 任务 ID → 运行中的协程。
     *
     * 写侧通过 [MutableStateFlow.update] / `compareAndSet` 整表替换，读侧拿到的是不可变
     * 快照，遍历时不必担心并发修改。
     */
    private val registry = MutableStateFlow<Map<String, Job>>(emptyMap())

    /** 当前活动任务数。 */
    val activeJobCount: Int get() = registry.value.size

    // ---------------------------------------------------------------- 注册

    /**
     * 注册一个定时任务；**同一个 ID 会先取消旧的**（等价于「换一份新配置」，
     * 不会出现两条同 ID 的协程同时跑）。
     *
     * 非阻塞：登记完就返回，第一次执行按 [ScheduledJob.runOnStart] / `initialDelayMs`
     * 的规则稍后发生。
     */
    fun schedule(job: ScheduledJob) {
        cancel(job.id)

        // LAZY 起步：协程体不会在 schedule() 的调用线程上内联执行（runOnStart 的任务
        // 可能很重），但登记与启动的先后顺序仍然由下面两行显式控制。
        val launched = scope.launch(
            CoroutineName("JobScheduler:${job.id}"),
            start = CoroutineStart.LAZY,
        ) {
            val self = coroutineContext[Job]
            try {
                runLoop(job)
            } finally {
                // 只摘掉自己那一条：同 ID 的新任务可能已经登记进表了，
                // 无脑 remove 会把新任务的登记删掉，它就再也取消不掉了。
                registry.update { current -> if (current[job.id] === self) current - job.id else current }
            }
        }

        // 先登记、再启动：反过来的话，一个立即执行的任务可能在自己的 finally 里
        // 先摘表、后登记，于是表里留下一条已经跑完的僵尸记录。
        registry.update { current -> current + (job.id to launched) }
        launched.start()

        // scope 已经取消时协程体根本不会执行（也就没有 finally），上面那条登记会变成
        // 谁也清不掉的僵尸任务 —— 这里补一次身份校验主动摘掉。
        if (!launched.isActive) {
            registry.update { current -> if (current[job.id] === launched) current - job.id else current }
            Log.warn(LOG_TAG, "job '${job.id}' was not started: scope is no longer active")
        }
    }

    // ---------------------------------------------------------------- 取消

    /**
     * 取消单个任务。返回是否真的取消了（任务不存在或已被取消时返回 false，
     * 重复取消是安全的）。
     */
    fun cancel(jobId: String): Boolean {
        val matched = takeMatching { it == jobId }
        matched.forEach { (_, job) -> job.cancel() }
        return matched.isNotEmpty()
    }

    /**
     * 按前缀取消一组任务，返回取消的条数。
     *
     * 典型用法是「某条连接被删了，它的任务全停」：前缀用
     * `ScheduledJob.prefixFor("ibm-mq", connectionId)`（结尾带点，`ibm-mq.c1.` 不会
     * 误伤 `ibm-mq.c10.*`）。
     */
    fun cancelByPrefix(prefix: String): Int {
        val matched = takeMatching { it.startsWith(prefix) }
        matched.forEach { (_, job) -> job.cancel() }
        return matched.size
    }

    /** 取消所有任务（应用退出 / 模块销毁）。 */
    fun cancelAll() {
        takeMatching { true }.forEach { (_, job) -> job.cancel() }
    }

    // ---------------------------------------------------------------- 查询

    /** 当前活动任务 ID，按字典序（调试 / 测试；不是热路径，不必缓存）。 */
    fun getActiveJobs(): List<String> = registry.value.keys.sorted()

    // ---------------------------------------------------------------- 内部

    /**
     * 一个任务的完整循环：首次延迟 → [执行 → 等间隔] 往复。
     *
     * 只有取消才能结束它（action 的异常在 [runJobSafely] 里就被吃掉了）。
     */
    private suspend fun runLoop(job: ScheduledJob) {
        // 首次延迟的三取一规则（runOnStart 优先 > initialDelayMs > 等满一轮）见
        // ScheduledJob 的类注释；这里只负责把它落成一个毫秒数。
        val firstDelay = when {
            job.runOnStart -> 0L
            job.initialDelayMs > 0L -> job.initialDelayMs
            else -> job.intervalMs
        }
        if (firstDelay > 0L) delay(firstDelay)

        while (true) {
            runJobSafely(job)
            delay(job.intervalMs)
        }
    }

    /**
     * 跑一轮任务：异常就地吃掉并上报，所以**永远不会**把 [runLoop] 的循环打断。
     *
     * 骨架抄的是 [EventBus] 的 handler 投递：`CancellationException` 必须原样抛出
     * （它是取消的正常控制流，吞掉会让 `Job.cancel()` 失效），其余 `Throwable` 兜住。
     * 捕获 `Throwable` 而不是 `Exception` 是为了和总线保持一致 —— 一个任务抛出的
     * `StackOverflowError` 不该让整条循环消失。
     */
    private suspend fun runJobSafely(job: ScheduledJob) {
        eventBus.emit(EventNames.SCHEDULER_JOB_STARTED, mapOf(KEY_JOB_ID to job.id))

        val started = TimeSource.Monotonic.markNow()
        try {
            job.action()
            eventBus.emit(
                EventNames.SCHEDULER_JOB_COMPLETED,
                mapOf(
                    KEY_JOB_ID to job.id,
                    KEY_DURATION_MS to started.elapsedNow().inWholeMilliseconds,
                ),
            )
        } catch (cancellation: CancellationException) {
            // 被取消不是失败：不发失败事件，安静退出。
            throw cancellation
        } catch (error: Throwable) {
            val payload = mapOf(
                KEY_JOB_ID to job.id,
                KEY_MESSAGE to (error.message ?: error::class.simpleName ?: "unknown"),
                KEY_ERROR to (error::class.simpleName ?: "Throwable"),
            )
            // 两条都发：JOB_FAILED 是任务级的生命周期事件（和 JOB_STARTED / JOB_COMPLETED
            // 配对），SCHEDULER_ERROR 是调度器的统一错误上报口，给「只想挂一个总告警」
            // 的消费方用。只想留一条的话，删掉其中一行即可。
            eventBus.emit(EventNames.SCHEDULER_JOB_FAILED, payload)
            eventBus.emit(EventNames.SCHEDULER_ERROR, payload)
            Log.error(LOG_TAG, "job '${job.id}' failed: $error")
        }
    }

    /**
     * 原子地「取出并摘除」所有匹配的任务，返回摘到的 (id, 协程)。
     *
     * 取与摘必须是一次 CAS 完成的：先读快照再赋值空表的话，中间并发注册进来的任务会
     * 被那次赋值抹掉 —— 既不在表里、协程也没被取消，变成谁也收不掉的漏网任务。
     * [MutableStateFlow] 只保证单次读写的原子性，也没有 `getAndUpdate`，所以自己转 CAS 循环。
     *
     * 摘表与 `Job.cancel()` 分开做（先摘、后取消）：取消是异步的，摘完再取消能保证
     * 这段时间里别人看到的活动任务列表已经是准确的了。
     */
    private fun takeMatching(predicate: (String) -> Boolean): List<Pair<String, Job>> {
        while (true) {
            val current = registry.value
            val matched = current.filterKeys(predicate)
            if (matched.isEmpty()) return emptyList()
            if (registry.compareAndSet(current, current - matched.keys)) return matched.toList()
        }
    }

    private companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_DURATION_MS = "durationMs"
        const val KEY_MESSAGE = "message"
        const val KEY_ERROR = "error"
    }
}
