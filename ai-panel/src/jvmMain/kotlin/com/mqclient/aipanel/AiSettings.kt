package com.mqclient.aipanel

import kotlinx.serialization.Serializable

/**
 * The user-tunable knobs for the AI panel. Persisted to a JSON file so a
 * restart of the app restores the same configuration.
 *
 * The mock [ChatService] ignores every field — they're here so that a real
 * provider implementation can be swapped in later without an API change.
 *
 * [serverUrl] is the API endpoint; each enum entry in [Provider] supplies
 * its own [Provider.defaultServerUrl] so a switch in the dropdown also
 * reseeds the URL with the matching default.
 */
@Serializable
data class AiSettings(
    val provider: Provider = Provider.CLAUDE,
    val apiKey: String = "",
    val serverUrl: String = Provider.CLAUDE.defaultServerUrl,
    val model: String = Provider.CLAUDE.defaultModel,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val attachConnection: Boolean = true,
    val attachObjectType: Boolean = true,
    val attachMessages: Boolean = false,
)

/**
 * Available AI providers. [defaultModel] and [defaultServerUrl] feed the UI:
 * switching [Provider] in the settings dialog reseeds both fields so a stale
 * endpoint doesn't follow the user across providers.
 */
@Serializable
enum class Provider(
    val displayName: String,
    val defaultModel: String,
    val defaultServerUrl: String,
) {
    CLAUDE("Claude", "claude-3-5-sonnet-latest", "https://api.anthropic.com"),
    OPENAI("OpenAI", "gpt-4o", "https://api.openai.com/v1"),
    OLLAMA("Ollama (本地)", "llama3.1", "http://localhost:11434"),
}

/** Default system prompt — bilingual is fine here since users only see it in the settings dialog. */
const val DEFAULT_SYSTEM_PROMPT: String =
    "你是一个 MQ 管理助手,帮助用户排查消息队列问题、解释概念并生成安全的运维命令。" +
        "回答简洁,中文输出,必要时给出可执行的步骤。"