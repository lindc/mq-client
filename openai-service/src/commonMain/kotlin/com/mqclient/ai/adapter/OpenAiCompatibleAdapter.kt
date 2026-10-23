package com.mqclient.ai.adapter

import com.mqclient.ai.AiAdapter
import com.mqclient.ai.AiChatMessage
import com.mqclient.ai.AiResponse
import com.mqclient.ai.AiRole
import com.mqclient.ai.AiStreamChunk
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
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
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
 *
 * HTTP 客户端经 [clientFor] 按连接取：`ignoreSsl=true` 的连接走 trust-all 客户端
 * （见 [AiHttp.clientFor]），默认走严格校验的共享客户端。旧构造（固定 client）保留
 * —— 适配器契约测试的 MockEngine 注入路径不变。
 */
class OpenAiCompatibleAdapter(
    private val clientFor: (AiConnection) -> HttpClient,
    /**
     * 非流式请求的整体超时（毫秒）：默认 60s，测试传小值以验证超时路径。
     * 流式（[chatStream]）不走它 —— 流式可能正当持续数分钟。
     */
    private val nonStreamingTimeoutMs: Long = NON_STREAMING_TIMEOUT_MS,
) : AiAdapter {

    /** 固定客户端的旧构造：测试注入 MockEngine 等场景（不区分连接）。 */
    constructor(client: HttpClient) : this({ _ -> client })

    override val providerId: String = "openai-compatible"

    /** 非流式对话。刻意不传 max_tokens：交给服务端默认值，兼容面最大。 */
    override suspend fun chat(connection: AiConnection, messages: List<AiChatMessage>): AiResponse {
        // 日志只记元数据（模型、消息条数、token 用量、耗时），绝不记消息内容与 token。
        val start = TimeSource.Monotonic.markNow()
        Log.debug(
            AI_ADAPTER_LOG_TAG,
            "chat start: connection='${connection.name}' model=${connection.model} " +
                "messages=${messages.size} stream=false",
        )
        try {
            // 非流式请求必须有整体超时：AiHttp 为 SSE 把 requestTimeoutMillis 设成了 null
            // （流式可能持续数分钟），但 chat() 复用了同一个客户端 —— 服务端只要
            // 「细水长流」地吐字节，socketTimeout 的 120s 永远不触发，面板就一直是
            // 「正在回复」，用户只能点停止。
            val result = withNonStreamingDeadline(connection.name, nonStreamingTimeoutMs) {
                val response = clientFor(connection).post(connection.endpoint("/chat/completions")) {
                    connection.applyAuth(this)
                    contentType(ContentType.Application.Json)
                    setBody(
                        aiJson.encodeToString(
                            ChatRequest.serializer(),
                            ChatRequest(connection.model, messages.toDto()),
                        ),
                    )
                }
                response.throwIfNotSuccess(PROVIDER_LABEL)

                val dto = aiJson.decodeFromString(ChatResponse.serializer(), response.bodyAsText())
                val choice = dto.choices.firstOrNull()
                AiResponse(
                    content = choice?.message?.content.orEmpty(),
                    model = dto.model ?: connection.model,
                    tokensUsed = dto.usage?.totalTokens,
                    finishReason = choice?.finishReason,
                )
            }
            Log.debug(
                AI_ADAPTER_LOG_TAG,
                "chat completed: model=${result.model} tokens=${result.tokensUsed ?: "-"} " +
                    "finish=${result.finishReason ?: "-"} chars=${result.content.length} " +
                    "duration=${start.elapsedNow().inWholeMilliseconds}ms",
            )
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.error(
                AI_ADAPTER_LOG_TAG,
                "chat failed: model=${connection.model} error=${e.message ?: e::class.simpleName}",
            )
            throw e
        }
    }

    /** 流式对话：逐帧解析 `choices[0].delta.content`；usage 末帧经 [AiStreamChunk] 透出。 */
    override fun chatStream(connection: AiConnection, messages: List<AiChatMessage>): Flow<AiStreamChunk> = flow {
        val start = TimeSource.Monotonic.markNow()
        Log.debug(
            AI_ADAPTER_LOG_TAG,
            "chat stream start: connection='${connection.name}' model=${connection.model} " +
                "messages=${messages.size} stream=true",
        )
        try {
            val response = clientFor(connection).post(connection.endpoint("/chat/completions")) {
                connection.applyAuth(this)
                contentType(ContentType.Application.Json)
                setBody(
                    aiJson.encodeToString(
                        ChatRequest.serializer(),
                        // include_usage：OpenAI 规范行为，规范服务会在 [DONE] 前发一帧只有
                        // usage 的收尾帧；不兼容的服务要么忽略、要么报错（后者值得暴露）。
                        ChatRequest(
                            connection.model,
                            messages.toDto(),
                            stream = true,
                            // aiJson 没开 encodeDefaults：布尔字段必须显式传参才不会被省略。
                            streamOptions = StreamOptions(includeUsage = true),
                        ),
                    ),
                )
            }
            response.throwIfNotSuccess(PROVIDER_LABEL)

            var deltaChars = 0
            response.bodyAsChannel().sseData().collect { payload ->
                if (payload == DONE_SENTINEL) return@collect
                // 解析不了的帧直接跳过：部分兼容服务会插非标准的心跳 / 注释帧。
                val chunk = runCatching { aiJson.decodeFromString(StreamChunk.serializer(), payload) }.getOrNull()
                    ?: return@collect
                // usage 收尾帧的 choices 为空（且 delta 为空），必须先于 choices 判空检查。
                val usage = chunk.usage
                if (usage != null) {
                    emit(
                        AiStreamChunk(
                            promptTokens = usage.promptTokens,
                            completionTokens = usage.completionTokens,
                            totalTokens = usage.totalTokens,
                        ),
                    )
                }
                val choice = chunk.choices.firstOrNull() ?: return@collect
                val delta = choice.delta?.content.orEmpty()
                if (delta.isNotEmpty() || choice.finishReason != null) {
                    deltaChars += delta.length
                    emit(AiStreamChunk(delta = delta, finishReason = choice.finishReason))
                }
            }
            Log.debug(
                AI_ADAPTER_LOG_TAG,
                "chat stream completed: model=${connection.model} chars=$deltaChars " +
                    "duration=${start.elapsedNow().inWholeMilliseconds}ms",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.error(
                AI_ADAPTER_LOG_TAG,
                "chat stream failed: model=${connection.model} " +
                    "error=${e.message ?: e::class.simpleName} duration=${start.elapsedNow().inWholeMilliseconds}ms",
            )
            throw e
        }
    }

    /** GET /models：最便宜的真实验证请求（OpenAI / Ollama / DeepSeek 都实现了它）。 */
    override suspend fun testConnection(connection: AiConnection): Boolean =
        testQuietly(PROVIDER_LABEL, connection.name) {
            val response = clientFor(connection).get(connection.endpoint("/models")) {
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
        @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    )

    /**
     * OpenAI 的 `stream_options.include_usage`：要求流末尾附一帧只有 usage 的收尾帧。
     * includeUsage 刻意不给默认值 —— aiJson 的 encodeDefaults=false 会把等于默认值的
     * 字段整个省略掉，那样这个开关就永远发不出去了。
     */
    @Serializable
    private data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean)

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
    }

    /** usage 三件套：流式收尾帧与非流式响应共用。 */
    @Serializable
    private data class Usage(
        @SerialName("total_tokens") val totalTokens: Int? = null,
        @SerialName("prompt_tokens") val promptTokens: Int? = null,
        @SerialName("completion_tokens") val completionTokens: Int? = null,
    )

    @Serializable
    private data class StreamChunk(
        val choices: List<Choice> = emptyList(),
        /** `include_usage` 开启时服务端在流末尾发的统计帧；多数帧上为 null。 */
        val usage: Usage? = null,
    ) {
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
