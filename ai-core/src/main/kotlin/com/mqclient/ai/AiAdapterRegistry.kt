package com.mqclient.ai

import com.mqclient.core.AiConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Provider + 适配器的注册表（替代旧 `app-core` 的 `AiProviderRegistry` SPI 方案）。
 *
 * **本模块不认识任何具体 provider**：注册表只提供 [register] / 查询，谁提供适配器
 * 谁负责登记 —— 每个 `*-service` 模块在自己的 `ServiceAdapter.initialize` 里登记
 * （启动期 [com.mqclient.core.ModuleRegistry.initializeServices] 统一调用）。
 * 这样「删掉某个 provider」才真的是删一个模块，而不是掏空整个 AI 栈。
 *
 * 为什么不用 ServiceLoader 发现适配器：它只能加载 JVM 类，注册表需要保持可在
 * commonMain 被引用（[AiAdapter] 契约也在那里）。SPI 仍然负责**模块**发现，
 * initialize 就是登记时机。
 *
 * **为什么在 jvmMain**：[AiAdapterRegistry] 本身没有 Java 依赖，但它唯一的写入方
 * （各 service 模块的 initialize）要传 `AiHttp::clientFor`，而 `AiHttp` 构造 Ktor
 * 客户端要 `io.ktor.client.engine.java` 与 `javax.net.ssl.SSLContext`。项目当前只有
 * `jvm()` 一个 target；把注册表放在同一侧，避免 commonMain 出现半套抽象。
 * 纯契约（[AiAdapter] / [AiProvider] / [AiChatMessage] / [AiException]）仍在
 * commonMain。
 *
 * 并发姿势与 `EventBus` / `JobScheduler` / `CacheManager` 一致：
 * StateFlow + CAS 整表替换。
 */
object AiAdapterRegistry {

    private data class Entry(val provider: AiProvider, val adapter: AiAdapter)

    private val registry = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** 登记（或同名替换）一个 Provider 及其适配器。id 不一致直接拒绝 —— 那是接线错误。 */
    fun register(provider: AiProvider, adapter: AiAdapter) {
        require(provider.id == adapter.providerId) {
            "provider.id=${provider.id} does not match adapter.providerId=${adapter.providerId}"
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
