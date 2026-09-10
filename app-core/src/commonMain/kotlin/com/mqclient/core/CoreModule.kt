package com.mqclient.core

import com.mqclient.core.event.EventBus
import com.mqclient.core.event.EventBusErrorReporter
import com.mqclient.core.scheduler.JobScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

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

    /**
     * 创建事件总线与定时任务调度器（幂等：重复调用返回同一个实例，方便在没有 DI 容器
     * 的情况下被多个入口安全调用）。
     *
     * [scope] 的生命周期就是总线的生命周期 —— 应用退出时 [shutdown] 会取消它。
     */
    fun initialize(
        scope: CoroutineScope,
        errorReporter: EventBusErrorReporter = EventBusErrorReporter.Println,
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
