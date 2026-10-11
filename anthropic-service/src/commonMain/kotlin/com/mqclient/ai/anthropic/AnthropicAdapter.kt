package com.mqclient.ai.anthropic

import com.mqclient.ai.AiAdapter
import com.mqclient.ai.AiChatMessage
import com.mqclient.ai.AiResponse
import com.mqclient.ai.AiRole
import com.mqclient.ai.AiStreamChunk
import com.mqclient.ai.AiException
import com.mqclient.ai.adapter.AiHttp
import com.mqclient.ai.adapter.aiJson
import com.mqclient.ai.adapter.baseUrlOrThrow
import com.mqclient.ai.adapter.sseData
import com.mqclient.ai.adapter.testQuietly
import com.mqclient.ai.adapter.throwIfNotSuccess
import com.mqclient.core.AiConnection
import kotlin.time.TimeSource
import com.mqclient.core.log.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val PROVIDER_LABEL = "Anthropic"

/**
 * 日志 tag：与 openai-service 的 `AI_ADAPTER_LOG_TAG` 同值 —— 适配器层日志共用一个
 * tag（该常量在 openai-service 是 internal，跨模块取不到，这里只能各写一份）。
 */
private const val LOG_TAG = "ai-adapter"

/** Anthropic API 的版本头（当前唯一稳定版本串）。 */
private const val API_VERSION = "2023-06-01"

/** `max_tokens` 必填：IR 没有这个概念，适配器用固定默认值（不暴露配置）。 */
private const val DEFAULT_MAX_TOKENS = 4096

/**
 * Anthropic Messages API 协议的适配器（Claude 系列）。
 *
 * - 端点：`{baseUrl}/v1/messages`（baseUrl 不带 `/v1`，路径写全）；
 * - 鉴权：`x-api-key: <token>` + `anthropic-version`（不是 OpenAI 的 Bearer）；
 * - 请求体：system 在顶层字段，messages 只有 user / assistant；`max_tokens` 必填；
 * - 流式：`stream: true` + SSE，事件类型经 data JSON 的 `type` 字段区分
 *   （message_start / content_block_delta / message_delta / message_stop / error）。
 *
 * 第一版不转换 tool_use / image 等非文本 content block（IR 只有纯文本），
 * 也不支持 prompt caching / extended thinking。
 */
class AnthropicAdapter(private val client: HttpClient) : AiAdapter {

    override val providerId: String = "anthropic"

    /** 非流式对话。 */
    override suspend fun chat(connection: AiConnection, messages: List<AiChatMessage>): AiResponse {
        // 日志只记元数据（模型、消息条数、token 用量、耗时），绝不记消息内容与 API Key。
        val start = TimeSource.Monotonic.markNow()
        Log.debug(
            LOG_TAG,
            "chat start: connection='${connection.name}' model=${connection.model} " +
                "messages=${messages.size} stream=false",
        )
        try {
            val response = client.post(connection.endpoint("/v1/messages")) {
                connection.applyAuth(this)
                contentType(ContentType.Application.Json)
                setBody(
                    aiJson.encodeToString(
                        ChatRequest.serializer(),
                        ChatRequest(
                            model = connection.model,
                            // max_tokens 刻意无默认值：aiJson 的 encodeDefaults=false 会把
                            // 等于默认值的字段整个省略，而 Anthropic 缺了它直接 400。
                            maxTokens = DEFAULT_MAX_TOKENS,
                            system = messages.systemText(),
                            messages = messages.toDto(),
                        ),
                    ),
                )
            }
            response.throwIfNotSuccess(PROVIDER_LABEL)

            val dto = aiJson.decodeFromString(ChatResponse.serializer(), response.bodyAsText())
            val result = AiResponse(
                // content 是块数组，只取 text 块拼接（tool_use / thinking 块第一版不转换）。
                content = dto.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString(""),
                model = dto.model ?: connection.model,
                tokensUsed = dto.usage?.sum(),
                finishReason = dto.stopReason,
            )
            Log.debug(
                LOG_TAG,
                "chat completed: model=${result.model} tokens=${result.tokensUsed ?: "-"} " +
                    "finish=${result.finishReason ?: "-"} chars=${result.content.length} " +
                    "duration=${start.elapsedNow().inWholeMilliseconds}ms",
            )
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.error(
                LOG_TAG,
                "chat failed: model=${connection.model} error=${e.message ?: e::class.simpleName}",
            )
            throw e
        }
    }

