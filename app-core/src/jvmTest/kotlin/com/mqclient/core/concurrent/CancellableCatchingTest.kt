package com.mqclient.core.concurrent

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * [runCatchingCancellable] 的契约：业务异常进 `Result`，取消必须穿透。
 *
 * 第二半才是存在的理由 —— `runCatching` 也会把取消吞进 `Result.failure`。
 */
class CancellableCatchingTest {

    @Test
    fun `success is wrapped as Result success`() {
        val result = runCatchingCancellable { 42 }

        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `ordinary failure is captured like runCatching`() {
        val result = runCatchingCancellable { throw IllegalStateException("boom") }

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancellation is rethrown instead of captured`() {
        // 这正是 runCatching 做不到的：它会把这个异常变成 Result.failure。
        assertFailsWith<CancellationException> {
            runCatchingCancellable { throw CancellationException("用户点了停止") }
        }
    }

    @Test
    fun `cancellation rethrown from a suspend call`() = runBlocking {
        assertFailsWith<CancellationException> {
            runCatchingCancellable {
                yield()
                throw CancellationException("模块关闭")
            }
        }
        // 末尾补 Unit：JUnit4 要求测试方法 void，而 assertFailsWith 返回抛出的异常。
        Unit
    }

    @Test
    fun `plain runCatching really does swallow cancellation - the reason this exists`() {
        // 留作对照：同一个异常在 runCatching 下变成 Result.failure，不再是取消。
        // 这条用例不会失败，它是把「为什么需要新函数」钉成可执行的事实。
        val swallowed = runCatching { throw CancellationException("用户点了停止") }

        assertTrue(
            swallowed.isFailure,
            "runCatching 应当吞掉取消（若这里不再成立，说明 stdlib 行为变了）",
        )
        assertTrue(swallowed.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `chained onFailure and getOrDefault behave like runCatching`() {
        val seen = mutableListOf<String>()

        val value = runCatchingCancellable { throw IllegalStateException("net down") }
            .onFailure { seen += (it.message ?: "-") }
            .getOrDefault(emptyList<String>())

        assertTrue(value.isEmpty())
        assertEquals(listOf("net down"), seen)
    }
}
