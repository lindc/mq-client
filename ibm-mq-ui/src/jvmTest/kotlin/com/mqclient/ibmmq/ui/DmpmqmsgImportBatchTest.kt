package com.mqclient.ibmmq.ui

import com.mqclient.ibmmq.messaging.DmpmqmsgMessage
import com.mqclient.ibmmq.messaging.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * [importDmpmqmsgMessages] 的汇总契约：批量导入要能如实报出「成功了几条、失败了几条、
 * 第一条失败原因」。
 *
 * 为什么值得钉住：这条路径把一句话拆成 N 次 MQ put，任何一次失败都不该让整批静默消失。
 * 之前的实现是「预览里选一条、导入那一条」，界面上永远只有「已导入 MessageId: xxx」——
 * 换成整批之后，只有计数能回答「到底进去了几条」。这里不碰 broker（sender 是参数），
 * 纯逻辑。
 */
class DmpmqmsgImportBatchTest {

    private fun message(body: String) = DmpmqmsgMessage(attrs = emptyMap(), body = body.toByteArray())

    private fun ok() = SendResult.success("ID:1", 0L)
    private fun fail(reason: String) = SendResult.failure(2035, reason)

    @Test
    fun `all messages sent is counted as all success`() = runBlocking {
        val batch = listOf(message("a"), message("b"), message("c"))
        val outcome = importDmpmqmsgMessages(batch) { ok() }

        assertEquals(3, outcome.sent)
        assertEquals(0, outcome.failed)
        assertEquals(3, outcome.total)
        assertNull(outcome.firstError)
    }

    @Test
    fun `failures are counted and the first reason is kept`() = runBlocking {
        val batch = listOf(message("a"), message("b"), message("c"))
        val outcome = importDmpmqmsgMessages(batch) { msg ->
            when (msg.body.toString(Charsets.UTF_8)) {
                "b" -> fail("2035 权限不足")
                "c" -> fail("后面还有一个失败")
                else -> ok()
            }
        }

        assertEquals(1, outcome.sent)
        assertEquals(2, outcome.failed)
        assertEquals("2035 权限不足", outcome.firstError, "只留第一条失败原因，后面的不覆盖它")
    }

    @Test
    fun `every message is attempted even after a failure`() = runBlocking {
        // 一条失败不该中断整批：剩下的消息仍然要发（用户要的是「尽量都进去」）。
        var attempts = 0
        val batch = listOf(message("a"), message("b"), message("c"))
        importDmpmqmsgMessages(batch) {
            attempts++
            if (attempts == 1) fail("first") else ok()
        }
        assertEquals(3, attempts)
    }

    @Test
    fun `progress reports processed and total after each message`() = runBlocking {
        val batch = listOf(message("a"), message("b"), message("c"))
        val seen = mutableListOf<Pair<Int, Int>>()
        importDmpmqmsgMessages(batch, onProgress = { done, all -> seen += done to all }) { ok() }

        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
    }

    @Test
    fun `empty batch yields zero counts without calling the sender`() = runBlocking {
        var called = false
        val outcome = importDmpmqmsgMessages(emptyList()) {
            called = true
            ok()
        }
        assertEquals(0, outcome.total)
        assertEquals(false, called)
    }

    @Test
    fun `cancellation propagates instead of being reported as a failure`() = runBlocking {
        // 取消 ≠ 失败：调用方要靠这个异常结束「导入中」状态，不能把它吞成一计数。
        val batch = listOf(message("a"), message("b"))
        val thrown = assertFailsWith<CancellationException> {
            importDmpmqmsgMessages(batch) { throw CancellationException("dialog closed") }
        }
        assertEquals("dialog closed", thrown.message)
    }
}
