package com.mqclient.core.spi

import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.EventBus
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * 每个业务模块拿到的运行时上下文：一条事件总线 + 一个属于它自己的 scope。
 *
 * 模块只依赖这个接口，不依赖 `CoreModule` 单例，也不依赖启动代码 —— 新增模块时
 * 只要实现 `ServiceAdapter.initialize(context)`（SPI 自动发现）并在这里注册自己的
 * 订阅即可，没有任何全局注册表要改。
 *
 * 约定：[moduleId] 必须等于 `ServiceAdapter.id`，它同时是事件名的第一段
 * （`ibm-mq` → `ibm-mq.scan.*`），所以「自己模块的事件」可以用前缀精确圈出来。
 */
interface ServiceContext {

    /** 模块 id，如 `ibm-mq`。 */
    val moduleId: String

    /** 应用级事件总线（全进程一个实例）。 */
    val eventBus: EventBus

    /** 模块自己的 scope：随应用退出级联取消，模块内部协程崩了不牵连别的模块。 */
    val coroutineScope: CoroutineScope

    companion object {
        /**
         * 用父 scope 建一个模块上下文（[coroutineScope] 由 [moduleScope] 派生）。
         * 生产代码里由 `ModuleRegistry` 在 SPI 初始化时调用。
         */
        fun of(moduleId: String, eventBus: EventBus, parentScope: CoroutineScope): ServiceContext =
            DefaultServiceContext(moduleId, eventBus, moduleScope(parentScope, moduleId))

        /**
         * 模块 scope：父 Job 是 [parentScope]（应用退出时级联取消），自己带
         * [SupervisorJob]（模块内部某个协程失败不会弄死兄弟模块），并挂上
         * `module:<id>` 的协程名，方便调试时看清是哪来的协程。
         */
        fun moduleScope(parentScope: CoroutineScope, moduleId: String): CoroutineScope {
            val parent = parentScope.coroutineContext[Job]
            val job = if (parent != null) SupervisorJob(parent) else SupervisorJob()
            return CoroutineScope(parentScope.coroutineContext + job + CoroutineName("module:$moduleId"))
        }
    }
}

private class DefaultServiceContext(
    override val moduleId: String,
    override val eventBus: EventBus,
    override val coroutineScope: CoroutineScope,
) : ServiceContext

/** payload 里统一约定的「来源模块」字段名。 */
const val MODULE_ID_KEY: String = "moduleId"

/**
 * 发本模块的事件：自动把 [ServiceContext.moduleId] 补进 payload（payload 里已经
 * 有 [MODULE_ID_KEY] 时不覆盖）并写进 [AppEvent.source]。
 *
 * 于是跨模块订阅方（UI、日志）永远能从 `event.getString(MODULE_ID_KEY)` 读到来源，
 * 而各模块不需要每次都手写这个字段。
 */
fun ServiceContext.emit(name: String, payload: Map<String, Any?> = emptyMap()) {
    eventBus.emit(name, withModuleId(payload), source = moduleId)
}

/** [emit] 的挂起版：确认事件已入队（见 [EventBus.emitSync]）。 */
suspend fun ServiceContext.emitSync(name: String, payload: Map<String, Any?> = emptyMap()) {
    eventBus.emitSync(name, withModuleId(payload), source = moduleId)
}

/**
 * 只订阅**本模块**的事件，等价于 `eventBus.onPrefix("$moduleId.", coroutineScope, …)`。
 *
 * 返回的 [Job] 就是这条订阅的生命周期：模块销毁时 `cancel()` 它即可。
 */
fun ServiceContext.onOwnEvents(handler: suspend (AppEvent) -> Unit): Job =
    eventBus.onPrefix("$moduleId.", coroutineScope, handler)

/** 精确订阅某个事件（含别的模块 / 核心事件），订阅挂在模块 scope 上。 */
fun ServiceContext.onEvent(eventName: String, handler: suspend (AppEvent) -> Unit): Job =
    eventBus.on(eventName, coroutineScope, handler)

/** 按后缀订阅（如 `.scan.completed`），订阅挂在模块 scope 上。 */
fun ServiceContext.onEventSuffix(suffix: String, handler: suspend (AppEvent) -> Unit): Job =
    eventBus.onSuffix(suffix, coroutineScope, handler)

private fun ServiceContext.withModuleId(payload: Map<String, Any?>): Map<String, Any?> =
    if (payload.containsKey(MODULE_ID_KEY)) payload else payload + (MODULE_ID_KEY to moduleId)
