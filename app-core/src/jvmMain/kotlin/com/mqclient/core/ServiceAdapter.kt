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
     * 模块初始化钩子：由 [ModuleRegistry.initializeServices] 在启动时调用，模块在这里
     * 注册自己的事件订阅（见 [ServiceContext]）。
     *
     * 默认空实现 —— 纯描述性的模块什么都不用写，新增模块也不需要修改启动代码。
     */
    fun initialize(context: ServiceContext) {}
}
