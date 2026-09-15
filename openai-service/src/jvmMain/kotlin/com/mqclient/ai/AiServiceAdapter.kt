package com.mqclient.ai

import com.mqclient.core.ServiceAdapter

/**
 * AI 体系的 SPI 描述模块：让「新建连接对话框」的类目 tab 由**一次 SPI 扫描**统一
 * 生成 —— AI 与 MQ 的 tab 都来自 [ServiceAdapter.category]，对话框不需要任何
 * 硬编码的类目清单。
 *
 * 纯描述性：AI 有自己的适配器注册表（[AiAdapterRegistry]），这里 initialize /
 * dispose 用默认空实现，不重复一条生命周期。没有对应的 [com.mqclient.core.BusinessUi]
 * 是合法的（service 没有界面不算错误，见 ModuleRegistry 的说明）。
 */
class AiServiceAdapter : ServiceAdapter {
    override val id = AiProviders.OPENAI_COMPATIBLE.id
    override val name = "OpenAI Compatible"
    override val description = "OpenAI 兼容协议的 AI 服务（OpenAI、Ollama、DeepSeek 等）"
    override val protocol = "OpenAI REST + SSE"
    override val category = "AI"
}
