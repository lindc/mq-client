package com.mqclient.ai.adapter

import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** SSE 解析的行为契约：只抽 `data:` 载荷，其余行全部跳过，通道关闭即流结束。 */
class SseParserTest {

    @Test
    fun `extracts data payloads and skips everything else`() = runBlocking {
        val body = buildString {
            append("data: one\n\n")
            append(": keep-alive\n\n")
            append("event: message_delta\n")
            append("data: two\n")
            append("data: \n\n")
            append("data: three\n")
        }

        val payloads = ByteReadChannel(body).sseData().toList()

        assertEquals(listOf("one", "two", "three"), payloads)
    }

    @Test
    fun `trims carriage returns from crlf line endings`() = runBlocking {
        val payloads = ByteReadChannel("data: hello\r\n").sseData().toList()
        assertEquals(listOf("hello"), payloads)
    }
}
