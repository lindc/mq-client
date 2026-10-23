package com.mqclient.core.concurrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [KeyedJobRegistry] 的行为契约：**同 key 顶掉旧的、不同 key 互不影响、结束后自动摘表**。
 *
 * 这些契约对应 P0-2：手动刷新（UI 按钮）的协程必须能被「断开连接」精确取消，
 * 而且取消一条连接不能波及另一条。
 */
class KeyedJobRegistryTest {

    @Test
    fun `launch runs the block`() = runBlocking {
        val registry = KeyedJobRegistry()
        val ran = CompletableDeferred<Unit>()

        registry.launch(this, "c1") { ran.complete(Unit) }.join()

        assertTrue(ran.isCompleted, "block 应该被执行")
    }

    @Test
    fun `launch with the same key cancels the previous one`() = runBlocking {
        val registry = KeyedJobRegistry()
        val started = CompletableDeferred<Unit>()
        val first = registry.launch(this, "c1") {
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()

        registry.launch(this, "c1") { }.join()
        first.join()

        assertTrue(first.isCancelled, "同一 key 的新协程应该取消旧的")
        awaitActive(registry) { it.isEmpty() }
    }

    @Test
    fun `different keys are independent`() = runBlocking {
        val registry = KeyedJobRegistry()
        val started = List(2) { CompletableDeferred<Unit>() }
        val jobs = List(2) { i ->
            registry.launch(this, "c$i") {
                started[i].complete(Unit)
                awaitCancellation()
            }
        }
        started.forEach { it.await() }

        assertTrue(registry.cancel("c0"))
        jobs[0].join()

        assertTrue(jobs[0].isCancelled)
        assertFalse(jobs[1].isCancelled, "另一个 key 上的协程不该被波及")
        assertEquals(setOf("c1"), registry.activeKeys)
        registry.cancelAll()
    }

    @Test
    fun `finished jobs are removed from the registry`() = runBlocking {
        val registry = KeyedJobRegistry()

        registry.launch(this, "c1") { }.join()

        awaitActive(registry) { it.isEmpty() }
        assertTrue(registry.activeKeys.isEmpty(), "跑完的协程不该留在表里")
    }

    @Test
    fun `cancel returns false for an unknown key`() = runBlocking {
        val registry = KeyedJobRegistry()

        assertFalse(registry.cancel("nope"))
    }

    @Test
    fun `cancelAll cancels every registered job`() = runBlocking {
        val registry = KeyedJobRegistry()
        val started = List(3) { CompletableDeferred<Unit>() }
        val jobs = List(3) { i ->
            registry.launch(this, "c$i") {
                started[i].complete(Unit)
                awaitCancellation()
            }
        }
        started.forEach { it.await() }

        registry.cancelAll()
        jobs.forEach { it.join() }

        jobs.forEach { assertTrue(it.isCancelled) }
        awaitActive(registry) { it.isEmpty() }
    }

    @Test
    fun `blank keys are rejected`() = runBlocking {
        val registry = KeyedJobRegistry()

        assertFailsWith<IllegalArgumentException> { registry.launch(this, "  ") { } }
        assertTrue(registry.activeKeys.isEmpty(), "被拒绝的 key 不该留下登记")
    }

    /** 完成回调与 `join()` 之间没有严格的先后保证，用「有界轮询」等它收敛。 */
    private suspend fun awaitActive(
        registry: KeyedJobRegistry,
        predicate: (Set<String>) -> Boolean,
    ) {
        withTimeout(2_000) {
            while (!predicate(registry.activeKeys)) delay(10)
        }
    }
}
