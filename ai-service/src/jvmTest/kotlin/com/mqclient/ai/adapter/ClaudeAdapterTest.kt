package com.mqclient.ai.adapter

import com.mqclient.ai.AiChatMessage
import com.mqclient.ai.AiRole
import com.mqclient.core.AiConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Claude 适配器的契约测试：system 走顶层字段、相邻同角色合并、max_tokens 必填、
 * SSE 具名事件的解析。
 */
class ClaudeAdapterTest {

    private fun connection() = AiConnection(
        id = "c-claude",
        name = "Claude 测试",
        providerId = "claude",
        baseUrl = "https://api.anthropic.com",
        token = "sk-ant-test",
        model = "claude-3-5-sonnet-latest",
    )

    private fun adapter(engine: MockEngine) = ClaudeAdapter(HttpClient(engine))

    @Test
    fun `chat sends system separately, merges adjacent turns and sums usage`() = runBlocking {
        var body = ""
        val engine = MockEngine { request ->
            assertEquals("/v1/messages", request.url.encodedPath)
            assertEquals("sk-ant-test", request.headers["x-api-key"])
            assertEquals("2023-06-01", request.headers["anthropic-version"])
            body = (request.body as TextContent).text
            respond(
                content = """{"model":"claude-3-5-sonnet-latest","content":[{"type":"text","text":"yo"}],""" +
                    """"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":7}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val response = adapter(engine).chat(
            connection(),
            listOf(
                AiChatMessage(AiRole.SYSTEM, "be nice"),
                AiChatMessage(AiRole.USER, "a"),
                AiChatMessage(AiRole.USER, "b"),
            ),
        )

        assertEquals("yo", response.content)
        assertEquals(12, response.tokensUsed)
        assertEquals("end_turn", response.finishReason)
        assertTrue(body.contains("\"system\":\"be nice\""), "system 应该是顶层字段：$body")
        assertTrue(body.contains("\"max_tokens\""), "max_tokens 是必填项：$body")
        assertTrue(body.contains("a\\n\\nb"), "相邻 user 消息应该合并：$body")
    }

    @Test
    fun `chatStream forwards text deltas and stop reason`() = runBlocking {
        val sse = buildString {
            append("event: message_start\ndata: {\"type\":\"message_start\"}\n\n")
            append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n")
            append("event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n")
            append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        }
        val engine = MockEngine {
            respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }

        val chunks = adapter(engine).chatStream(connection(), listOf(AiChatMessage(AiRole.USER, "hi"))).toList()

        assertEquals("Hi", chunks.map { it.delta }.joinToString(""))
        assertEquals("end_turn", chunks.mapNotNull { it.finishReason }.lastOrNull())
    }
}
