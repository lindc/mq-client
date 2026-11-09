package com.mqclient.kafka

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.apache.kafka.clients.consumer.ConsumerRecords

/**
 * 浏览循环的取消语义：**关掉对话框要立刻停下**，不能等满 `connectionTimeout`。
 *
 * `KafkaConsumer.poll` 是阻塞调用，协程取消对它无效；旧实现只在循环条件里看
 * `now < deadline`，于是关窗后 IO 线程会被钉住最多 30s（默认 `connectionTimeout`），
 * 期间同一台机器上的其它读取一起排队。修法与 RocketMQ 侧一致：`runInterruptible`
 * 打断在飞的那次 poll + 循环头 `ensureActive()`。
 *
 * 用 [KafkaMessageService.collectPageWithPoll] 的可测内核，无需真实 broker。
 */
class KafkaMessageBrowseCancellationTest {

    @Test
    fun `browse loop stops promptly when cancelled`() {
        runBlocking(Dispatchers.Default) {
            val service = KafkaMessageService()
            val pollStarted = CountDownLatch(1)

            val job = async {
                service.collectPageWithPoll(
                    topic = "orders",
                    count = 100,
                    timeoutMs = 60_000, // 远大于取消发生的时刻
                    previousCursor = null,
                ) {
                    pollStarted.countDown()
                    // 模拟不可取消的阻塞 poll：只有 Thread.interrupt() 能让它提前返回。
                    runInterruptible { Thread.sleep(10_000) }
                    ConsumerRecords.empty()
                }
            }

            assertTrue(pollStarted.await(5, TimeUnit.SECONDS), "poll 应该已经开始")
            delay(100)
            val cancelAt = System.currentTimeMillis()
            job.cancel()

            assertFailsWith<CancellationException> { job.await() }
            val elapsed = System.currentTimeMillis() - cancelAt
            assertTrue(
                elapsed < 3_000,
                "取消后不该继续等 poll 的 10s，实际 ${elapsed}ms —— 循环没检查取消或 poll 没被中断",
            )
        }
    }
}
