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
                .onFailure { error ->
                    System.err.println("[AiProviderRegistry] cannot load provider: $error")
                    // 少一个 provider 用户是看得见的（下拉里没有它），原因却只在控制台里 ——
                    // 报到错误通道上，别让「为什么没有 OpenAI」变成猜谜。
                    CoreModule.reportError(
                        message = "AI provider 加载失败：${error.message ?: error::class.simpleName}",
                        source = "core",
                        error = error,
                    )
                }
                .onSuccess(::add)
        }
    }

    /** First provider with the given [id], or null when none is registered. */
    fun findById(id: String): AiProvider? = all.firstOrNull { it.id == id }
}