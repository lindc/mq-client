package com.mqclient.aipanel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Single source of truth for the AI panel. Owns the current [AiSettings] and
 * the persisted chat log, and brokers the streaming coroutine that fills the
 * in-flight assistant bubble.
 *
 * Compose observes [settings] / [messages] / [streamingMessageId] directly,
 * so any mutation triggers a recomposition of just the dependent subviews.
 */
@Stable
class AiPanelState internal constructor(
    initialSettings: AiSettings,
    initialMessages: List<AiMessage>,
    private val chat: ChatService,
    private val scope: CoroutineScope,
) {
    var settings: AiSettings by mutableStateOf(initialSettings)
        private set

    var messages: List<AiMessage> by mutableStateOf(initialMessages)
        private set

    /** Id of the assistant message currently being streamed, or null. */
    var streamingMessageId: String? by mutableStateOf(null)
        private set

    val isStreaming: Boolean get() = streamingMessageId != null

    /**
     * Applies [transform] to the settings, persists the result, and notifies
     * Compose. Switching [AiSettings.provider] also resets both
     * [AiSettings.model] and [AiSettings.serverUrl] to that provider's
     * defaults so a stale model name or endpoint doesn't follow the user
     * across providers.
     */
    fun updateSettings(transform: (AiSettings) -> AiSettings) {
        val next = transform(settings)
        val resolved = if (next.provider != settings.provider) {
            next.copy(
                model = next.provider.defaultModel,
                serverUrl = next.provider.defaultServerUrl,
            )
        } else {
            next
        }
        settings = resolved
        AiStore.saveSettings(resolved)
    }

    /**
     * Sends [text] as a user message, then kicks off a streaming reply.
     * The visible user bubble keeps the raw [text]; a hidden context prefix
     * is added in the message we hand to the [ChatService] so that
     * attach-to-context settings actually reach the (future) real backend.
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

        val contextPrefix = buildContextPrefix(settings, context)
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
                chat.streamReply(historyForCall, settings, context) { chunk ->
                    appendToMessage(assistantMsg.id, chunk)
                }
            } finally {
                streamingMessageId = null
                AiStore.saveMessages(messages)
            }
        }
    }

    /** Records 👍/👎 feedback on an assistant bubble. In-memory only — not persisted yet. */
    fun recordFeedback(messageId: String, useful: Boolean?) {
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

    /** Builds a `[CONTEXT] k=v ...` prefix string from enabled settings, or null if nothing to attach. */
    private fun buildContextPrefix(settings: AiSettings, context: AiContext?): String? {
        if (context == null) return null
        val parts = mutableListOf<String>()
        if (settings.attachConnection) {
            context.connectionName?.let { parts += "connection=$it" }
            context.serviceId?.let { parts += "service=$it" }
        }
        if (settings.attachObjectType) context.objectType?.let { parts += "objectType=$it" }
        if (parts.isEmpty()) return null
        return "[CONTEXT] ${parts.joinToString(" ")}"
    }

    private fun newId(): String = UUID.randomUUID().toString()
}

/**
 * Compose entry point. Loads persisted settings + history off the calling
 * thread, then wires them into a stable [AiPanelState] tied to the local
 * coroutine scope.
 */
@Composable
fun rememberAiPanelState(): AiPanelState {
    val scope = rememberCoroutineScope()
    val state = remember {
        AiPanelState(
            initialSettings = AiStore.loadSettings(),
            initialMessages = AiStore.loadMessages(),
            chat = MockChatService(),
            scope = scope,
        )
    }
    return state
}