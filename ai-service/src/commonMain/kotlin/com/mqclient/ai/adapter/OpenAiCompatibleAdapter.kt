package com.mqclient.ai.adapter

import com.mqclient.ai.AiAdapter
import com.mqclient.ai.AiChatMessage
import com.mqclient.ai.AiResponse
import com.mqclient.ai.AiRole
import com.mqclient.ai.AiStreamChunk
import com.mqclient.core.AiConnection
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val PROVIDER_LABEL = "OpenAI 兼容"

/** OpenAI 协议在流末尾发送的哨兵帧。 */
private const val DONE_SENTINEL = "[DONE]"

/**
 * OpenAI Chat Completions 协议的适配器 —— 一个适配器覆盖 OpenAI、Ollama、DeepSeek、
 * Moonshot 等所有兼容服务。
 *
 * - 端点：`{baseUrl}/chat/completions`（baseUrl 要带 `/v1` 之类的版本段，
 *   Provider 预置默认值已经带好）；
 * - 鉴权：`Authorization: Bearer <token>`；token 为空则不带头（本地 Ollama）；
 * - 流式：`stream: true` + SSE，`data: [DONE]` 结束。
 */
class OpenAiCompatibleAdapter(private val client: HttpClient) : AiAdapter {

    override val providerId: String = "openai-compatible"

    /** 非流式对话。刻意不传 max_tokens：交给服务端默认值，兼容面最大。 */
    override suspend fun chat(connection: AiConnection, messages: List<AiChatMessage>): AiResponse {
        val response = client.post(connection.endpoint("/chat/completions")) {
            connection.applyAuth(this)
            contentType(ContentType.Application.Json)
            setBody(aiJson.encodeToString(ChatRequest.serializer(), ChatRequest(connection.model, messages.toDto())))
        }
        response.throwIfNotSuccess(PROVIDER_LABEL)

        val dto = aiJson.decodeFromString(ChatResponse.serializer(), response.bodyAsText())
        val choice = dto.choices.firstOrNull()
        return AiResponse(
            content = choice?.message?.content.orEmpty(),
            model = dto.model ?: connection.model,
            tokensUsed = dto.usage?.totalTokens,
            finishReason = choice?.finishReason,
        )
    }

    /** 流式对话：逐帧解析 `choices[0].delta.content`。 */
    override fun chatStream(connection: AiConnection, messages: List<AiChatMessage>): Flow<AiStreamChunk> = flow {
        val response = client.post(connection.endpoint("/chat/completions")) {
            connection.applyAuth(this)
            contentType(ContentType.Application.Json)
            setBody(
                aiJson.encodeToString(
                    ChatRequest.serializer(),
                    ChatRequest(connection.model, messages.toDto(), stream = true),
                ),
            )
        }
        response.throwIfNotSuccess(PROVIDER_LABEL)

        response.bodyAsChannel().sseData().collect { payload ->
            if (payload == DONE_SENTINEL) return@collect
            // 解析不了的帧直接跳过：部分兼容服务会插非标准的心跳 / 注释帧。
            val chunk = runCatching { aiJson.decodeFromString(StreamChunk.serializer(), payload) }.getOrNull()
                ?: return@collect
            val choice = chunk.choices.firstOrNull() ?: return@collect
            val delta = choice.delta?.content.orEmpty()
            if (delta.isNotEmpty() || choice.finishReason != null) {
                emit(AiStreamChunk(delta = delta, finishReason = choice.finishReason))
            }
        }
    }

    /** GET /models：最便宜的真实验证请求（OpenAI / Ollama / DeepSeek 都实现了它）。 */
    override suspend fun testConnection(connection: AiConnection): Boolean =
        testQuietly(PROVIDER_LABEL, connection.name) {
            val response = client.get(connection.endpoint("/models")) {
                connection.applyAuth(this)
            }
            response.throwIfNotSuccess(PROVIDER_LABEL)
        }

    private fun AiConnection.endpoint(path: String): String = baseUrlOrThrow() + path

    private fun AiConnection.applyAuth(builder: HttpRequestBuilder) {
        // 不带 token 的本地服务（如 Ollama）不该收到一个空的 Authorization 头。
        if (token.isNotBlank()) builder.header(HttpHeaders.Authorization, "Bearer $token")
    }

    /* ----------------------------- wire 模型 ----------------------------- */

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<MessageDto>,
        val stream: Boolean? = null,
    )

    @Serializable
    private data class MessageDto(val role: String, val content: String)

    @Serializable
    private data class ChatResponse(
        val model: String? = null,
        val choices: List<Choice> = emptyList(),
        val usage: Usage? = null,
    ) {
        @Serializable
        data class Choice(
            val message: Message? = null,
            @SerialName("finish_reason") val finishReason: String? = null,
        )

        @Serializable
        data class Message(val content: String? = null)

        @Serializable
        data class Usage(@SerialName("total_tokens") val totalTokens: Int? = null)
    }

    @Serializable
    private data class StreamChunk(val choices: List<Choice> = emptyList()) {
        @Serializable
        data class Choice(
            val delta: Delta? = null,
            @SerialName("finish_reason") val finishReason: String? = null,
        )

        @Serializable
        data class Delta(val content: String? = null)
    }

    private fun List<AiChatMessage>.toDto(): List<MessageDto> = map { MessageDto(it.role.wireName(), it.content) }

    private fun AiRole.wireName(): String = when (this) {
        AiRole.SYSTEM -> "system"
        AiRole.USER -> "user"
        AiRole.ASSISTANT -> "assistant"
    }
}
