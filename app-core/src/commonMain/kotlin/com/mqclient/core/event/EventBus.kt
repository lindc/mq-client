package com.mqclient.core.event

import com.mqclient.core.log.Log
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** EventBus 组件的日志 tag。 */
private const val LOG_TAG = "event-bus"

/** 订阅的匹配方式；[EventBus] 的四个订阅入口各对应一种。 */
enum class EventMatchMode { EXACT, PREFIX, SUFFIX, ALL }

/**
 * 一条订阅记录（匹配规则 + 处理器），由 [EventBus] 创建并持有。
 *
 * 只做匹配和描述，不持有任何业务状态 —— 事件总线本身只是转发者。
 * 故意不做成 data class：它的身份就是对象本身，同一个处理器订阅两次是两条独立
 * 订阅，取消其中一条不该影响另一条。
 */
class EventSubscription internal constructor(
    /** 匹配用的字面量；[EventMatchMode.ALL] 时无意义。 */
    val pattern: String,
    val mode: EventMatchMode,
    internal val handler: suspend (AppEvent) -> Unit,
) {
    /**
     * 收集协程，由 [EventBus] 在订阅时写入；[EventBus.cancelAll] 靠它取消。
     *
     * 必须 `@Volatile`：写发生在订阅线程、读可能发生在调用 [EventBus.cancelAll] 的
     * 线程（应用退出 / 测试收尾），没有 happens-before 关系时读到 null 会让那条
     * 订阅永远取消不掉。
     */
    @Volatile
    internal var job: Job? = null

    /** 事件名是否命中本条订阅。 */
    fun matches(eventName: String): Boolean = when (mode) {
        EventMatchMode.EXACT -> eventName == pattern
        EventMatchMode.PREFIX -> eventName.startsWith(pattern)
        EventMatchMode.SUFFIX -> eventName.endsWith(pattern)
        EventMatchMode.ALL -> true
    }

    /** `ibm-mq.scan.started` / `ibm-mq.*` / `*.scan.completed` / `*`，用于日志。 */
    override fun toString(): String = when (mode) {
        EventMatchMode.EXACT -> pattern
        EventMatchMode.PREFIX -> "$pattern*"
        EventMatchMode.SUFFIX -> "*$pattern"
        EventMatchMode.ALL -> "*"
    }
}

/**
 * 处理器异常的上报口。
 *
 * 默认实现走统一日志（[Log]，tag = `event-bus`）：落点由 Log 的平台实现决定
 * （控制台 / 将来的文件）；要换格式或落点，在
 * `CoreModule.initialize(scope, errorReporter = …)` 里替换即可。
 *
 * ⚠️ 上报口**不要**改成「往同一条总线发错误事件」：handler 抛错 → 发事件 → 该事件的
 * 订阅者又抛错 → 又触发上报…… 会变成无界的事件风暴（[emit] 是异步的，栈上检测不到）。
 * 需要让用户看见，走 `CoreModule.lastError` 这种**状态**通道，不要走事件。
 */
fun interface EventBusErrorReporter {
    fun onHandlerError(event: AppEvent, subscription: EventSubscription, error: Throwable)

    companion object {
        /** 默认实现：经统一日志记录事件名 + 订阅规则 + 异常，绝不抛出。 */
        val Logging: EventBusErrorReporter = EventBusErrorReporter { event, subscription, error ->
            Log.error(LOG_TAG, "handler failed: event=${event.name} subscription=$subscription error=$error")
        }
    }
}

/**
 * 应用级事件总线：字符串事件名 + 字符串匹配（精确 / 前缀 / 后缀 / 通配）订阅。
 *
 * 设计要点：
 *  1. **零注册**：事件只有名字，总线不知道有哪些事件、哪些模块，新增模块和事件都
 *     不需要改这里；
 *  2. **每个订阅一个收集协程**：handler 之间互不阻塞（一个卡住不影响别人），
 *     单个 handler 的异常被就地吃掉（只会上报给 [EventBusErrorReporter]），
 *     而同一条订阅的多次回调严格按事件顺序**串行**执行，不会并发重入；
 *  3. **不丢事件**：`extraBufferCapacity = 64` + [BufferOverflow.SUSPEND]，慢订阅者
 *     最多让发送方落后 64 条，再往上发送方挂起等待 —— 所以 [emitSync] 一条不丢。
 *     [emit] 因为承诺不挂起，缓冲满时改走有界的降级队列（单条转发协程、FIFO），
 *     只有「缓冲 + 队列」都满才会丢，并计入 [droppedEventCount]；
 *  4. **不用 GlobalScope**：总线只借用构造时传入的 scope，订阅默认也跑在这个 scope
 *     上；模块传入自己的 scope 就等于用自己的生命周期管住了订阅；
 *  5. **不持有业务状态**：唯一的内部状态是订阅注册表（为了取消和调试）。
 *
 * ⚠️ 语义边界：`replay = 0`，所以**没有任何在收集的订阅者**时发出的事件会被
 * SharedFlow 直接丢弃 —— 这是 replay=0 的定义，背压救不了（背压只作用于已经
 * 开始收集的订阅者）。启动阶段请在模块注册完订阅后用 [awaitSubscribers] 等一下，
 * 再广播 `core.app.started`。
 */
