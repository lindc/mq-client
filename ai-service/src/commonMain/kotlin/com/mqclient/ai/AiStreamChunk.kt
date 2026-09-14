package com.mqclient.ai

/**
 * 流式响应的一片增量。
 *
 * [delta] 是本次新增的文本（收尾帧可能为空 —— 那一帧只带 [finishReason]）；
 * [finishReason] 由服务端在收尾帧给出（`stop` / `end_turn` / `STOP` …），没有则为 null。
 */
data class AiStreamChunk(
    val delta: String = "",
    val finishReason: String? = null,
)
