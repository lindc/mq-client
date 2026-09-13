package com.mqclient.core

import com.mqclient.core.event.EventBus
import com.mqclient.core.event.EventBusErrorReporter
import com.mqclient.core.event.EventNames
import com.mqclient.core.log.Log
import com.mqclient.core.scheduler.JobScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 核心模块：应用启动时创建事件总线与定时任务调度器，并把它们交给所有业务模块。
 *
 * 主程序入口是唯一调用 [initialize] 的地方：
 *
 * ```
 * val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("app"))
 * CoreModule.initialize(appScope)
 * ModuleRegistry.initializeServices(CoreModule.eventBus, appScope, CoreModule.jobScheduler)
 * ```
 *
 * 之后任何模块要么拿到注入的 `ServiceContext`，要么读 [eventBus] / [jobScheduler]。
 *
 * 刻意不用 `lateinit var`：未初始化就访问时给一句能看懂的报错，而不是 lateinit 的
 * "property … has not been initialized"。
 */
object CoreModule {

    private var bus: EventBus? = null
    private var appScope: CoroutineScope? = null
    private var scheduler: JobScheduler? = null

    /** 应用级事件总线。在 [initialize] 之前访问会抛 [IllegalStateException]。 */
    val eventBus: EventBus
        get() = bus ?: error("CoreModule.initialize(scope) 必须先于 CoreModule.eventBus 调用")

    /**
     * [eventBus] 的「没有就给 null」版本：调用点只是**顺带**广播时用它 ——
     * 「先问 [isInitialized] 再取」中间隔着一次并发 [shutdown] 的话，
     * 第二次读取会抛异常；这里的读写只有一次。
     */
    val eventBusOrNull: EventBus? get() = bus

    /** 应用级 scope —— 所有模块 scope 的父节点。 */
    val scope: CoroutineScope
        get() = appScope ?: error("CoreModule.initialize(scope) 必须先于 CoreModule.scope 调用")

    /**
     * 应用级定时任务调度器 —— 全进程一个实例，所有模块的任务都登记在它这里，
     * [shutdown] 时统一取消。这样「谁在跑什么」有一个总账，见 [JobScheduler.getActiveJobs]。
     */
    val jobScheduler: JobScheduler
        get() = scheduler ?: error("CoreModule.initialize(scope) 必须先于 CoreModule.jobScheduler 调用")

    /** 是否已经初始化过。 */
    val isInitialized: Boolean get() = bus != null

    private val _lastError = MutableStateFlow<AppError?>(null)

    /**
     * 最近一条**用户可见**的错误（由 [reportError] 写入），界面订阅它来提示用户。
     *
     * 用 StateFlow 而不是只发事件：事件总线的 `replay = 0`，启动阶段（模块初始化、
     * 读配置）发出的错误事件会早于 UI 订阅、被直接丢掉；状态则随时读得到当前值。
     */
    val lastError: StateFlow<AppError?> get() = _lastError.asStateFlow()

    /** 界面提示完调它清掉当前错误（[lastError] 回到 null）。 */
    fun clearLastError() {
        _lastError.value = null
    }

    /**
     * 上报一条「用户应该看得见」的错误：写进 [lastError]，并发 [EventNames.ERROR_OCCURRED]
     * 事件（日志 / 监控 / 模块订阅用）。总线还没建时退回统一日志（[Log]）—— 打包成桌面程序之后
     * 控制台不可见，所以报错必须有一条能走到界面上的路。
     *
     * 供 app-core 内部拿不到总线引用的对象（[ConfigStore] / [ConnectionStore]）和 UI 层
     * 使用；能拿到 `ServiceContext` 的模块优先用自己的 `eventBus`。
     */
    fun reportError(message: String, source: String = "core", error: Throwable? = null) {
        val reported = AppError(
            message = message,
            source = source,
            cause = error?.let { it::class.simpleName },
        )
        _lastError.value = reported

        val bus = bus
        if (bus == null) {
            Log.error(reported.source, reported.message + (reported.cause?.let { " ($it)" } ?: ""))
            return
        }
        bus.emit(
            name = EventNames.ERROR_OCCURRED,
            payload = mapOf(
                "message" to reported.message,
                "moduleId" to reported.source,
                "error" to reported.cause,
            ),
            source = reported.source,
        )
    }

    /**
     * 创建事件总线与定时任务调度器（幂等：重复调用返回同一个实例，方便在没有 DI 容器
     * 的情况下被多个入口安全调用）。
     *
     * [scope] 的生命周期就是总线的生命周期 —— 应用退出时 [shutdown] 会取消它。
     */
    fun initialize(
        scope: CoroutineScope,
        errorReporter: EventBusErrorReporter = EventBusErrorReporter.Logging,
    ): EventBus {
        bus?.let { return it }
        val created = EventBus(scope, errorReporter)
        appScope = scope
        bus = created
        scheduler = JobScheduler(scope, created)
        return created
    }

    /**
     * 应用退出：先停掉所有定时任务，再取消事件订阅与应用 scope（会级联到各模块 scope
     * 与模块内部协程）。之后 [isInitialized] 回到 false，可以重新 [initialize]
     * （测试里有用）。
     *
     * 顺序有讲究：任务执行时会往总线上发事件，先取消订阅的话这些事件就发到空气里了；
     * 反过来先停任务，退出过程中不会再有人往总线上投递。
     *
     * 模块自己的资源（连接 / 会话 / 各模块的定时任务与订阅）不归这里管：调用点要**先**调
     * `ModuleRegistry.disposeServices()`（见 app-ui 的 Main.kt closeApp），本方法排在它后面。
     */
    fun shutdown() {
        scheduler?.cancelAll()
        scheduler = null
        bus?.cancelAll()
        appScope?.cancel()
        bus = null
        appScope = null
    }
}

/**
 * 一条用户可见的错误。
 *
 * [source] 是出错的对象 / 模块（`core.config`、`ibm-mq`、`app-ui`…），[cause] 只留
 * 异常类名不留堆栈 —— 界面展示的是「出了什么事」，堆栈属于日志。
 *
 * 刻意不带时间戳：同一个错误重复上报时值相等，[CoreModule.lastError] 这个 StateFlow
 * 不会重复发射（否则在组合期间被触发的上报会把界面推进重组循环）。
 */
data class AppError(
    val message: String,
    val source: String,
    val cause: String? = null,
)