class EventBus(
    private val scope: CoroutineScope,
    private val errorReporter: EventBusErrorReporter = EventBusErrorReporter.Logging,
) {
    /**
     * replay=0：新订阅者不会收到订阅之前的事件（事件语义，不是状态）。
     * 想给 UI 做「当前状态」用 StateFlow，不要用事件总线。
     */
    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * 降级队列：快路径 `tryEmit` 塞不进去时，事件先落到这里，由唯一的转发协程
     * 按顺序搬进 [_events]。
     *
     * 为什么是「一条队列 + 一条协程」，而不是「每次塞不进去就 `scope.launch` 一条
     * 协程去挂起入队」：慢订阅者持续存在时，`tryEmit` 会持续失败，后者**每发一条
     * 事件就多一条协程**，而且它们全部挂在 `_events.emit` 上等同一个订阅者 ——
     * 协程数无上限增长（直到拖垮进程），彼此之间还不保序。换成队列之后：协程数
     * 恒为 1、队列内严格 FIFO、积压量有上限。
     *
     * 容量有上限是刻意的：[emit] 承诺不挂起、不阻塞调用方，背压只能往「丢」这个
     * 方向泄，不能无限攒。队列满时丢最新的一条并计入 [droppedEventCount]。
     */
    private val pending = Channel<AppEvent>(OVERFLOW_QUEUE_CAPACITY)

    /**
     * 还欠着没进 [_events] 的事件条数：即 [pending] 里的，加上转发协程手上正握着的
     * 那一条（它取出来了、但还没 `emit` 成功）。
     *
     * 用途是给 [emit] 的快路径把门：**它大于 0 时新事件必须跟着排队**，不能走
     * `tryEmit` 插到前面去，否则先来的还在排队、后来的先被投递。
     *
     * 计数点刻意不对称 —— 入队在 `trySend` **之前** +1，出队在 `_events.emit`
     * **返回之后** -1。所以「转发协程刚取走最后一条、还没发出去」这段时间里计数
     * 仍然是 1，快路径依然关着；只看队列本身是否为空会漏掉这个窗口。
     */
    private val backlog = MutableStateFlow(0)

    /**
     * 唯一的转发协程：把 [pending] 里的事件按顺序搬进 [_events]。
     *
     * 必须声明在 `pending` 之后 —— 属性和 init 块按声明顺序初始化，反了会读到
     * 还没初始化的队列。
     *
     * 构造期就启动，而不是用 LAZY 等到第一次溢出再 `start()`：那样一来并发的溢出
     * 会各自去 start，还得额外处理「scope 已取消时 start 返回 false」的分支。常驻
     * 一条只占一个空闲协程，换来的是没有启动竞态。
     *
     * 它只 `emit` 不 `collect`，因此不计入 `_events.subscriptionCount`，
     * [awaitSubscribers] 的期望值不受影响。
     */
    init {
        scope.launch(CoroutineName("EventBus:overflow-drain")) {
            for (event in pending) {
                _events.emit(event)
                backlog.update { it - 1 } // 见 backlog 的注释：必须在 emit 返回之后
            }
        }
    }

    /**
     * 订阅注册表：写侧 CAS 整表替换（[MutableStateFlow.update]），读侧拿到的是
     * 不可变快照，所以遍历时不用担心并发修改。
     *
     * commonMain 里拿不到 `java.util.concurrent`（CopyOnWriteArrayList 是 JVM 的），
     * 这是它在 common 下的等价物 —— 语义就是写时复制列表。
     */
    private val registry = MutableStateFlow<List<EventSubscription>>(emptyList())

    /** 当前注册的订阅，按注册顺序；调试 / 监控用。 */
    val activeSubscriptions: StateFlow<List<EventSubscription>> = registry.asStateFlow()

    /** 已注册的订阅条数。 */
    val subscriptionCount: Int get() = registry.value.size

    /** 被 [emit] 丢弃的事件条数（[emitSync] 不会丢，不计入这里）。 */
    private val droppedEvents = MutableStateFlow(0L)

    /**
     * 因「缓冲区 + 降级队列都满」而被丢弃的事件总数。
     *
     * 正常运行时恒为 0；它开始涨就说明有 handler 卡住了或消费不过来，不该当作噪声
     * 忽略。丢的时候还会打一行 WARN（含事件名），所以日志里看得到是从哪个事件开始
     * 顶不住的。
     */
    val droppedEventCount: Long get() = droppedEvents.value

    // ---------------------------------------------------------------- 发送

    /**
     * 异步发送：不挂起、不阻塞调用方，也不返回投递结果。
     *
     * `tryEmit` 是同步的，所以同一个调用线程里连续 [emit] 的顺序是保真的。缓冲区被
     * 填满（有订阅者落后 [BUFFER_CAPACITY] 条以上）时事件转进降级队列 [pending]，
     * 由单条转发协程按 FIFO 补发 —— 队列内的相对顺序同样是保真的。
     *
     * ⚠️ 队列也满时丢**最新**的一条：计入 [droppedEventCount]，并打一行 WARN。走到
     * 这一步说明有订阅者长时间不消费，继续攒下去只会把内存吃光 —— [emit] 不能挂起，
     * 背压没有别的地方可去。真的一条都不能丢的场合（启动广播、测试里的确定顺序）用
     * [emitSync]，它挂起直到事件进入缓冲区。
     *
     * ⚠️ 转发协程借用总线的 scope，所以**总线 scope 已取消时（退出途中）这次 emit
     * 不会被投递**。
     */
    fun emit(name: String, payload: Map<String, Any?> = emptyMap(), source: String? = null) {
        emit(AppEvent(name = name, payload = payload, source = source))
    }

    /** [emit] 的事件对象重载（转发 / 重新发布已有事件时用）。 */
    fun emit(event: AppEvent) {
        // 有积压就跟着排队：否则先来的还在队列里、后来的却可能插进缓冲区先被投递。
        // backlog 的计数点保证了「转发协程刚取走一条还没发出去」也算积压。
        if (backlog.value == 0 && _events.tryEmit(event)) return

        backlog.update { it + 1 }
        if (pending.trySend(event).isSuccess) return

        // 队列也满了：这条发不出去，计数要还回去，否则快路径会永久关闭。
        backlog.update { it - 1 }
        droppedEvents.update { it + 1 }
        Log.warn(
            LOG_TAG,
            "buffer overflow: dropped '${event.name}' (total dropped: ${droppedEvents.value})",
        )
    }

    /**
     * 同步发送：挂起直到事件进入缓冲区（缓冲区满则等订阅者追上来）。
     *
     * 用于需要「确认已入队」的场合 —— 启动阶段广播 `core.app.started`、或者测试里
     * 需要确定的事件顺序。注意它确认的是**入队**，不是「所有 handler 都跑完了」。
     */
    suspend fun emitSync(name: String, payload: Map<String, Any?> = emptyMap(), source: String? = null) {
        emitSync(AppEvent(name = name, payload = payload, source = source))
    }

    /** [emitSync] 的事件对象重载。 */
    suspend fun emitSync(event: AppEvent) {
        _events.emit(event)
    }

    // ---------------------------------------------------------------- 订阅

    /** 精确订阅：只处理名字完全相同的事件。返回的 [Job] 即这条订阅的生命周期。 */
    fun on(
        eventName: String,
        scope: CoroutineScope = this.scope,
        handler: suspend (AppEvent) -> Unit,
    ): Job = subscribe(EventSubscription(eventName, EventMatchMode.EXACT, handler), scope)

    /**
     * 前缀订阅：处理以 [prefix] 开头的事件。
     *
     * 模块「自己发、自己收」的常规姿势：`onPrefix("ibm-mq.")` 只收自己模块的事件。
     */
    fun onPrefix(
        prefix: String,
        scope: CoroutineScope = this.scope,
        handler: suspend (AppEvent) -> Unit,
    ): Job = subscribe(EventSubscription(prefix, EventMatchMode.PREFIX, handler), scope)

    /**
     * 后缀订阅：处理以 [suffix] 结尾的事件。
     *
     * 例：`onSuffix(".scan.completed")` 横跨所有模块监听「扫描完成」。
     */
    fun onSuffix(
        suffix: String,
        scope: CoroutineScope = this.scope,
        handler: suspend (AppEvent) -> Unit,
    ): Job = subscribe(EventSubscription(suffix, EventMatchMode.SUFFIX, handler), scope)

    /** 通配订阅：处理所有事件（日志 / 监控 / 调试）。 */
    fun onAll(
        scope: CoroutineScope = this.scope,
        handler: suspend (AppEvent) -> Unit,
    ): Job = subscribe(EventSubscription("*", EventMatchMode.ALL, handler), scope)

    /**
     * 等所有已注册的订阅真正开始收集事件，默认最多等 [timeoutMillis] 毫秒。
     *
     * `replay = 0` 时，订阅从 `launch` 到真正进入 `collect` 之间有一小段窗口，
     * 这期间发出的事件它收不到。启动阶段的「先注册、后广播」就靠它兜住：返回 true
     * 之后 [emitSync] 的事件一定能落到所有订阅者手里。
     *
     * 期望值取自调用瞬间的注册表快照，所以等待期间若有订阅被取消（计数回落），
     * 可能等不到而超时返回 false —— 调用方按 false 处理即可，不要假设它一定是 true。
     */
    suspend fun awaitSubscribers(timeoutMillis: Long = 2_000L): Boolean {
        val expected = registry.value.size
        if (expected == 0) return true
        return withTimeoutOrNull(timeoutMillis) {
            _events.subscriptionCount.first { it >= expected }
        } != null
    }

    /**
     * 取消所有注册的订阅（应用退出、测试收尾）。
     *
     * 取走和清空必须是原子的：先读一张快照再赋值空表，中间并发注册进来的订阅会被
     * 那次赋值抹掉 —— 它们既不在注册表里、job 也没被取消，变成谁也收不掉的漏网
     * 协程。所以这里用 CAS 把两步合成一步（[takeAllSubscriptions]）。
     */
    fun cancelAll() {
        val all = takeAllSubscriptions()
        all.forEach { it.job?.cancel() }
    }

    /**
     * 原子地「取出并清空」注册表。
     *
     * [MutableStateFlow] 只保证了单次读写的原子性，所以这里用 CAS 循环把两步合成
     * 一步；commonMain 里也没有 `getAndUpdate` 可用。
     */
    private fun takeAllSubscriptions(): List<EventSubscription> {
        while (true) {
            val current = registry.value
            if (registry.compareAndSet(current, emptyList())) return current
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun subscribe(subscription: EventSubscription, subscriberScope: CoroutineScope): Job {
        // UNDISPATCHED：协程体在当前调用栈上一直执行到第一个挂起点（也就是进入
        // collect 之前），所以「写 job + 注册」跟调用方是同一帧完成的 —— 紧接着
        // 调 awaitSubscribers 也能看到这条订阅。
        //
        // 同时它带 ATOMIC 语义：即使 scope 已经取消，协程体也一定执行，finally 也
        // 一定跑到。换成 DEFAULT 或 LAZY 的话，已取消 scope 上的协程体根本不执行
        // （实测如此），注册表里就留下一条永远不会开始收集、也永远等不到 finally
        // 摘除的「幻影订阅」—— 它让 awaitSubscribers 的期望值永远达不到，每次启动
        // 白等 2s，而 APP_STARTED 在 replay=0 下照旧被丢掉。
        //
        // replay=0 意味着新订阅者不会拿到任何积压事件，所以 collect 会立刻挂起：
        // 这段同步执行的代码只有两次 StateFlow 更新，不会在调用线程上跑 handler。
        return subscriberScope.launch(
            CoroutineName("EventBus:$subscription"),
            start = CoroutineStart.UNDISPATCHED,
        ) {
            // job 先就位再注册：反过来写的话，并发的 cancelAll 可能拿到一条
            // job 仍是 null 的订阅，那条就取消不掉了。
            subscription.job = coroutineContext[Job]
            registry.update { it + subscription }
            try {
                _events.collect { event ->
                    if (subscription.matches(event.name)) deliver(subscription, event)
                }
            } finally {
                registry.update { it - subscription }
            }
        }
    }

    /**
     * 单个 handler 的调用点：异常只影响这一个订阅。
     *
     * CancellationException 必须原样抛出（它是取消的正常控制流，吞掉会让
     * `Job.cancel()` 失效）；其余 Throwable 就地吃掉并上报，订阅协程正常继续跑，
     * 其他订阅者更是完全不受影响。
     */
    private suspend fun deliver(subscription: EventSubscription, event: AppEvent) {
        try {
            subscription.handler(event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            try {
                errorReporter.onHandlerError(event, subscription, error)
            } catch (reporterError: Throwable) {
                // 上报口自己坏了也不能连累订阅。
                Log.error(LOG_TAG, "error reporter failed: $reporterError (original: $error)")
            }
        }
    }

    // 两个容量做成 internal 而不是 private：降级路径的「有界」是行为契约的一部分，
    // 测试要按它算准丢包条数，写死数字会在改容量时静默失配。
    internal companion object {
        /** 缓冲区容量：与需求一致（64 条），写满了就背压而不是丢事件。 */
        internal const val BUFFER_CAPACITY = 64

        /**
         * 降级队列容量：缓冲区之外还能再攒这么多条才丢。
         *
         * 取缓冲区 4 倍是为了吃掉「订阅者偶发卡顿」这种短时抖动 —— 那种情况不该丢
         * 事件。真到了这里还满，说明是持续不消费，丢掉才是正确答案。
         */
        internal const val OVERFLOW_QUEUE_CAPACITY = 256
    }
}
