package com.mqclient.ai

/**
 * OpenAI 兼容协议的 provider 预置（本模块自有，不放 ai-core）。
 *
 * OpenAI Chat Completions 是事实标准，Ollama / DeepSeek / Moonshot 等都走它 ——
 * 所以「OpenAI 兼容」既是本模块的默认 provider，也是三分天下里覆盖面最广的一类。
 */
object AiProviders {

    /** OpenAI Chat Completions 协议：OpenAI / Ollama / DeepSeek / Moonshot 等。 */
    val OPENAI_COMPATIBLE = AiProvider(
        id = "openai-compatible",
        name = "OpenAI 兼容",
        description = "OpenAI Chat Completions 协议，覆盖 OpenAI、Ollama、DeepSeek、Moonshot 等服务",
        defaultBaseUrl = "https://api.openai.com/v1",
        requiresToken = true,
        defaultModels = listOf("gpt-4o", "gpt-4o-mini", "deepseek-chat", "moonshot-v1-8k", "llama3.1"),
    )
}
