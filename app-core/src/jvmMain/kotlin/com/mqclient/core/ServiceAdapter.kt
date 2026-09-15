package com.mqclient.core

import com.mqclient.core.spi.ServiceContext

/**
 * Pure (non-UI) descriptor of a business module, discovered automatically via
 * Java SPI ([java.util.ServiceLoader]). Each `*-service` module provides one
 * implementation and registers it under `META-INF/services/com.mqclient.core.ServiceAdapter`.
 */
interface ServiceAdapter {
    /** Stable identifier, e.g. "ibm-mq". */
    val id: String

    /** Human readable name shown in the UI. */
    val name: String

    /** Short description of what the module does. */
    val description: String

    /**
     * Wire protocol the module speaks, e.g. "AMQP 0.9.1" or "Kafka Protocol".
     * Surfaced in the About dialog so users know which transports are bundled
     * without the UI needing to know about individual service modules.
     */
    val protocol: String

    /**
     * 类目名，新建连接对话框按它把各模块分到不同的 tab（如 `"MQ"`、`"AI"`）。
     *
     * 各模块**自己持有字符串常量** —— app-core 不集中定义取值，新增类目不需要改
     * 这里；tab 的生成完全来自 SPI 扫描（见 [ModuleRegistry] 与对话框侧的
     * `groupByCategory`）。
     */
    val category: String

    /**
     * 模块初始化钩子：由 [ModuleRegistry.initializeServices] 在启动时调用，模块在这里
     * 注册自己的事件订阅（见 [ServiceContext]）。
     *
     * 默认空实现 —— 纯描述性的模块什么都不用写，新增模块也不需要修改启动代码。
     */
    fun initialize(context: ServiceContext) {}

    /**
     * 模块销毁钩子：由 [ModuleRegistry.disposeServices] 在应用退出时**按初始化逆序**调用，
     * 模块在这里释放自己持有的资源：真实连接 / 会话 / 消费者 / 订阅 / 定时任务。
     *
     * 调用点在 `CoreModule.shutdown()` 与 `DatabaseManager.close()` **之前**（见 app-ui 的
     * `Main.kt` closeApp）：这时总线与调度器还活着，dispose 里发事件、用模块 scope 都还成立。
     *
     * 契约：
     *  - 默认空实现 —— 纯描述性的模块不用写；
     *  - 必须**幂等**，且能容忍「[initialize] 没跑过或跑了一半」：失败过的模块正是需要
     *    收尾的那个，销毁路径照样会调它；
     *  - 抛出的异常只影响它自己（[ModuleRegistry.disposeServices] 逐个兜住），
     *    但不要依赖这一点 —— 没关掉的资源就是泄漏。
     */
    fun dispose() {}
}
