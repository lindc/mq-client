package com.mqclient.core

import kotlinx.serialization.Serializable

/**
 * Root config persisted at `~/.mqclient/config.json`. Plaintext (no secrets
 * live here) — the secret-bearing piece, the list of MQ connections, lives
 * in `connections.json` encrypted with `secret.key`.
 */
@Serializable
data class Config(
    val theme: ThemeConfig = ThemeConfig(),
    val ai: AiConfig = AiConfig(),
)

/**
 * Theme tuning. The bundled `lxgw_wen_kai_mono_regular` is always applied —
 * the system-font flags just layer extra faces on top of it for users who
 * want a Latin / monospace variant in the family.
 */
@Serializable
data class ThemeConfig(
    /** Packed ARGB integer; default keeps the seed used since v0. */
    val seedColor: Long = 0xFF68A500L,
    val useSystemSerif: Boolean = false,
    val useSystemSans: Boolean = false,
    val useSystemMono: Boolean = false,
)

/**
 * User-tunable AI panel knobs. The system prompt is stored as Base64 so the
 * stored JSON stays valid no matter what characters the user typed (Chinese,
 / quotes, newlines, etc.) and we don't have to fight JSON escape rules.
 *
 * [providerId] is a stable id from [AiProvider]; if no provider with that id
 * is registered at startup the panel falls back to the first available one.
 */
@Serializable
data class AiConfig(
    val providerId: String = "claude",
    /** API endpoint. Reseeded from [AiProvider.defaultServerUrl] when the provider changes. */
    val providerUrl: String = "https://api.anthropic.com",
    /** Plaintext API key. Future: move into the encrypted connections store. */
    val accessKey: String = "",
    val model: String = "claude-3-5-sonnet-latest",
    /** UTF-8 of [DEFAULT_SYSTEM_PROMPT] (or whatever the user entered) → Base64. */
    val systemPromptBase64: String = "",
    /** How many past messages to load from `ai-messages.json` on startup. */
    val historyLimit: Int = 200,
)

/** Default system prompt — bilingual is fine here since users only see it in the settings dialog. */
const val DEFAULT_SYSTEM_PROMPT: String =
    "你是一个 MQ 管理助手,帮助用户排查消息队列问题、解释概念并生成安全的运维命令。" +
        "回答简洁,中文输出,必要时给出可执行的步骤。"

/** UTF-8 bytes of [DEFAULT_SYSTEM_PROMPT] → Base64. Computed once at class load. */
val DEFAULT_SYSTEM_PROMPT_BASE64: String =
    java.util.Base64.getEncoder().encodeToString(DEFAULT_SYSTEM_PROMPT.toByteArray(Charsets.UTF_8))