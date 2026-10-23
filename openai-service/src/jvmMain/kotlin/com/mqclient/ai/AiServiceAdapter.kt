package com.mqclient.ai

import com.mqclient.ai.adapter.AiHttp
import com.mqclient.ai.adapter.OpenAiCompatibleAdapter
import com.mqclient.core.ServiceAdapter
import com.mqclient.core.spi.ServiceContext

/**
 * OpenAI 兼容模块的 SPI 描述：让「新建连接对话框」的 AI 类目里出现这张卡片
 * （[com.mqclient.core.ModuleRegistry.services] 按 [category] 分组）。
 *
 * [initialize] 是适配器的**注册时机**：[AiAdapterRegistry] 不认识任何具体 provider，
 * 每个 provider 模块自己登记。启动期
 * [com.mqclient.core.ModuleRegistry.initializeServices] 跑完之后，所有访问注册表的
 * 调用点（面板发送 / 设置页 / 向导表单）天然可见 —— 与 Anthropic 模块同一契约。
 *
 * 没有对应的 [com.mqclient.core.AdapterUI] 是合法的（service 没有界面不算错误，
 * 见 ModuleRegistry 的说明）。
 */
class AiServiceAdapter : ServiceAdapter {
    override val id = AiProviders.OPENAI_COMPATIBLE.id
    override val name = "OpenAI Compatible"
    override val description = "OpenAI 兼容协议的 AI 服务（OpenAI、Ollama、DeepSeek 等）"
    override val protocol = "OpenAI REST + SSE"
    override val category = "AI"

    override fun initialize(context: ServiceContext) {
        // clientFor 按连接分发：默认严格 CIO，ignoreSsl=true 走 trust-all（见 AiHttp）。
        AiAdapterRegistry.register(
            AiProviders.OPENAI_COMPATIBLE,
            OpenAiCompatibleAdapter(AiHttp::clientFor),
        )
    }
}
