package com.mqclient.core

import com.mqclient.core.event.EventBus
import com.mqclient.core.event.EventBusErrorReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * 核心模块：应用启动时创建事件总线，并把它交给所有业务模块。
 *
 * 主程序入口是唯一调用 [initialize] 的地方：
 *
 * ```
 * val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("app"))
 * CoreModule.initialize(appScope)
 * ModuleRegistry.initializeServices(CoreModule.eventBus, appScope)
 * ```
 *
 * 之后任何模块要么拿到注入的 `ServiceContext`，要么读 [eventBus]。
 *
 * 刻意不用 `lateinit var`：未初始化就访问时给一句能看懂的报错，而不是 lateinit 的
 * "property … has not been initialized"。
 */
object CoreModule {

    private var bus: EventBus? = null
    private var appScope: CoroutineScope? = null

    /** 应用级事件总线。在 [initialize] 之前访问会抛 [IllegalStateException]。 */
    val eventBus: EventBus
        get() = bus ?: error("CoreModule.initialize(scope) 必须先于 CoreModule.eventBus 调用")

    /** 应用级 scope —— 所有模块 scope 的父节点。 */
    val scope: CoroutineScope
        get() = appScope ?: error("CoreModule.initialize(scope) 必须先于 CoreModule.scope 调用")

    /** 是否已经初始化过。 */
    val isInitialized: Boolean get() = bus != null

    /**
     * 创建事件总线（幂等：重复调用返回同一个实例，方便在没有 DI 容器的情况下被
     * 多个入口安全调用）。
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
        return created
    }

    /**
     * 应用退出：取消所有事件订阅并取消应用 scope（会级联到各模块 scope 与模块内部
     * 协程）。之后 [isInitialized] 回到 false，可以重新 [initialize]（测试里有用）。
     */
    fun shutdown() {
        bus?.cancelAll()
        appScope?.cancel()
        bus = null
        appScope = null
    }
}
