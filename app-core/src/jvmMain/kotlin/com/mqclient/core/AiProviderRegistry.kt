package com.mqclient.core

import java.util.ServiceLoader

/**
 * Discovers every [AiProvider] on the application classpath via Java SPI.
 *
 * Adding a new provider means: write a class that implements [AiProvider],
 * drop a `META-INF/services/com.mqclient.core.AiProvider` file pointing at
 * it, and add the module as a dependency. The dropdown in the AI header
 * picks it up automatically on next launch.
 *
 * Broken providers (no-arg constructor throws, class missing, etc.) are
 * skipped and logged rather than aborting the whole list — same resilience
 * policy as [ModuleRegistry].
 */
object AiProviderRegistry {

    val all: List<AiProvider> by lazy { loadAll() }

    private fun loadAll(): List<AiProvider> = buildList {
        val providers = ServiceLoader.load(AiProvider::class.java).iterator()
        while (providers.hasNext()) {
            runCatching { providers.next() }
                .onFailure {
                    System.err.println("[AiProviderRegistry] cannot load provider: $it")
                }
                .onSuccess(::add)
        }
    }

    /** First provider with the given [id], or null when none is registered. */
    fun findById(id: String): AiProvider? = all.firstOrNull { it.id == id }
}