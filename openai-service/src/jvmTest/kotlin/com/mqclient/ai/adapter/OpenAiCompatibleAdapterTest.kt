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
import kotlinx.coroutines.delay
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

    /**
     * 非流式请求必须有整体超时：AiHttp 为 SSE 把 requestTimeoutMillis 设成了 null，
     * 服务端只要一直不把响应体写完，socketTimeout 就永远不触发 —— 面板会一直停在
     * 「正在回复」。这里用一个永不返回的引擎，断言 chat() 在超时后抛带连接名的 AiException。
     */
    @Test
    fun `chat gives up when the server never finishes the response`() = runBlocking {
        val hangingEngine = MockEngine {
            delay(30_000) // 远大于下面的超时；delay 可被取消
            respond("late", HttpStatusCode.OK)
        }
        val subject = OpenAiCompatibleAdapter(
            clientFor = { HttpClient(hangingEngine) },
            nonStreamingTimeoutMs = 150,
        )

        val error = assertFailsWith<AiException> {
            subject.chat(connection(), listOf(AiChatMessage(AiRole.USER, "hi")))
        }

        assertTrue(
            error.message.orEmpty().contains("超时"),
            "应是超时文案，实际：${error.message}",
        )
        assertTrue(
            error.message.orEmpty().contains(connection().name),
            "超时文案应带连接名，实际：${error.message}",
        )
    }

    /**
     * HTTP 200 + 错误体（流式）：有的兼容服务不回 4xx，而是在 200 的 SSE 流里给一帧
     * `{"error":…}`，甚至整个 body 就是这一帧。旧实现把它当「解析不了的帧」跳过，
     * 最终返回空回复——界面只看到一个空气泡。
     */
    @Test
    fun `chatStream surfaces an error payload delivered with HTTP 200`() = runBlocking {
        val sse = "data: {\"error\":{\"message\":\"insufficient quota\"}}\n\n"
        val engine = MockEngine {
            respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }

        val error = assertFailsWith<AiException> {
            adapter(engine).chatStream(connection(), listOf(AiChatMessage(AiRole.USER, "hi"))).toList()
        }

        assertTrue(
            error.message.orEmpty().contains("insufficient quota"),
            "错误文案应带出服务端 detail，实际：${error.message}",
        )
    }

    /** HTTP 200 + 错误体（非流式）：同一类服务的非流式分支（choices 缺失会解成空回复）。 */
    @Test
    fun `chat surfaces an error payload delivered with HTTP 200`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"error":{"message":"model not found"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val error = assertFailsWith<AiException> {
            adapter(engine).chat(connection(), listOf(AiChatMessage(AiRole.USER, "hi")))
        }

        assertTrue(
            error.message.orEmpty().contains("model not found"),
            "错误文案应带出服务端 detail，实际：${error.message}",
        )
    }
}
