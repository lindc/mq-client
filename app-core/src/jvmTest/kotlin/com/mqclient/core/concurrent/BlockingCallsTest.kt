package com.mqclient.core.concurrent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * [BlockingCalls.withDeadline] 的契约：**等待有上限、放弃不等于打断、迟到的产出必被释放**。
 *
 * 这组测试守的是 P1-1：`MQQueueManager` 构造与 `PCFMessageAgent.send` 都是纯阻塞调用，
 * `withTimeout` 在它们身上不生效。这里用 `Thread.sleep` 模拟阻塞调用（同样不响应中断）。
 */
class BlockingCallsTest {

    @Test
    fun `returns the value when the call finishes in time`() {
        var released = false

        val value = runBlocking {
            BlockingCalls.withDeadline(
                timeoutMs = 5_000,
                label = "快调用",
                onAbandoned = { released = true },
            ) { 42 }
        }

        assertEquals(42, value)
        assertTrue(!released, "正常返回不该触发 onAbandoned")
    }

    @Test
    fun `times out and reports the label when the call hangs`() {
        val started = CountDownLatch(1)

        val error = assertFailsWith<BlockingCallTimeoutException> {
            runBlocking {
                BlockingCalls.withDeadline(timeoutMs = 150, label = "连接 QM1") {
                    started.countDown()
                    Thread.sleep(1_000) // 模拟不可打断的阻塞调用
                }
            }
        }

        assertTrue(started.await(2, TimeUnit.SECONDS), "阻塞调用应该已经开始")
        assertTrue(
            error.message.orEmpty().contains("连接 QM1"),
            "超时文案应带上主语，实际：${error.message}",
        )
    }

    @Test
    fun `hands the late result to onAbandoned exactly once`() {
        val released = AtomicReference<Int?>(null)
        val releaseCount = AtomicInteger(0)

        assertFailsWith<BlockingCallTimeoutException> {
            runBlocking {
                BlockingCalls.withDeadline(
                    timeoutMs = 100,
                    label = "连接 QM1",
                    onAbandoned = { late ->
                        releaseCount.incrementAndGet()
                        released.set(late)
                    },
                ) {
                    Thread.sleep(300)
                    7 // 迟到的产出
                }
            }
        }

        // 产出在 300ms 左右到达，给收尾线程留出时间。
        val deadline = System.currentTimeMillis() + 5_000
        while (released.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        assertEquals(7, released.get(), "被放弃的连接最终必须被交出来释放")
        // 再等一小会儿，确认没有第二次释放。
        Thread.sleep(100)
        assertEquals(1, releaseCount.get(), "onAbandoned 只应被调用一次")
    }

    @Test
    fun `propagates the original exception without the Future wrapper`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                BlockingCalls.withDeadline(timeoutMs = 5_000, label = "失败调用") {
                    throw IllegalArgumentException("配置不对")
                }
            }
        }

        assertEquals("配置不对", thrown.message)
    }

    @Test
    fun `cancels promptly and still releases the abandoned result`() = runBlocking(Dispatchers.Default) {
        val released = AtomicReference<String?>(null)
        val began = CountDownLatch(1)

        val job = async {
            BlockingCalls.withDeadline(
                timeoutMs = 60_000, // 远大于取消发生的时刻
                label = "长扫描",
                onAbandoned = { late -> released.set(late) },
            ) {
                began.countDown()
                Thread.sleep(400)
                "会话"
            }
        }
        assertTrue(began.await(2, TimeUnit.SECONDS))
        // 给协程一点时间进入等待。
        delay(100)
        val cancelAt = System.currentTimeMillis()
        job.cancel()

        assertFailsWith<CancellationException> { job.await() }
        val elapsed = System.currentTimeMillis() - cancelAt
        assertTrue(elapsed < 1_500, "取消不该等到 60s 超时上限，实际等了 ${elapsed}ms")

        val deadline = System.currentTimeMillis() + 5_000
        while (released.get() == null && System.currentTimeMillis() < deadline) {
            delay(20)
        }
        assertEquals("会话", released.get(), "取消后迟到的产出也要释放")
    }

    @Test
    fun `runs more than four blocking calls at the same time`() = runBlocking(Dispatchers.Default) {
        // 「核心 4 + LinkedBlockingQueue(256)」的旧池只会真正并发 4 个：队列不满就永不扩容。
        // 六路调用必须能同时拿到执行槽 —— 只要还有一路拿不到，栅栏就永远等不齐。
        val concurrency = 6
        val barrier = CountDownLatch(concurrency)

        val results = (1..concurrency).map { index ->
            async {
                BlockingCalls.withDeadline(timeoutMs = 20_000, label = "并发 $index") {
                    barrier.countDown()
                    if (!barrier.await(10, TimeUnit.SECONDS)) {
                        throw AssertionError("只有 4 个 worker：第 5/6 路阻塞调用拿不到执行槽")
                    }
                    index
                }
            }
        }.awaitAll()

        assertEquals((1..concurrency).toList(), results.sorted(), "六路并发都该正常返回")
    }

    @Test
    fun `rejects a non-positive timeout`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                BlockingCalls.withDeadline(timeoutMs = 0, label = "零超时") { 1 }
            }
        }
    }

    @Test
    fun `does not release anything when the call itself fails`() {
        var released = false

        assertFailsWith<IllegalStateException> {
            runBlocking {
                BlockingCalls.withDeadline(
                    timeoutMs = 5_000,
                    label = "失败调用",
                    onAbandoned = { released = true },
                ) {
                    throw IllegalStateException("连不上")
                }
            }
        }

        // 收尾线程没有东西可释放 —— 这里只确认没有误触发。
        Thread.sleep(100)
        assertTrue(!released, "抛异常的调用没有产出，不该触发 onAbandoned")
    }
}
