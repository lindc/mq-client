package com.mqclient.mqui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * [pollUntil] 的取消语义与失败分级契约。
 *
 * 重点是第一条：查询抛 `CancellationException` 时必须**原样上抛**，不能被当成
 * 「查询失败」继续轮询。旧实现用 `runCatching { query() }.getOrNull()`，会把取消
 * 一并吞掉 —— 用户在「启动通道」轮询期间点断开 / 关掉页面，轮询会一直跑到超时
 * （30s）才结束，而且最后报的是「超时」而不是「被取消」。
 */
class PollUntilTest {

    @Test
    fun `cancellation from query propagates instead of being swallowed`() = runBlocking {
        assertFailsWith<CancellationException> {
            pollUntil(
                command = { CommandOutcome(success = true) },
                query = { throw CancellationException("页面关闭") },
                isTarget = { false },
                intervalMs = 10,
                timeoutMs = 5_000,
            )
        }
        Unit // assertFailsWith 有返回值，不补 Unit 的话测试方法就不是 void（JUnit4 拒绝）
    }

    @Test
    fun `a failing query keeps polling and is reported on timeout`() = runBlocking {
        var attempts = 0

        val outcome = pollUntil(
            command = { CommandOutcome(success = true) },
            query = {
                attempts++
                throw IllegalStateException("断开了")
            },
            isTarget = { false },
            intervalMs = 10,
            timeoutMs = 80,
        )

        val timeout = outcome as PollOutcome.Timeout
        assertEquals("断开了", timeout.lastError, "超时结果应带上最后一次查询错误")
        assertTrue(attempts > 1, "查询失败不该终止轮询，实际只试了 $attempts 次")
    }

    @Test
    fun `returns Reached as soon as the target state shows up`() = runBlocking {
        var polled = 0

        val outcome = pollUntil(
            command = { CommandOutcome(success = true) },
            query = { (polled++).let { n -> if (n >= 2) "RUNNING" else "STARTING" } },
            isTarget = { it == "RUNNING" },
            intervalMs = 5,
            timeoutMs = 5_000,
        )

        assertEquals(PollOutcome.Reached("RUNNING"), outcome)
    }

    @Test
    fun `a failed command short-circuits without polling`() = runBlocking {
        var polled = false

        val outcome = pollUntil(
            command = { CommandOutcome(success = false, errorMessage = "MQRC 2085") },
            query = {
                polled = true
                "RUNNING"
            },
            isTarget = { it == "RUNNING" },
        )

        assertEquals(PollOutcome.CommandFailed("MQRC 2085"), outcome)
        assertTrue(!polled, "命令失败时不该进入轮询")
    }

    @Test
    fun `a null status means undecidable and keeps polling`() = runBlocking {
        var polled = 0

        val outcome = pollUntil(
            command = { CommandOutcome(success = true) },
            query = { (polled++).let { n -> if (n >= 2) "STOPPED" else null } },
            isTarget = { it == "STOPPED" },
            intervalMs = 5,
            timeoutMs = 5_000,
        )

        assertEquals(PollOutcome.Reached("STOPPED"), outcome)
        assertTrue(polled >= 3, "null 状态应继续轮询，实际轮询 $polled 次")
    }
}
