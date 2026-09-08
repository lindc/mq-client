package com.mqclient.core

import kotlinx.serialization.Serializable

/**
 * Root config persisted at `~/.mqclient/config.json`. Plaintext (no secrets
 * live here) — the secret-bearing piece, the list of MQ connections, lives
 * in `connections.json` encrypted with `secret.key`.
 */
@Serializable
data class Config(
    val general: GeneralConfig = GeneralConfig(),
    val theme: ThemeConfig = ThemeConfig(),
    val ai: AiConfig = AiConfig(),
)

/**
 * App-wide behaviour knobs that don't fit in either theme or AI.
 */
@Serializable
data class GeneralConfig(
    val language: Language = Language.SIMPLIFIED_CHINESE,
    /** Past-N-days of history the app keeps around before pruning. */
    val historyRetentionDays: Int = 90,
)

@Serializable
enum class Language(val displayName: String) {
    SIMPLIFIED_CHINESE("简体中文"),
    ENGLISH("English"),
}

/**
 * Theme tuning. The bundled `lxgw_wen_kai_mono` font is the source of truth
 * — the renderer currently only consumes [fontFamily] (and falls back to
 * the bundled face if the id is unknown).
 *
 * [themeMode] is a coarse switch over light/dark/system-follow; the actual
 * palette generation happens in the UI module via MaterialKolor's
 * dynamic theme.
 */
@Serializable
data class ThemeConfig(
    /** Packed ARGB integer. */
    val seedColor: Long = DEFAULT_SEED_COLOR,
    /** Stable id of the font to use; see [FONT_LXGW_WEN_KAI_MONO] etc. */
    val fontFamily: String = FONT_LXGW_WEN_KAI_MONO,
    /** Base font size in px (rendered as sp at the typography layer). */
    val fontSize: Int = 14,
    /** Light/dark/system-follow switch. Defaults to SYSTEM for new and existing installs. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

/**
 * How the app should resolve light vs dark theme. SYSTEM defers to the
 * platform's `isSystemInDarkTheme()`; the UI module reads this enum and
 * computes the boolean it needs for MaterialKolor / NucleusDecoratedWindow.
 */
@Serializable
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}

/** The default seed color; restored by the "恢复默认" button in settings. */
const val DEFAULT_SEED_COLOR: Long = 0xFF68A500L

/** Stable ids the settings dialog writes into [ThemeConfig.fontFamily]. */
const val FONT_LXGW_WEN_KAI_MONO: String = "lxgw_wen_kai_mono"
const val FONT_SYSTEM_SANS: String = "system_sans"
const val FONT_SYSTEM_SERIF: String = "system_serif"
const val FONT_SYSTEM_MONO: String = "system_mono"

/**
 * User-tunable AI panel knobs.
 *
 * The dropdown the user sees in the panel header shows whatever providers
 * are registered via the [AiProvider] SPI; each provider gets its own
 * [AiProviderConfig] slot in [providerConfigs]. Missing entries just mean
 * "user hasn't customised this one yet" — the renderer falls back to
 * the provider's defaults.
 *
 * [historyLimit] governs how many past messages the panel loads from
 * `ai-messages.json` on startup — defaults to 200 per the first-launch
 * spec.
 */
@Serializable
data class AiConfig(
    /** Stable id of the active [AiProvider]; empty = first registered. */
    val activeProviderId: String = "",
    /** How many past messages to load on startup. UI clamps to 10-500. */
    val historyLimit: Int = 200,
    /** Whether to attach the current connection/queue context to each user prompt. */
    val autoAttachContext: Boolean = true,
    /** One slot per AI provider — sparse; missing entries use provider defaults. */
    val providerConfigs: List<AiProviderConfig> = emptyList(),
)

/**
 * Per-provider overrides. [providerId] matches [AiProvider.id]; the URL,
 * token, model and prompt are user-edited and override the provider's
 * built-in defaults when present.
 *
 * [systemPrompt] is stored Base64 to keep the JSON file clean regardless
 * of what the user types (CJK, quotes, newlines).
 */
@Serializable
data class AiProviderConfig(
    val providerId: String,
    val providerUrl: String = "",
    val accessToken: String = "",
    val model: String = "",
    val systemPromptBase64: String = "",
)

/** Default system prompt — bilingual is fine here since users only see it in the settings dialog. */
const val DEFAULT_SYSTEM_PROMPT: String =
    "你是一个 MQ 管理助手,帮助用户排查消息队列问题、解释概念并生成安全的运维命令。" +
        "回答简洁,中文输出,必要时给出可执行的步骤。"

/** UTF-8 bytes of [DEFAULT_SYSTEM_PROMPT] → Base64. Computed once at class load. */
val DEFAULT_SYSTEM_PROMPT_BASE64: String =
    java.util.Base64.getEncoder().encodeToString(DEFAULT_SYSTEM_PROMPT.toByteArray(Charsets.UTF_8))