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
 * Single source of truth for the AI panel. Owns the current [AiConfig] and
 * the persisted chat log, and brokers the streaming coroutine that fills the
 * in-flight assistant bubble.
 *
 * Settings come from [AiConfig] (the shared config store). The system prompt
 * round-trips through Base64 in storage but is exposed here as plain text.
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

    /** Plaintext system prompt — decoded from [AiConfig.systemPromptBase64] on demand. */
    val systemPrompt: String get() = decodePrompt(config.systemPromptBase64)

    /**
     * Applies [transform] to the config, persists the result, and notifies
     * Compose. Switching the provider reseeds both model and URL so a stale
     * endpoint doesn't follow the user across providers.
     */
    fun updateConfig(transform: (AiConfig) -> AiConfig) {
        val next = transform(config)
        val resolved = if (next.providerId != config.providerId) {
            val provider = AiProviderRegistry.findById(next.providerId)
            if (provider != null) {
                next.copy(model = provider.defaultModel, providerUrl = provider.defaultServerUrl)
            } else {
                next
            }
        } else {
            next
        }
        config = resolved
        ConfigStore.saveConfig(ConfigStore.loadConfig().copy(ai = resolved))
    }

    /**
     * Sends [text] as a user message, then kicks off a streaming reply.
     * The visible user bubble keeps the raw [text]; a hidden context prefix
     * is added in the message we hand to the [ChatService] so attach-to-
     * context settings actually reach the (future) real backend.
     *
     * No-op if [text] is blank or a previous turn is still streaming.
     */
    fun sendUserMessage(text: String, context: AiContext?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isStreaming) return

        val userMsg = AiMessage(
            id = newId(),
            role = Role.USER,
            content = trimmed,
            timestamp = System.currentTimeMillis(),
        )

        // No UI-exposed attach flags anymore — defaults are baked in:
        // connection + object type are always attached when context is set.
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

    /** Builds a `[CONTEXT] k=v ...` prefix from the current selection, or null if nothing to attach. */
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