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
import io.ktor.client.request.get
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

private const val PROVIDER_LABEL = "Gemini"

private const val MAX_OUTPUT_TOKENS = 4096

/**
 * Google Gemini 适配器（generativelanguage v1beta）。
 *
 * 与 OpenAI 协议的关键差异：
 *  - 模型在 URL 路径里（`/v1beta/models/{model}:generateContent`），不是请求体字段；
 *  - 鉴权用 `x-goog-api-key` 头；
 *  - 消息在 `contents` 里，角色是 `user` / `model`（助手叫 model）；系统提示词走
 *    顶层 `system_instruction`；
 *  - 流式走 `:streamGenerateContent?alt=sse`，每帧是一个完整响应片段。
 */
class GeminiAdapter(private val client: HttpClient) : AiAdapter {

    override val providerId: String = "gemini"

    override suspend fun chat(connection: AiConnection, messages: List<AiChatMessage>): AiResponse {
        val response = client.post(connection.generateContentUrl()) {
            connection.applyAuth(this)
            contentType(ContentType.Application.Json)
            setBody(aiJson.encodeToString(GenerateRequest.serializer(), request(messages)))
        }
        response.throwIfNotSuccess(PROVIDER_LABEL)

        val dto = aiJson.decodeFromString(GenerateResponse.serializer(), response.bodyAsText())
        val candidate = dto.candidates.firstOrNull()
        return AiResponse(
            content = candidate?.content.text().orEmpty(),
            model = connection.model,
            tokensUsed = dto.usageMetadata?.totalTokenCount,
            finishReason = candidate?.finishReason,
        )
    }

    override fun chatStream(connection: AiConnection, messages: List<AiChatMessage>): Flow<AiStreamChunk> = flow {
        val response = client.post(connection.streamContentUrl()) {
            connection.applyAuth(this)
            contentType(ContentType.Application.Json)
            setBody(aiJson.encodeToString(GenerateRequest.serializer(), request(messages)))
        }
        response.throwIfNotSuccess(PROVIDER_LABEL)

        response.bodyAsChannel().sseData().collect { payload ->
            val chunk = runCatching { aiJson.decodeFromString(GenerateResponse.serializer(), payload) }.getOrNull()
                ?: return@collect
            val candidate = chunk.candidates.firstOrNull() ?: return@collect
            val delta = candidate.content.text()
            if (delta.isNotEmpty() || candidate.finishReason != null) {
                emit(AiStreamChunk(delta = delta, finishReason = candidate.finishReason))
            }
        }
    }

    /** GET /v1beta/models：带鉴权的最便宜验证请求。 */
    override suspend fun testConnection(connection: AiConnection): Boolean =
        testQuietly(PROVIDER_LABEL, connection.name) {
            val response = client.get(connection.modelsUrl()) {
                connection.applyAuth(this)
            }
            response.throwIfNotSuccess(PROVIDER_LABEL)
        }

    /* ----------------------------- 内部组装 ----------------------------- */

    private fun request(messages: List<AiChatMessage>): GenerateRequest {
        val contents = messages.toContents()
        if (contents.isEmpty()) throw AiException("Gemini 请求没有可发送的消息（历史为空？）")
        return GenerateRequest(
            contents = contents,
            systemInstruction = messages.firstOrNull { it.role == AiRole.SYSTEM }
                ?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { ContentDto(parts = listOf(PartDto(it))) },
            generationConfig = GenerationConfigDto(maxOutputTokens = MAX_OUTPUT_TOKENS),
        )
    }

    /**
     * role=system 由调用方抽走（走 `system_instruction`）；其余映射为
     * user / model，相邻同角色合并（Gemini 推荐角色交替）。
     */
    private fun List<AiChatMessage>.toContents(): List<ContentDto> {
        val contents = mutableListOf<ContentDto>()
        filter { it.role != AiRole.SYSTEM }.forEach { message ->
            val role = if (message.role == AiRole.ASSISTANT) "model" else "user"
            val last = contents.lastOrNull()
            if (last != null && last.role == role) {
                contents[contents.lastIndex] = last.copy(parts = last.parts + PartDto(message.content))
            } else {
                contents += ContentDto(role = role, parts = listOf(PartDto(message.content)))
            }
        }
        return contents
    }

    /** 响应 / 分片里的可见文本：所有 text part 拼起来。 */
    private fun ContentDto?.text(): String = this?.parts?.mapNotNull { it.text }.orEmpty().joinToString("")

    private fun AiConnection.modelOrThrow(): String {
        val model = model.trim()
        if (model.isEmpty()) throw AiException("连接「$name」还没填模型（model）")
        return model
    }

    private fun AiConnection.generateContentUrl(): String =
        baseUrlOrThrow() + "/v1beta/models/${modelOrThrow()}:generateContent"

    private fun AiConnection.streamContentUrl(): String =
        baseUrlOrThrow() + "/v1beta/models/${modelOrThrow()}:streamGenerateContent?alt=sse"

    private fun AiConnection.modelsUrl(): String = baseUrlOrThrow() + "/v1beta/models"

    private fun AiConnection.applyAuth(builder: HttpRequestBuilder) {
        builder.header("x-goog-api-key", token)
    }

    /* ----------------------------- wire 模型 ----------------------------- */

    @Serializable
    private data class GenerateRequest(
        val contents: List<ContentDto>,
        @SerialName("system_instruction") val systemInstruction: ContentDto? = null,
        @SerialName("generation_config") val generationConfig: GenerationConfigDto? = null,
    )

    @Serializable
    private data class ContentDto(val role: String? = null, val parts: List<PartDto> = emptyList())

    @Serializable
    private data class PartDto(val text: String? = null)

    @Serializable
    private data class GenerationConfigDto(@SerialName("max_output_tokens") val maxOutputTokens: Int? = null)

    @Serializable
    private data class GenerateResponse(
        val candidates: List<Candidate> = emptyList(),
        @SerialName("usageMetadata") val usageMetadata: UsageMetadata? = null,
    ) {
        @Serializable
        data class Candidate(
            val content: ContentDto? = null,
            @SerialName("finishReason") val finishReason: String? = null,
        )

        @Serializable
        data class UsageMetadata(@SerialName("totalTokenCount") val totalTokenCount: Int? = null)
    }
}