    /**
     * 流式对话：`message_start` 带输入 token，`content_block_delta` 的 text_delta
     * 是正文增量，`message_delta` 收尾（stop_reason + 输出 token），`message_stop`
     * 之后通道关闭。中途的 `error` 事件转 [AiException] 抛出。
     */
    override fun chatStream(connection: AiConnection, messages: List<AiChatMessage>): Flow<AiStreamChunk> = flow {
        val start = TimeSource.Monotonic.markNow()
        Log.debug(
            LOG_TAG,
            "chat stream start: connection='${connection.name}' model=${connection.model} " +
                "messages=${messages.size} stream=true",
        )
        try {
            val response = client.post(connection.endpoint("/v1/messages")) {
                connection.applyAuth(this)
                contentType(ContentType.Application.Json)
                setBody(
                    aiJson.encodeToString(
                        ChatRequest.serializer(),
                        ChatRequest(
                            model = connection.model,
                            maxTokens = DEFAULT_MAX_TOKENS,
                            system = messages.systemText(),
                            messages = messages.toDto(),
                            stream = true,
                        ),
                    ),
                )
            }
            response.throwIfNotSuccess(PROVIDER_LABEL)

            var inputTokens: Int? = null
            var deltaChars = 0
            response.bodyAsChannel().sseData().collect { payload ->
                // 解析不了的帧直接跳过（心跳 / 注释帧已由 sseData 挡掉，这里是兜底）。
                val event = runCatching { aiJson.decodeFromString(StreamEvent.serializer(), payload) }.getOrNull()
                    ?: return@collect
                when (event.type) {
                    "message_start" -> inputTokens = event.usage?.inputTokens ?: inputTokens
                    "content_block_delta" -> {
                        val text = event.delta?.text
                        if (!text.isNullOrEmpty()) {
                            deltaChars += text.length
                            emit(AiStreamChunk(delta = text))
                        }
                    }
                    "message_delta" -> {
                        val output = event.usage?.outputTokens
                        val stop = event.delta?.stopReason
                        if (stop != null || output != null) {
                            emit(
                                AiStreamChunk(
                                    finishReason = stop,
                                    promptTokens = inputTokens,
                                    completionTokens = output,
                                    totalTokens = listOfNotNull(inputTokens, output).takeIf { it.isNotEmpty() }?.sum(),
                                ),
                            )
                        }
                    }
                    "error" -> {
                        // 流内错误帧：留 WARN 痕迹后再抛，外层统一按失败收尾。
                        val detail = event.error?.message
                        Log.warn(LOG_TAG, "chat stream error frame: model=${connection.model} detail=$detail")
                        throw AiException(
                            message = "$PROVIDER_LABEL 流式响应失败" + if (detail != null) "：$detail" else "",
                        )
                    }
                }
            }
            Log.debug(
                LOG_TAG,
                "chat stream completed: model=${connection.model} chars=$deltaChars " +
                    "duration=${start.elapsedNow().inWholeMilliseconds}ms",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.error(
                LOG_TAG,
                "chat stream failed: model=${connection.model} " +
                    "error=${e.message ?: e::class.simpleName} duration=${start.elapsedNow().inWholeMilliseconds}ms",
            )
            throw e
        }
    }

    /** GET /v1/models：Anthropic 的 Models API，最便宜的真实验证请求。 */
    override suspend fun testConnection(connection: AiConnection): Boolean =
        testQuietly(PROVIDER_LABEL, connection.name) {
            val response = client.get(connection.endpoint("/v1/models")) {
                connection.applyAuth(this)
            }
            response.throwIfNotSuccess(PROVIDER_LABEL)
        }

    private fun AiConnection.endpoint(path: String): String = baseUrlOrThrow() + path

    private fun AiConnection.applyAuth(builder: HttpRequestBuilder) {
        // version 头恒发；key 为空时不发空 x-api-key（与 OpenAI 适配器对空 token 的取舍一致）。
        builder.header("anthropic-version", API_VERSION)
        if (token.isNotBlank()) builder.header("x-api-key", token)
    }

    /* ----------------------------- wire 模型 ----------------------------- */

    @Serializable
    private data class ChatRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String? = null,
        val messages: List<MessageDto>,
        val stream: Boolean? = null,
    )

    @Serializable
    private data class MessageDto(val role: String, val content: String)

    @Serializable
    private data class ChatResponse(
        val model: String? = null,
        val content: List<ContentBlock> = emptyList(),
        @SerialName("stop_reason") val stopReason: String? = null,
        val usage: Usage? = null,
    ) {
        @Serializable
        data class ContentBlock(val type: String? = null, val text: String? = null)
    }

    /** usage 两件套（Anthropic 不给 total）：流式各帧与非流式响应共用。 */
    @Serializable
    private data class Usage(
        @SerialName("input_tokens") val inputTokens: Int? = null,
        @SerialName("output_tokens") val outputTokens: Int? = null,
    ) {
        fun sum(): Int? = listOfNotNull(inputTokens, outputTokens).takeIf { it.isNotEmpty() }?.sum()
    }

    /** 流式事件：四类事件的字段并集（宽松解析，各事件只取自己关心的字段）。 */
    @Serializable
    private data class StreamEvent(
        val type: String? = null,
        val delta: StreamDelta? = null,
        val usage: Usage? = null,
        val error: ErrorBody? = null,
    ) {
        @Serializable
        data class StreamDelta(
            val type: String? = null,
            val text: String? = null,
            @SerialName("stop_reason") val stopReason: String? = null,
        )

        @Serializable
        data class ErrorBody(val message: String? = null)
    }

    /* ----------------------------- IR 转换 ----------------------------- */

    /** SYSTEM 消息 → 顶层 system 字段（多条以空行连接）；无 SYSTEM 时为 null（省略）。 */
    private fun List<AiChatMessage>.systemText(): String? =
        filter { it.role == AiRole.SYSTEM }.map { it.content }.takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")

    /** user / assistant 消息 → messages 数组（SYSTEM 已提到顶层）。 */
    private fun List<AiChatMessage>.toDto(): List<MessageDto> =
        filter { it.role != AiRole.SYSTEM }.map { MessageDto(it.role.wireName(), it.content) }

    private fun AiRole.wireName(): String = when (this) {
        AiRole.SYSTEM -> "system" // 不可达（SYSTEM 已被过滤），保持枚举完整
        AiRole.USER -> "user"
        AiRole.ASSISTANT -> "assistant"
    }
}
