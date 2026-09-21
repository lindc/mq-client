package com.mqclient.aiui

/**
 * 一次流式回复的收尾信息：服务端在收尾/usage 帧给出的元数据。
 *
 * [finishReason]：`stop` / `length` / …（服务端没给则为 null）；
 * 三个 token 字段：服务端支持 `stream_options.include_usage` 时才有值，
 * 否则为 null（落库时按 0 处理）。
 */
data class AiStreamResult(
    val finishReason: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)
