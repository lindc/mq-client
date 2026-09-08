package com.mqclient.aipanel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mqclient.core.AiConfig
import com.mqclient.core.AiProvider
import com.mqclient.core.AiProviderRegistry
import com.mqclient.core.ConfigStore
import com.mqclient.core.DEFAULT_SYSTEM_PROMPT
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Single source of truth for the AI panel.
 *
 * Pulls the registered [AiProvider] list from the SPI registry and merges
 * in the user-configured overrides from [AiConfig.providerConfigs]. The
 * [activeProvider] is whatever provider matches [AiConfig.activeProviderId]
 * (or the first registered one if nothing's set).
 */
@Stable
class AiPanelState internal constructor(
    initialConfig: AiConfig,
    initialMessages: List<AiMessage>,
    private val chat: ChatService,
    private val scope: CoroutineScope,
) {
    var config: AiConfig by mutableStateOf(initialConfig)
        private set

    var messages: List<AiMessage> by mutableStateOf(initialMessages)
        private set

    /** Id of the assistant message currently being streamed, or null. */
    var streamingMessageId: String? by mutableStateOf(null)
        private set

    val isStreaming: Boolean get() = streamingMessageId != null

    /** All registered AI providers (from SPI). Recomputed when [AiProviderRegistry] changes. */
    val providers: List<AiProvider> = AiProviderRegistry.all

    /**
     * The provider the user picked via [AiConfig.activeProviderId], or the
     * first registered one if nothing's set. Computed on every read so it
     * stays in lockstep with [config] after [updateConfig].
     */
    val activeProvider: AiProvider?
        get() = providers.firstOrNull { it.id == config.activeProviderId }
            ?: providers.firstOrNull()

    /**
     * The override (URL/token/model/prompt) for the active provider, or
     * null when the user hasn't customised it yet — the runtime falls back
     * to the provider's defaults.
     */
    val activeProviderOverride: com.mqclient.core.AiProviderConfig?
        get() = config.providerConfigs.firstOrNull { it.providerId == activeProvider?.id }

    val systemPrompt: String
        get() = activeProviderOverride?.systemPromptBase64?.let(::decodePrompt) ?: DEFAULT_SYSTEM_PROMPT

    fun updateConfig(transform: (AiConfig) -> AiConfig) {
        val next = transform(config)
        config = next
        ConfigStore.saveConfig(ConfigStore.loadConfig().copy(ai = next))
    }

    /**
     * Sends [text] as a user message, then kicks off a streaming reply.
     * No-op if [text] is blank, a previous turn is still streaming, or no
     * provider has been configured yet.
     */
    fun sendUserMessage(text: String, context: AiContext?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isStreaming) return
        if (activeProvider == null) return

        val userMsg = AiMessage(
            id = newId(),
            role = Role.USER,
            content = trimmed,
            timestamp = System.currentTimeMillis(),
        )

        val contextPrefix = buildContextPrefix(context)
        val wireContent = if (contextPrefix != null) "$contextPrefix\n\n$trimmed" else trimmed
        val historyForCall = messages + userMsg.copy(content = wireContent)

        val assistantMsg = AiMessage(
            id = newId(),
            role = Role.ASSISTANT,
            content = "",
            timestamp = System.currentTimeMillis(),
        )
        messages = messages + userMsg + assistantMsg
        streamingMessageId = assistantMsg.id

        scope.launch {
            try {
                chat.streamReply(historyForCall, config, context) { chunk ->
                    appendToMessage(assistantMsg.id, chunk)
                }
            } finally {
                streamingMessageId = null
                AiStore.saveMessages(messages)
            }
        }
    }

    /** Records 👍/👎 feedback on an assistant bubble. In-memory only — not persisted yet. */
    fun recordFeedback(messageId: String, useful: Boolean) {
        val idx = messages.indexOfLast { it.id == messageId }
        if (idx < 0) return
        val current = messages[idx]
        messages = messages.toMutableList().also { it[idx] = current.copy() }
    }

    /** Drops every message and clears the on-disk log. */
    fun clearHistory() {
        messages = emptyList()
        AiStore.saveMessages(emptyList())
    }

    private fun appendToMessage(messageId: String, chunk: String) {
        val mutable = messages.toMutableList()
        val idx = mutable.indexOfLast { it.id == messageId }
        if (idx < 0) return
        mutable[idx] = mutable[idx].copy(content = mutable[idx].content + chunk)
        messages = mutable
    }

    /** Builds a `[CONTEXT] k=v =` prefix from the current selection, or null if nothing to attach. */
    private fun buildContextPrefix(context: AiContext?): String? {
        if (context == null) return null
        val parts = mutableListOf<String>()
        context.connectionName?.let { parts += "connection=$it" }
        context.serviceId?.let { parts += "service=$it" }
        context.objectType?.let { parts += "objectType=$it" }
        if (parts.isEmpty()) return null
        return "[CONTEXT] ${parts.joinToString(" ")}"
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun decodePrompt(b64: String): String =
        try {
            if (b64.isBlank()) DEFAULT_SYSTEM_PROMPT
            else String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
        } catch (_: Throwable) {
            DEFAULT_SYSTEM_PROMPT
        }

    companion object {
        /** Encode any user-entered prompt text into the Base64 storage form. */
        fun encodePrompt(plain: String): String =
            java.util.Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Compose entry point. Loads the chat log off disk (sliced to
 * [AiConfig.historyLimit] most-recent messages) and wires the persisted
 * [AiConfig] into a stable [AiPanelState] tied to the local coroutine scope.
 */
@Composable
fun rememberAiPanelState(initialConfig: AiConfig = ConfigStore.loadConfig().ai): AiPanelState {
    val scope = rememberCoroutineScope()
    val state = remember(initialConfig) {
        AiPanelState(
            initialConfig = initialConfig,
            initialMessages = AiStore.loadMessages(initialConfig.historyLimit),
            chat = MockChatService(),
            scope = scope,
        )
    }
    return state
}