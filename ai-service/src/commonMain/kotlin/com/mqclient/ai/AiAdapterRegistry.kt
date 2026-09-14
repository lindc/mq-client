package com.mqclient.ai

import com.mqclient.ai.adapter.AiHttp
import com.mqclient.ai.adapter.ClaudeAdapter
import com.mqclient.ai.adapter.GeminiAdapter
import com.mqclient.ai.adapter.OpenAiCompatibleAdapter
import com.mqclient.core.AiConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Provider + 适配器的注册表（替代旧 `app-core` 的 `AiProviderRegistry` SPI 方案）。
 *
 * 为什么不用 ServiceLoader：它是 JVM-only，会把适配器层逼进 jvmMain；注册表让整个
 * AI 栈留在 commonMain。内置三件套在对象初始化时登记，[register] 对外公开 ——
 * 想加新 Provider，写一个 [AiAdapter] 实现 + 一行注册即可，注册表零改动。
 *
 * 并发姿势与 `EventBus` / `JobScheduler` / `CacheManager` 一致：
 * StateFlow + CAS 整表替换（commonMain 拿不到 java.util.concurrent）。
 */
object AiAdapterRegistry {

    private data class Entry(val provider: AiProvider, val adapter: AiAdapter)

    private val registry = MutableStateFlow<Map<String, Entry>>(emptyMap())

    init {
        register(AiProviders.OPENAI_COMPATIBLE, OpenAiCompatibleAdapter(AiHttp.client))
        register(AiProviders.CLAUDE, ClaudeAdapter(AiHttp.client))
        register(AiProviders.GEMINI, GeminiAdapter(AiHttp.client))
    }

    /** 登记（或同名替换）一个 Provider 及其适配器。id 不一致直接拒绝 —— 那是接线错误。 */
    fun register(provider: AiProvider, adapter: AiAdapter) {
        require(provider.id == adapter.providerId) {
            "provider.id=${provider.id} 与 adapter.providerId=${adapter.providerId} 不一致"
        }
        registry.update { it + (provider.id to Entry(provider, adapter)) }
    }

    /** 全部已登记的 Provider（设置界面的下拉候选）。 */
    fun providers(): List<AiProvider> = registry.value.values.map { it.provider }

    /** 按 id 取 Provider；未登记返回 null。 */
    fun provider(id: String): AiProvider? = registry.value[id]?.provider

    /** 按 providerId 取适配器；未登记返回 null。 */
    fun adapter(providerId: String): AiAdapter? = registry.value[providerId]?.adapter

    /** 按连接取适配器；providerId 未知返回 null。 */
    fun adapterFor(connection: AiConnection): AiAdapter? = adapter(connection.providerId)
}
