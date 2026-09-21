package com.mqclient.aipanel

import kotlinx.serialization.Serializable

/** Who authored a [AiMessage]. */
@Serializable
enum class Role { USER, ASSISTANT }

/**
 * One entry in the AI panel's chat log (in-memory view; the durable store is the
 * chat_history table).
 *
 * @property id Stable identifier (uuid-ish) so feedback buttons can target a
 *   single bubble even after the assistant message is edited mid-stream.
 * @property timestamp Epoch millis at the moment the message was created.
 * @property feedback 👍/👎 评价（null = 未评价）；只活在内存 —— chat_history 是
 *   只追加的审计表，没有评价的位置，重启后丢失是计划内的取舍。
 */
@Serializable
data class AiMessage(
    val id: String,
    val role: Role,
    val content: String,
    val timestamp: Long,
    val feedback: Boolean? = null,
)