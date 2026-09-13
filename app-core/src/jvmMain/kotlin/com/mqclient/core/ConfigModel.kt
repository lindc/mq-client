package com.mqclient.core

import kotlinx.serialization.Serializable

/**
 * Root config persisted at `~/.mqclient/config.json`.
 *
 * Single-file layout (since the ConfigManager migration): everything —
 * including the connection list — lives in this one plaintext-JSON file.
 * Sensitive string fields (marked with [Sensitive] and/or matching the
 * sensitive-name keywords in [ConfigManager]) are stored encrypted, each
 * value as `enc:v1:Base64(iv):Base64(ct+tag)` under the AES key from
 * `~/.mqclient/secret.key`. Nothing else lives in the config dir anymore.
 *
 * Unknown keys in older/newer files are tolerated on read
 * ([kotlinx.serialization.json.Json] ignoreUnknownKeys) and every field has
 * a default, so a missing/partial file degrades gracefully.
 */
@Serializable
data class Config(
    val general: GeneralConfig = GeneralConfig(),
    val theme: ThemeConfig = ThemeConfig(),
    val ai: AiConfig = AiConfig(),
    /** Saved MQ connections — one entry per middleware endpoint. */
    val connections: List<ConnectionInfo> = emptyList(),
    /** File-layout version info, for future format migrations. */
    val metadata: ConfigMetadata = ConfigMetadata(),
)

/** Version / format bookkeeping stored in [Config.metadata]. */
@Serializable
data class ConfigMetadata(
    /** Bump when the on-disk layout changes in a way readers must detect. */
    val version: Int = 1,
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
    /**
     * Font id persisted from the settings dialog: one of the FONT_* constants
     * below for the built-ins, or a **system font family name** as reported by
     * the OS (e.g. "Microsoft YaHei") once the font dropdown started listing
     * installed fonts. Rendering does not consume this yet — the UI layer
     * resolves ids to actual font faces when the message-bus rework lands;
     * unknown/uninstalled values then fall back to the default.
     */
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
    /** API key — encrypted at rest by ConfigManager (name-matched + [Sensitive]). */
    @Sensitive
    val accessToken: String = "",
    val model: String = "",
    val systemPromptBase64: String = "",
)

/**
 * One saved MQ connection. A connection always belongs to a middleware module
 * ([serviceId] is a [ServiceAdapter.id]), but several connections can share one
 * module — the sidebar lists connections, not modules.
 *
 * The concrete credential fields a middleware needs are decided by its own
 * service module (per the single-file spec, connections live in config.json);
 * any field a service adds whose name matches the sensitive keywords
 * (password, apiKey, …) is encrypted automatically by [ConfigManager].
 */
@Serializable
data class ConnectionInfo(
    val id: String,
    /** User-facing name, e.g. "生产环境". */
    val name: String,
    val serviceId: String,
    /** Name of the MQ endpoint itself, e.g. "rabbit@mq-01". */
    val mqName: String,
    val host: String,
    val port: Int,
    /**
     * 该中间件自己的扩展字段（vhost / channel / queue manager / SASL 机制 …），键值都是字符串。
     *
     * 这是对话框按 [serviceId] 渲染字段的挂靠点：各 service 模块声明自己要哪些字段，
     * 值原样存进这里 —— app-core 不解释内容，也不需要为每个中间件改一次模型。
     * 键名命中敏感关键字的项（password / token / secret …）由 [ConfigManager] 按名字
     * 自动加密，和普通字段同一套规则。
     */
    val options: Map<String, String> = emptyMap(),
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
) {
    val endpoint: String get() = "$host:$port"
}

/** Live state of a connection, as shown by the sidebar's status dot and label. */
@Serializable
enum class ConnectionStatus(val label: String) {
    Connected("已连接"),
    Connecting("连接中..."),
    Failed("连接失败"),
    Disconnected("未连接"),
}

/** The default seed color; restored by the "恢复默认" button in settings. */
const val DEFAULT_SEED_COLOR: Long = 0xFF68A500L

/**
 * Stable ids the settings dialog writes into [ThemeConfig.fontFamily] for the
 * four built-in choices (bundled font + the three generic system families).
 * Anything else persisted in [ThemeConfig.fontFamily] is a system font family
 * name coming from the system-font enumeration in the UI module.
 */
const val FONT_LXGW_WEN_KAI_MONO: String = "lxgw_wen_kai_mono"
const val FONT_SYSTEM_SANS: String = "system_sans"
const val FONT_SYSTEM_SERIF: String = "system_serif"
const val FONT_SYSTEM_MONO: String = "system_mono"

/** Default system prompt — bilingual is fine here since users only see it in the settings dialog. */
const val DEFAULT_SYSTEM_PROMPT: String =
    "你是一个 MQ 管理助手,帮助用户排查消息队列问题、解释概念并生成安全的运维命令。" +
        "回答简洁,中文输出,必要时给出可执行的步骤。"

/** UTF-8 bytes of [DEFAULT_SYSTEM_PROMPT] → Base64. Computed once at class load. */
val DEFAULT_SYSTEM_PROMPT_BASE64: String =
    java.util.Base64.getEncoder().encodeToString(DEFAULT_SYSTEM_PROMPT.toByteArray(Charsets.UTF_8))
