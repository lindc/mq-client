package com.mqclient.core

import com.mqclient.core.log.Log
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
/** 本组件的日志 tag。 */
private const val LOG_TAG = "ai-provider-registry"

object AiProviderRegistry {

    /**
     * 全部 provider；首次访问时枚举一次并缓存。
     *
     * ⚠️ `by lazy` **不缓存异常**：`loadAll` 抛出去的话，之后每次读 [all] 都会重跑一遍
     * SPI 枚举、并重发一次错误上报（启动弹窗会被反复刷）。所以 [loadAll] 必须自己把
     * 所有故障吃掉 —— 它内部每一步都兜了 `runCatching`，这里不该有异常逃出来。
     */
    val all: List<AiProvider> by lazy { loadAll() }

    private fun loadAll(): List<AiProvider> {
        val providers = runCatching { ServiceLoader.load(AiProvider::class.java).iterator() }
            .onFailure(::report)
            .getOrNull() ?: return emptyList()

        val loaded = mutableListOf<AiProvider>()
        while (true) {
            // hasNext() 同样会炸：配置文件损坏时抛 ServiceConfigurationError，
            // 它是 Error 不是 Exception，放外面会一路逃出 `by lazy` —— 和
            // ModuleRegistry.loadFrom 是同一个坑，那里的两步都兜住了。
            // 枚举失败时**停**而不是继续：同一个坏迭代器只会反复抛同一个错，
            // 继续循环等于死循环。
            val hasNext = runCatching { providers.hasNext() }
                .onFailure(::report)
                .getOrNull() ?: break
            if (!hasNext) break
            runCatching { providers.next() }
                .onFailure(::report)
                .onSuccess { loaded += it }
        }
        return loaded
    }

    /** 一个 provider 加载失败的统一落点：日志 + 错误通道（界面上要看得见）。 */
    private fun report(error: Throwable) {
        Log.error(LOG_TAG, "cannot load AI provider: $error")
        // 少一个 provider 用户是看得见的（下拉里没有它），原因却只在控制台里 ——
        // 报到错误通道上，别让「为什么没有 OpenAI」变成猜谜。
        CoreModule.reportError(
            message = "AI provider 加载失败：${error.message ?: error::class.simpleName}",
            source = "core",
            error = error,
        )
    }

    /** First provider with the given [id], or null when none is registered. */
    fun findById(id: String): AiProvider? = all.firstOrNull { it.id == id }
}