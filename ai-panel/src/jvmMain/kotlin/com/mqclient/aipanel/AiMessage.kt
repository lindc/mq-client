package com.mqclient.aipanel

import kotlinx.serialization.Serializable

/** Who authored a [AiMessage]. */
@Serializable
enum class Role { USER, ASSISTANT }

/**
 * One entry in the AI panel's chat log.
 *
 * @property id Stable identifier (uuid-ish) so feedback buttons can target a
 *   single bubble even after the assistant message is edited mid-stream.
 * @property timestamp Epoch millis at the moment the message was created.
 */
@Serializable
data class AiMessage(
    val id: String,
    val role: Role,
    val content: String,
    val timestamp: Long,
)