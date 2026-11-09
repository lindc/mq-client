package com.mqclient.ibmmq

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [awaitMqiQuiescence] 的契约：**等得到就串行化，等不到也必须如实返回 false**。
 *
 * 它守的是断开路径：`disconnect()` 与在飞的 MQI 调用同时作用于同一个
 * `MQQueueManager` 是未定义行为；但无限期等锁会把「断开 / 删除连接 / 退出」
 * 一起吊死（MQI 是纯阻塞、不响应取消的）。所以必须两者兼顾。
 */
class MqiQuiescenceTest {

    @Test
    fun `returns true when the lock is free`() = runBlocking {
        val lock = Mutex()

        assertTrue(awaitMqiQuiescence(lock, waitMs = 1_000))
        // 拿到后立刻释放了（withLock 的块是空的），后续仍可获取。
        assertTrue(awaitMqiQuiescence(lock, waitMs = 1_000))
    }

    @Test
    fun `returns false instead of waiting forever when an MQI call is stuck`() = runBlocking {
        val lock = Mutex()
        val holder = launch(Dispatchers.Default) {
            lock.withLock {
                // 模拟卡在 socket 上的 MQI 调用：不响应取消，也不释放锁。
                delay(30_000)
            }
        }
        // 确保锁已被持有。
        delay(100)

        val startedAt = System.currentTimeMillis()
        val acquired = awaitMqiQuiescence(lock, waitMs = 300)
        val elapsed = System.currentTimeMillis() - startedAt

        assertFalse(acquired, "锁被一条卡死的 MQI 调用占着时必须返回 false")
        assertTrue(elapsed < 5_000, "不该等到调用自己结束（30s），实际 ${elapsed}ms")
        holder.cancel()
    }

    @Test
    fun `succeeds once the in-flight call releases the lock`() = runBlocking {
        val lock = Mutex()
        val holder = launch(Dispatchers.Default) {
            lock.withLock { delay(200) }
        }
        delay(50)

        assertTrue(
            awaitMqiQuiescence(lock, waitMs = 5_000),
            "在飞调用结束后应当拿到锁（这是正常断开路径）",
        )
        holder.join()
    }

    @Test
    fun `serializes with the holder`() = runBlocking {
        val lock = Mutex()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val holder = async(Dispatchers.Default) {
            lock.withLock {
                delay(200)
                order += "mqi-done"
            }
        }
        delay(50)

        val acquired = awaitMqiQuiescence(lock, waitMs = 5_000)
        order += "disconnect"

        assertTrue(acquired)
        holder.await()
        // 断开必须排在在飞 MQI 之后，而不是与之交错。
        kotlin.test.assertEquals(listOf("mqi-done", "disconnect"), order)
    }
}
