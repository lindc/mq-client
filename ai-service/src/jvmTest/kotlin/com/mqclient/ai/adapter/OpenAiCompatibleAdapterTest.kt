package com.mqclient.ai.adapter

import com.mqclient.ai.AiChatMessage
import com.mqclient.ai.AiException
import com.mqclient.ai.AiRole
import com.mqclient.core.AiConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * OpenAI 兼容适配器的契约测试（MockEngine，无真实网络）：
 * 请求形状（URL / Bearer 头）、响应解析、SSE 流式、错误映射、testConnection。
 */
class OpenAiCompatibleAdapterTest {

    private fun connection() = AiConnection(
        id = "c-openai",
        name = "测试连接",
        providerId = "openai-compatible",
        baseUrl = "https://api.openai.com/v1",
        token = "sk-test",
        model = "gpt-4o",
    )

    private fun adapter(engine: MockEngine) = OpenAiCompatibleAdapter(HttpClient(engine))

    @Test
    fun `chat posts to chat completions with bearer auth and parses the response`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/v1/chat/completions", request.url.encodedPath)
            assertEquals("Bearer sk-test", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{"model":"gpt-4o","choices":[{"message":{"content":"hi"},"finish_reason":"stop"}],""" +
                    """"usage":{"total_tokens":42}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val response = adapter(engine).chat(connection(), listOf(AiChatMessage(AiRole.USER, "hello")))

        assertEquals("hi", response.content)
        assertEquals("gpt-4o", response.model)
        assertEquals(42, response.tokensUsed)
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `chatStream emits deltas and forwards the finish reason`() = runBlocking {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val engine = MockEngine {
            respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }

        val chunks = adapter(engine).chatStream(connection(), listOf(AiChatMessage(AiRole.USER, "hi"))).toList()

        assertEquals("Hello", chunks.map { it.delta }.joinToString(""))
        assertEquals("stop", chunks.mapNotNull { it.finishReason }.lastOrNull())
    }

    @Test
    fun `non-2xx maps to AiException carrying the server message`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"error":{"message":"invalid api key"}}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val error = assertFailsWith<AiException> {
            adapter(engine).chat(connection(), listOf(AiChatMessage(AiRole.USER, "hi")))
        }

        assertEquals(401, error.statusCode)
        assertTrue(error.message!!.contains("invalid api key"))
    }

    @Test
    fun `testConnection hits the models endpoint and returns false on server error`() = runBlocking {
        val okEngine = MockEngine { request ->
            assertEquals("/v1/models", request.url.encodedPath)
            respond("""{"data":[]}""", HttpStatusCode.OK)
        }
        assertTrue(adapter(okEngine).testConnection(connection()))

        val brokenEngine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        assertFalse(adapter(brokenEngine).testConnection(connection()))
    }
}
