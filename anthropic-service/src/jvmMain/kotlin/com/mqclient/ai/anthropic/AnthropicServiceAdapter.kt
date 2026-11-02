package com.mqclient.ai.anthropic

import com.mqclient.ai.AiAdapterRegistry
import com.mqclient.ai.adapter.AiHttp
import com.mqclient.core.ServiceAdapter
import com.mqclient.core.spi.ServiceContext

/**
 * Anthropic 模块的 SPI 描述：让「新建连接对话框」的 AI 类目里出现 Anthropic 卡片
 * （[ModuleRegistry.services] 按 [category] 分组）。
 *
 * [initialize] 是适配器的**注册时机**：[AiAdapterRegistry] 刻意不做 ServiceLoader
 * （保 commonMain），内置 OpenAI 在 registry 的 init 块登记，Anthropic 作为独立
 * 模块在这里登记 —— 启动期 [com.mqclient.core.ModuleRegistry.initializeServices]
 * 跑完之后，所有访问注册表的调用点（面板发送 / 设置页 / 向导表单）天然可见。
 */
class AnthropicServiceAdapter : ServiceAdapter {
    override val id = AnthropicProviders.ANTHROPIC.id
    override val name = "Anthropic"
    override val description = "AI services speaking the Anthropic Messages API (Claude model family)"
    override val protocol = "Anthropic REST + SSE"
    override val category = "AI"

    override fun initialize(context: ServiceContext) {
        // clientFor 按连接分发：默认严格 CIO，ignoreSsl=true 走 trust-all（见 AiHttp）。
        AiAdapterRegistry.register(AnthropicProviders.ANTHROPIC, AnthropicAdapter(AiHttp::clientFor))
    }
}
