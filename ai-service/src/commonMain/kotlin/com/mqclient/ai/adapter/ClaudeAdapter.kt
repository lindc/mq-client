package com.mqclient.ai.adapter

import com.mqclient.ai.AiAdapter
import com.mqclient.ai.AiChatMessage
import com.mqclient.ai.AiException
import com.mqclient.ai.AiResponse
import com.mqclient.ai.AiRole
import com.mqclient.ai.AiStreamChunk
import com.mqclient.core.AiConnection
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val PROVIDER_LABEL = "Claude"

/** Anthropic 要求的 API 版本头；2023-06-01 是现行稳定版。 */
private const val ANTHROPIC_VERSION = "2023-06-01"

/** 非流式请求的 max_tokens：Messages API 的必填项，取一个覆盖常见回复的值。 */
private const val MAX_TOKENS = 4096

/**
 * Anthropic Claude 适配器（Messages API）。
 *
 * 与 OpenAI 兼容协议的关键差异：
 *  - 鉴权是 `x-api-key` 头 + 必填的 `anthropic-version` 头；
 *  - 系统提示词不是一条 role=system 的消息，而是顶层 `system` 字段；
 *  - `max_tokens` 必填；
 *  - 流式响应的文本在具名事件（`content_block_delta`）的 `delta.text` 里。
 */
class ClaudeAdapter(private val client: HttpClient) : AiAdapter {

    override val providerId: String = "claude"

    override suspend fun chat(connection: AiConnection, messages: List<AiChatMessage>): AiResponse {
        val response = client.post(connection.endpoint("/v1/messages")) {
            connection.applyAuth(this)
            contentType(ContentType.Application.Json)
            setBody(aiJson.encodeToString(MessageRequest.serializer(), request(connection, messages)))
        }
        response.throwIfNotSuccess(PROVIDER_LABEL)

        val dto = aiJson.decodeFromString(MessageResponse.serializer(), response.bodyAsText())
        return AiResponse(
            content = dto.content.mapNotNull { it.text }.joinToString(""),
            model = dto.model ?: connection.model,
            tokensUsed = dto.usage?.total(),
            finishReason = dto.stopReason,
        )
    }

    override fun chatStream(connection: AiConnection, messages: List<AiChatMessage>): Flow<AiStreamChunk> = flow {
        val response = client.post(connection.endpoint("/v1/messages")) {
            connection.applyAuth(this)
            contentType(ContentType.Application.Json)
            setBody(aiJson.encodeToString(MessageRequest.serializer(), request(connection, messages, stream = true)))
        }
        response.throwIfNotSuccess(PROVIDER_LABEL)

        response.bodyAsChannel().sseData().collect { payload ->
            val event = runCatching { aiJson.decodeFromString(StreamEvent.serializer(), payload) }.getOrNull()
                ?: return@collect
            when (event.type) {
                "content_block_delta" ->
                    event.delta?.text?.takeIf { it.isNotEmpty() }?.let { emit(AiStreamChunk(delta = it)) }

                "message_delta" ->
                    event.delta?.stopReason?.let { emit(AiStreamChunk(finishReason = it)) }

                "error" ->
                    throw AiException("Claude 流式响应错误：${event.error?.message ?: "unknown"}")

                // message_start / ping / content_block_start / message_stop ... 无需转发。
                else -> Unit
            }
        }
    }

    /** POST 一条最小消息（max_tokens=1）：比 GET 更贴近真实调用路径的验证。 */
    override suspend fun testConnection(connection: AiConnection): Boolean =
        testQuietly(PROVIDER_LABEL, connection.name) {
            val response = client.post(connection.endpoint("/v1/messages")) {
                connection.applyAuth(this)
                contentType(ContentType.Application.Json)
                setBody(
                    aiJson.encodeToString(
                        MessageRequest.serializer(),
                        MessageRequest(
                            model = connection.model,
                            maxTokens = 1,
                            messages = listOf(MessageDto("user", "ping")),
                        ),
                    ),
                )
            }
            response.throwIfNotSuccess(PROVIDER_LABEL)
        }

    /* ----------------------------- 内部组装 ----------------------------- */

    private fun request(connection: AiConnection, messages: List<AiChatMessage>, stream: Boolean = false): MessageRequest {
        val turns = messages.toClaudeTurns()
        if (turns.isEmpty()) throw AiException("Claude 请求没有可发送的消息（历史为空？）")
        return MessageRequest(
            model = connection.model,
            maxTokens = MAX_TOKENS,
            system = messages.firstOrNull { it.role == AiRole.SYSTEM }?.content?.takeIf { it.isNotBlank() },
            messages = turns,
            stream = stream.takeIf { it },
        )
    }

    /**
     * role=system 抽掉（走顶层 `system` 字段）；其余按 user / assistant 输出，
     * 相邻同角色合并 —— Messages API 要求角色交替，连续两条 user 会直接 400。
     */
    private fun List<AiChatMessage>.toClaudeTurns(): List<MessageDto> {
        val turns = mutableListOf<MessageDto>()
        filter { it.role != AiRole.SYSTEM }.forEach { message ->
            val role = if (message.role == AiRole.ASSISTANT) "assistant" else "user"
            val last = turns.lastOrNull()
            if (last != null && last.role == role) {
                turns[turns.lastIndex] = last.copy(content = last.content + "\n\n" + message.content)
            } else {
                turns += MessageDto(role = role, content = message.content)
            }
        }
        return turns
    }

    private fun AiConnection.endpoint(path: String): String = baseUrlOrThrow() + path

    private fun AiConnection.applyAuth(builder: HttpRequestBuilder) {
        builder.header("x-api-key", token)
        builder.header("anthropic-version", ANTHROPIC_VERSION)
    }

    /* ----------------------------- wire 模型 ----------------------------- */

    @Serializable
    private data class MessageRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String? = null,
        val messages: List<MessageDto>,
        val stream: Boolean? = null,
    )

    @Serializable
    private data class MessageDto(val role: String, val content: String)

    @Serializable
    private data class MessageResponse(
        val model: String? = null,
        val content: List<Block> = emptyList(),
        @SerialName("stop_reason") val stopReason: String? = null,
        val usage: Usage? = null,
    ) {
        @Serializable
        data class Block(val type: String? = null, val text: String? = null)

        @Serializable
        data class Usage(
            @SerialName("input_tokens") val inputTokens: Int? = null,
            @SerialName("output_tokens") val outputTokens: Int? = null,
        ) {
            fun total(): Int? =
                if (inputTokens == null && outputTokens == null) null else (inputTokens ?: 0) + (outputTokens ?: 0)
        }
    }

    @Serializable
    private data class StreamEvent(
        val type: String? = null,
        val delta: Delta? = null,
        val error: Err? = null,
    ) {
        @Serializable
        data class Delta(val text: String? = null, @SerialName("stop_reason") val stopReason: String? = null)

        @Serializable
        data class Err(val type: String? = null, val message: String? = null)
    }
}
