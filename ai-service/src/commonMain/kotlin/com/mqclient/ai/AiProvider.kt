package com.mqclient.ai

/**
 * 一个 AI 服务提供者类型（Provider）的描述。
 *
 * Provider 只回答「这一类 API 长什么样」；用户真正使用的是
 * [com.mqclient.core.AiConnection]（Provider + 地址 / token / 模型），
 * 同一个 Provider 可以有多条连接（多个账号、多个 Ollama 实例……）。
 *
 * 规范 id 的字符串只在 [AiProviders] 里出现，适配器和注册表都引用那里的常量。
 */
data class AiProvider(
    /** 稳定 id（如 `openai-compatible`）；连接通过 providerId 找到适配器。 */
    val id: String,
    /** 展示名（下拉框用）。 */
    val name: String,
    /** 一句话说明（设置界面展示）。 */
    val description: String = "",
    /** 新建连接时预填的接口地址。 */
    val defaultBaseUrl: String = "",
    /** 是否通常需要 token（本地 Ollama 不需要；只作界面提示，不作硬校验）。 */
    val requiresToken: Boolean = true,
    /** 模型下拉候选；空列表 = 只让用户手填。 */
    val defaultModels: List<String> = emptyList(),
)

/** 内置 Provider 预置。 */
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

    /** Anthropic 官方 Messages API（格式与 OpenAI 不同，走独立适配器）。 */
    val CLAUDE = AiProvider(
        id = "claude",
        name = "Claude",
        description = "Anthropic 官方 Messages API",
        defaultBaseUrl = "https://api.anthropic.com",
        requiresToken = true,
        defaultModels = listOf("claude-3-5-sonnet-latest", "claude-3-5-haiku-latest", "claude-3-opus-latest"),
    )

    /** Google Gemini（generativelanguage v1beta）。 */
    val GEMINI = AiProvider(
        id = "gemini",
        name = "Gemini",
        description = "Google Gemini API",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        requiresToken = true,
        defaultModels = listOf("gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash"),
    )

    /** 全部内置 Provider，注册表初始化用。 */
    val ALL: List<AiProvider> = listOf(OPENAI_COMPATIBLE, CLAUDE, GEMINI)
}
