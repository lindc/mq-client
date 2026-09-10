package com.mqclient.core

import com.mqclient.core.event.EventBus
import com.mqclient.core.spi.ServiceContext
import java.util.ServiceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * Loads all business modules registered through Java SPI at startup.
 *
 * Because every `*-service` / `*-ui` module is on the application classpath,
 * [java.util.ServiceLoader] finds their `META-INF/services` registrations
 * without any manual wiring.
 */
object ModuleRegistry {
    val services: List<ServiceAdapter> by lazy { loadAll(ServiceAdapter::class.java) }

    val uis: List<BusinessUi> by lazy { loadAll(BusinessUi::class.java) }

    /**
     * 逐个初始化已发现的 service 模块：给每个模块一个自己的 scope 与 [ServiceContext]，
     * 让它在 [ServiceAdapter.initialize] 里注册自己的事件订阅。
     *
     * 容错策略与 [loadAll] 一致：单个模块初始化失败只跳过它自己（并取消它的 scope，
     * 释放已启动的订阅协程），其余模块照常启动 —— 一个写坏的模块不该拖垮整个应用。
     */
    fun initializeServices(eventBus: EventBus, parentScope: CoroutineScope): List<ServiceAdapter> =
        services.onEach { adapter ->
            val context = ServiceContext.of(adapter.id, eventBus, parentScope)
            runCatching { adapter.initialize(context) }
                .onFailure {
                    System.err.println("[ModuleRegistry] cannot initialize module '${adapter.id}': $it")
                    context.coroutineScope.cancel()
                }
        }

    /**
     * Loads providers one by one.
     *
     * [ServiceLoader] aborts the whole iteration the moment a single provider
     * fails (missing class, no public no-arg constructor, throwing `init`, …),
     * which used to cost us every other module. Here a broken module is skipped
     * and reported instead, so the rest of the app still comes up.
     */
    private fun <T : Any> loadAll(type: Class<T>): List<T> = buildList {
        val providers = ServiceLoader.load(type).iterator()
        while (providers.hasNext()) {
            runCatching { providers.next() }
                .onFailure {
                    // ServiceConfigurationError is an Error, not an Exception.
                    System.err.println("[ModuleRegistry] cannot load ${type.simpleName} provider: $it")
                }
                .onSuccess(::add)
        }
    }
}
