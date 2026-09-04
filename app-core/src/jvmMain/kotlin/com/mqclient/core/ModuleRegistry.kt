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
    val services: List<BusinessService> by lazy {
        runCatching { ServiceLoader.load(BusinessService::class.java).toList() }
            .getOrDefault(emptyList())
    }

    val uis: List<BusinessUi> by lazy {
        runCatching { ServiceLoader.load(BusinessUi::class.java).toList() }
            .getOrDefault(emptyList())
    }
}
