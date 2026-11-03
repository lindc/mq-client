package com.mqclient.aiui

import com.mqclient.core.AiConnection

/**
 * 「询问 AI」的分析目标（从对象属性对话框 / 卡片入口发起时由宿主注入）：
 * [contextJson] 是发起时点构建好的 chain 数组 JSON（ibm-mq 的 AiChainBuilder 产物），
 * 发送时由 [buildChainContextJson] 包装上 currentTime / userQuestion。
 */
data class AiChainTarget(
    val connectionId: String,
    val objectType: String,
    val objectName: String,
    /** 面板「正在分析」chip 的展示文本。 */
    val targetLabel: String,
    val contextJson: String,
)

/**
 * Tiny DTO describing the user's current focus in the main UI. We pass this
 * rather than a `com.mqclient.core.Connection` so `:ai-panel` stays decoupled
 * from the rest of the app.
 *
 * [chain] 非空 = 「询问 AI」链路分析会话：发送时换用链路分析系统提示词、消息体为
 * 完整上下文 JSON（见 AiPanelState），且切换目标对象即开新对话。
 */
data class AiContext(
    val connectionName: String?,
    val serviceId: String?,
    val objectType: String?,
    val chain: AiChainTarget? = null,
)

/**
 * Pluggable chat backend. The real implementation ([AdapterChatService]) bridges
 * to the ai-core adapters (registry dispatch + SSE streaming); tests use
 * [MockChatService].
 */
interface ChatService {
    /**
     * Streams one assistant turn for [connection]. Implementations are expected
     * to call [onChunk] multiple times with small string fragments; the call
     * site appends them to the in-flight assistant bubble.
     *
     * 返回值是收尾元数据（finishReason / token usage，见 [AiStreamResult]）——
     * 服务端没给的字段为 null，调用方按缺省处理。
     *
     * ⚠️ [onChunk] **可能从任意线程被调**（真实实现在 HTTP/SSE 线程上回调）：只需要把
     * 文本递出去，不要去碰 Compose 状态 —— 调用方（[AiPanelState]）会把分片转回自己的
     * 调度器上串行追加。顺序仍按调用顺序保留。
     *
     * [systemPromptOverride] 非空时作为**附加**系统提示词拼在连接自己的 systemPrompt
     * 之后（「询问 AI」链路分析用，见 chainAnalysisSystemPrompt()）；null = 行为不变。
     */
    suspend fun streamReply(
        history: List<AiMessage>,
        connection: AiConnection,
        context: AiContext?,
        systemPromptOverride: String? = null,
        onChunk: (String) -> Unit,
    ): AiStreamResult
}