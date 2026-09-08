package com.mqclient.core

/**
 * Pluggable AI provider descriptor. Discovered automatically via Java SPI
 * ([java.util.ServiceLoader]) from any module on the classpath that ships a
 * `META-INF/services/com.mqclient.core.AiProvider` file — adding a new
 * provider is a drop-in (implement + register).
 *
 * Defaults here are used by [com.mqclient.core.AiConfig] when the user
 * hasn't overridden the model or URL — the user can edit them in the
 * settings dialog after picking a provider.
 */
interface AiProvider {
    /** Stable id, e.g. "claude". Persisted in [AiConfig.providerId]. */
    val id: String

    /** Human-readable name shown in the dropdown. */
    val displayName: String

    /** Default model id when this provider is first picked. */
    val defaultModel: String

    /** Default API endpoint when this provider is first picked. */
    val defaultServerUrl: String

    /**
     * Curated list of model identifiers offered to the settings UI as a
     * dropdown; empty means the UI falls back to a free-text input (the
     * "custom" branch in the dialog). Implementation is optional — the
     * default getter returns `emptyList()`.
     */
    val models: List<String>
        get() = emptyList()
}