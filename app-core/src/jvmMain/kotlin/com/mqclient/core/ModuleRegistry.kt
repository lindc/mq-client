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
        val list = runCatching { ServiceLoader.load(BusinessService::class.java).toList() }
            .getOrDefault(emptyList())
        println("[ModuleRegistry] services=${list.map { it.id }}")
        list
    }

    val uis: List<BusinessUi> by lazy {
        val urls = BusinessUi::class.java.classLoader
            .getResources("META-INF/services/com.mqclient.core.BusinessUi")
            .toList()
        println("[ModuleRegistry] BusinessUi service files found: ${urls.size}")
        urls.forEachIndexed { i, u -> println("   [$i] $u") }
        val list = runCatching { ServiceLoader.load(BusinessUi::class.java).toList() }
            .getOrDefault(emptyList())
        println("[ModuleRegistry] uis=${list.map { it.serviceId }}")
        list
    }
}
