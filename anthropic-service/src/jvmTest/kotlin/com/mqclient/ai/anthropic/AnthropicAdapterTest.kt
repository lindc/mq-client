package com.mqclient.ai.anthropic

import com.mqclient.ai.AiChatMessage
import com.mqclient.ai.AiException
import com.mqclient.ai.AiRole
import com.mqclient.ai.adapter.aiJson
import com.mqclient.core.AiConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Anthropic 适配器的契约测试（MockEngine，无真实网络）：
 * 请求形状（URL / x-api-key / system 顶层 / max_tokens）、content 块解析、
 * SSE 事件序列映射、中途 error 事件、错误映射、testConnection。
 */
class AnthropicAdapterTest {

    private fun connection(token: String = "sk-ant-test") = AiConnection(
        id = "c-anthropic",
        name = "测试连接",
        providerId = "anthropic",
        baseUrl = "https://api.anthropic.com",
        token = token,
        model = "claude-sonnet-4-5",
    )

    private fun adapter(engine: MockEngine) = AnthropicAdapter(HttpClient(engine))

    /** MockEngine 请求体：setBody(String) 生成 TextContent，直接取 text。 */
    private fun requestBody(request: HttpRequestData): String =
        (request.body as TextContent).text

    @Test
    fun `chat posts to messages with anthropic auth and flattens text blocks`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/v1/messages", request.url.encodedPath)
            assertEquals("sk-ant-test", request.headers["x-api-key"])
            assertEquals("2023-06-01", request.headers["anthropic-version"])

            val body = requestBody(request).let { aiJson.parseToJsonElement(it).jsonObject }
            assertEquals("claude-sonnet-4-5", body["model"]!!.jsonPrimitive.content)
            assertEquals(4096, body["max_tokens"]!!.jsonPrimitive.content.toInt())
            assertEquals("You are helpful", body["system"]!!.jsonPrimitive.content)
            val messages = body["messages"]!!.jsonArray
            assertEquals(3, messages.size, "SYSTEM 提到顶层，messages 只剩 user/assistant")
            assertEquals(
                listOf("user", "assistant", "user"),
                messages.map { it.jsonObject["role"]!!.jsonPrimitive.content },
            )

            respond(
                content = """
                    {"model":"claude-sonnet-4-5",
                     "content":[{"type":"text","text":"Hi "},{"type":"tool_use","id":"t1"},{"type":"text","text":"there"}],
                     "stop_reason":"end_turn",
                     "usage":{"input_tokens":10,"output_tokens":7}}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val response = adapter(engine).chat(
            connection(),
            listOf(
                AiChatMessage(AiRole.SYSTEM, "You are helpful"),
                AiChatMessage(AiRole.USER, "hello"),
                AiChatMessage(AiRole.ASSISTANT, "hi"),
                AiChatMessage(AiRole.USER, "again"),
            ),
        )

        assertEquals("Hi there", response.content, "只拼接 text 块，tool_use 块跳过")
        assertEquals("claude-sonnet-4-5", response.model)
        assertEquals(17, response.tokensUsed, "input + output")
        assertEquals("end_turn", response.finishReason)
    }

    @Test
    fun `chat without token omits x-api-key but keeps the version header`() = runBlocking {
        val engine = MockEngine { request ->
            assertNull(request.headers["x-api-key"])
            assertEquals("2023-06-01", request.headers["anthropic-version"])
            respond("""{"content":[{"type":"text","text":"ok"}]}""", HttpStatusCode.OK)
        }

        val response = adapter(engine).chat(
            connection(token = ""),
            listOf(AiChatMessage(AiRole.USER, "hi")),
        )
        assertEquals("ok", response.content)
    }

    @Test
    fun `chatStream maps anthropic events to deltas and a final usage chunk`() = runBlocking {
        val sse = buildString {
            append("event: message_start\n")
            append("data: {\"type\":\"message_start\",\"usage\":{\"input_tokens\":10}}\n\n")
            append("event: content_block_delta\n")
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}\n\n")
            append("event: content_block_delta\n")
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}\n\n")
            append("event: message_delta\n")
            append("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7}}\n\n")
            append("event: message_stop\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
        val engine = MockEngine { request ->
            assertEquals("/v1/messages", request.url.encodedPath)
            // encodeDefaults=false：stream=true 是显式传参必须出现在请求体里；
            // max_tokens 同理（无默认值的必填字段恒发）。
            val body = requestBody(request)
            assertTrue(body.contains("\"stream\":true") && body.contains("\"max_tokens\":4096"))
            respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }

        val chunks = adapter(engine).chatStream(connection(), listOf(AiChatMessage(AiRole.USER, "hi"))).toList()

        assertEquals("Hello", chunks.map { it.delta }.joinToString(""), "两个 text_delta 拼成全文")
        val last = chunks.last()
        assertEquals("end_turn", last.finishReason)
        assertEquals(10, last.promptTokens)
        assertEquals(7, last.completionTokens)
        assertEquals(17, last.totalTokens)
        assertNull(chunks.first().finishReason)
    }

    @Test
    fun `mid-stream error event maps to AiException carrying the server message`() = runBlocking {
        val sse = buildString {
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"par\"}}\n\n")
            append("data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n")
        }
        val engine = MockEngine {
            respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }

        val error = assertFailsWith<AiException> {
            adapter(engine).chatStream(connection(), listOf(AiChatMessage(AiRole.USER, "hi"))).toList()
        }
        assertTrue(error.message!!.contains("Overloaded"))
    }

    @Test
    fun `non-2xx maps to AiException carrying the server message`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val error = assertFailsWith<AiException> {
            adapter(engine).chat(connection(), listOf(AiChatMessage(AiRole.USER, "hi")))
        }
        assertEquals(401, error.statusCode)
        assertTrue(error.message!!.contains("invalid x-api-key"))
    }

    @Test
    fun `testConnection hits the models endpoint and returns false on server error`() = runBlocking {
        val okEngine = MockEngine { request ->
            assertEquals("/v1/models", request.url.encodedPath)
            assertEquals("sk-ant-test", request.headers["x-api-key"])
            respond("""{"data":[]}""", HttpStatusCode.OK)
        }
        assertTrue(adapter(okEngine).testConnection(connection()))

        val brokenEngine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        assertFalse(adapter(brokenEngine).testConnection(connection()))
    }

    /**
     * 非流式请求必须有整体超时（AiHttp 的 requestTimeoutMillis 为 SSE 设成了 null）。
     * 用永不返回的引擎验证 chat() 在超时后抛带连接名的 AiException。
     */
    @Test
    fun `chat gives up when the server never finishes the response`() = runBlocking {
        val hangingEngine = MockEngine {
            delay(30_000) // 远大于下面的超时；delay 可被取消
            respond("late", HttpStatusCode.OK)
        }
        val subject = AnthropicAdapter(
            clientFor = { HttpClient(hangingEngine) },
            nonStreamingTimeoutMs = 150,
        )
        val target = connection()

        val error = assertFailsWith<AiException> {
            subject.chat(target, listOf(AiChatMessage(AiRole.USER, "hi")))
        }

        assertTrue(error.message.orEmpty().contains("Request timed out"), "应是超时文案，实际：${error.message}")
        assertTrue(
            error.message.orEmpty().contains(target.name),
            "超时文案应带连接名，实际：${error.message}",
        )
    }
}
