package com.mqclient.aipanel

import com.mqclient.core.AiConfig

/**
 * Tiny DTO describing the user's current focus in the main UI. We pass this
 * rather than a `com.mqclient.core.Connection` so `:ai-panel` stays decoupled
 * from the rest of the app.
 */
data class AiContext(
    val connectionName: String?,
    val serviceId: String?,
    val objectType: String?,
)

/**
 * Pluggable chat backend. The current implementation is a mock that emits a
 * canned reply; a future real implementation would call out to OpenAI /
 * Anthropic / Ollama via HTTP and parse SSE chunks into the same [onChunk]
 * callback so the rest of the UI doesn't change.
 */
interface ChatService {
    /**
     * Streams one assistant turn. Implementations are expected to call
     * [onChunk] multiple times with small string fragments; the call site
     * appends them to the in-flight assistant bubble.
     */
    suspend fun streamReply(
        history: List<AiMessage>,
        config: AiConfig,
        context: AiContext?,
        onChunk: (String) -> Unit,
    )
}