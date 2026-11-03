package com.mqclient.kafka

import java.util.concurrent.ExecutionException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.apache.kafka.common.KafkaFuture
import org.apache.kafka.common.internals.KafkaFutureImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * [awaitKafka] 的行为：不占线程、超时可打断、失败露出根因。
 *
 * 为什么值得测：旧实现 `future.get(timeout, unit)` 是阻塞且**不可取消**的，
 * 外面包一层 `withTimeout(3000)` 只是把异常抛出来的时机提前，线程照样被占满 ——
 * 这正是「N 个组排队等 N 次往返」的一个来源。这里用真实的 [KafkaFutureImpl]
 * （不是 mock）验证三点：正常完成、超时、异常解包。
 */
class KafkaFuturesTest {

    @Test
    fun `completes with the future value`() = runBlocking {
        val future: KafkaFuture<String> = KafkaFutureImpl<String>().apply { complete("stable") }
        assertEquals("stable", future.awaitKafka(1_000))
    }

    @Test
    fun `timeout fires without waiting for the future itself`() = runBlocking {
        val future = KafkaFutureImpl<String>() // 永远不完成
        val started = System.nanoTime()
        assertFailsWith<TimeoutCancellationException> { future.awaitKafka(timeoutMs = 150) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 2_000, "超时必须真的打断等待，实测 ${elapsedMs}ms")
    }

    @Test
    fun `timeout does not kill the caller coroutine`() = runBlocking {
        val future = KafkaFutureImpl<String>()
        // 超时是「跳过这一项」的信号，不是让整个协程树退出 —— 调用方（逐个 group
        // 的 async）必须能继续等下一个组。
        val skipped = runCatching { future.awaitKafka(timeoutMs = 100) }.isFailure
        assertTrue(skipped)
        // 还能继续干活
        delay(10)
        assertEquals(42, 42)
    }

    @Test
    fun `failure unwraps ExecutionException to the broker root cause`() = runBlocking {
        val future = KafkaFutureImpl<String>().apply {
            completeExceptionally(ExecutionException(IllegalStateException("GroupAuthorizationException: denied")))
        }
        val error = assertFailsWith<IllegalStateException> { future.awaitKafka(1_000) }
        assertTrue(
            error.message!!.contains("GroupAuthorizationException"),
            "必须露出 broker 的根因类型（UI 的错误分类只认根因），实测：${error.message}",
        )
    }

    @Test
    fun `outer withTimeout cancels a pending await`() = runBlocking {
        val future = KafkaFutureImpl<String>()
        val started = System.nanoTime()
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(120) { future.awaitKafka() }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 2_000, "外层 withTimeout 必须能打断 await，实测 ${elapsedMs}ms")
    }

    @Test
    fun `concurrent awaits of many futures stay independent`() = runBlocking {
        // 模拟「批量 describe 之后的并发等待」：一个慢的不该拖住其它的。
        val fast = KafkaFutureImpl<Int>().apply { complete(1) }
        val slow = KafkaFutureImpl<Int>() // 永不完成
        val results = mutableListOf<Int>()
        val jobs = listOf(
            launch { results += fast.awaitKafka(500) },
            launch { runCatching { slow.awaitKafka(200) } },
        )
        jobs.forEach { it.join() }
        assertEquals(listOf(1), results, "快的那个必须照常返回")
    }
}
