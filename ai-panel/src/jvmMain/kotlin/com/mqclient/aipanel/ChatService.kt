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
     *
     * ⚠️ [onChunk] **可能从任意线程被调**（真实实现在 HTTP/SSE 线程上回调）：只需要把
     * 文本递出去，不要去碰 Compose 状态 —— 调用方（[AiPanelState]）会把分片转回自己的
     * 调度器上串行追加。顺序仍按调用顺序保留。
     */
    suspend fun streamReply(
        history: List<AiMessage>,
        config: AiConfig,
        context: AiContext?,
        onChunk: (String) -> Unit,
    )
}