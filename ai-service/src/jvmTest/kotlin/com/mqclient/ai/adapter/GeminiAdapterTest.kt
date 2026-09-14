package com.mqclient.ai.adapter

import com.mqclient.ai.AiChatMessage
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Gemini 适配器的契约测试：模型进 URL 路径、x-goog-api-key 头、响应解析。 */
class GeminiAdapterTest {

    private fun connection() = AiConnection(
        id = "c-gemini",
        name = "Gemini 测试",
        providerId = "gemini",
        baseUrl = "https://generativelanguage.googleapis.com",
        token = "gm-test",
        model = "gemini-2.0-flash",
    )

    private val responseJson =
        """{"candidates":[{"content":{"parts":[{"text":"hey"}]},"finishReason":"STOP"}],""" +
            """"usageMetadata":{"totalTokenCount":9}}"""

    @Test
    fun `chat hits v1beta generateContent with the model in the path`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/v1beta/models/gemini-2.0-flash:generateContent", request.url.encodedPath)
            assertEquals("gm-test", request.headers["x-goog-api-key"])
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val response = GeminiAdapter(HttpClient(engine))
            .chat(connection(), listOf(AiChatMessage(AiRole.USER, "hi")))

        assertEquals("hey", response.content)
        assertEquals("gemini-2.0-flash", response.model)
        assertEquals(9, response.tokensUsed)
        assertEquals("STOP", response.finishReason)
    }

    @Test
    fun `chatStream parses sse chunks`() = runBlocking {
        val engine = MockEngine {
            // 真流式响应每条载荷都包在 `data: ` 帧里。
            respond("data: $responseJson\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }

        val chunks = GeminiAdapter(HttpClient(engine))
            .chatStream(connection(), listOf(AiChatMessage(AiRole.USER, "hi")))
            .toList()

        assertEquals("hey", chunks.map { it.delta }.joinToString(""))
        assertEquals("STOP", chunks.mapNotNull { it.finishReason }.lastOrNull())
    }
}
