package com.mqclient.starrocks.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 面板取数错误出口的守卫：**取消不是失败**，其余异常才进错误态。
 *
 * 这组用例锁的是 [runPanelLoad] / [runOptional] 这两个机制，而不是某个面板 ——
 * 面板只是它们的调用点。之前 4 个面板各有一次「把 CancellationException 当成业务
 * 错误」的缺陷，根因都是裸 `catch (Throwable)`；收成函数后只要这两个函数语义没错，
 * 调用点就不可能再错。
 *
 * 反向验证过：把 `runPanelLoad` 里的 `catch (cancellation: CancellationException)
 * { throw cancellation }` 删掉，前两条用例会红。
 */
class StarRocksPanelLoadTest {

    @Test
    fun `cancellation propagates and never reaches the error handler`() = runBlocking {
        var handled: Throwable? = null

        try {
            runPanelLoad(onError = { handled = it }) {
                throw CancellationException("Job was cancelled")
            }
            fail("取消必须上抛，不能返回 null")
        } catch (expected: CancellationException) {
            assertEquals("Job was cancelled", expected.message)
        }

        assertNull(handled, "取消不能进错误处理（否则界面会弹 Job was cancelled）")
    }

    @Test
    fun `ordinary failure is reported and yields null`() = runBlocking {
        val boom = IllegalStateException("connection refused")
        var handled: Throwable? = null

        val result = runPanelLoad(onError = { handled = it }) { throw boom }

        assertNull(result)
        assertSame(boom, handled, "业务异常必须原样交给调用方（它决定怎么展示）")
    }

    @Test
    fun `success passes the value through`() = runBlocking {
        var handled: Throwable? = null

        val result = runPanelLoad(onError = { handled = it }) { 42 }

        assertEquals(42, result)
        assertNull(handled)
    }

    @Test
    fun `cancellation propagates through runOptional too`() {
        try {
            runOptional<Int> { throw CancellationException("cancelled") }
            fail("取消必须上抛，不能被降级成 null")
        } catch (expected: CancellationException) {
            // 期望路径
        }
    }

    @Test
    fun `runOptional degrades ordinary failures to null`() {
        assertNull(runOptional { throw IllegalStateException("optional part failed") })
        assertEquals("ok", runOptional { "ok" })
    }

    /**
     * `Error` 不是业务失败：面板显示一行红字救不回 OOM，而且它会被上层当成
     * 「查询失败」继续重试。这里断言它照旧上抛。
     */
    @Test
    fun `error is not swallowed as a business failure`() = runBlocking {
        var handled: Throwable? = null
        try {
            runPanelLoad(onError = { handled = it }) { throw AssertionError("simulated Error") }
            fail("Error 必须上抛")
        } catch (expected: AssertionError) {
            assertTrue(expected.message!!.contains("simulated"))
        }
        assertNull(handled, "Error 不应进业务错误处理")
    }
}
