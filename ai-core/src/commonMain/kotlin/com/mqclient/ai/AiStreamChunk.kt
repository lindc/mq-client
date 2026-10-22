package com.mqclient.ai

/**
 * 流式响应的一片增量。
 *
 * [delta] 是本次新增的文本（收尾帧可能为空 —— 那一帧只带 [finishReason]）；
 * [finishReason] 由服务端在收尾帧给出（`stop` / `end_turn` / `STOP` …），没有则为 null。
 *
 * [promptTokens] / [completionTokens] / [totalTokens] 只在收尾的 usage 帧上出现
 * （请求带 `stream_options.include_usage` 时，OpenAI 兼容服务在 [DONE] 前发一帧
 * 只有 usage、choices 为空的帧）；服务端不给则三者为 null，调用方按 0 处理。
 */
data class AiStreamChunk(
    val delta: String = "",
    val finishReason: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)
