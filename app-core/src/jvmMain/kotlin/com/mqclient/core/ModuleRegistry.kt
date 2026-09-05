package com.mqclient.core

import java.util.ServiceLoader

/**
 * Loads all business modules registered through Java SPI at startup.
 *
 * Because every `*-service` / `*-ui` module is on the application classpath,
 * [java.util.ServiceLoader] finds their `META-INF/services` registrations
 * without any manual wiring.
 */
object ModuleRegistry {
    val services: List<BusinessService> by lazy { loadAll(BusinessService::class.java) }

    val uis: List<BusinessUi> by lazy { loadAll(BusinessUi::class.java) }

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
