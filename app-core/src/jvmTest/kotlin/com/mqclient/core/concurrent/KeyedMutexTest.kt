package com.mqclient.core.concurrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [KeyedMutex] 的行为契约：**同 key 串行、不同 key 并发**，条目随最后一个持有者
 * 退出而回收。
 *
 * 背景：四个扫描服务原本各用一把模块级 [kotlinx.coroutines.sync.Mutex]，导致
 * 连接 A 的慢扫挡住连接 B 的刷新（扫描是彼此独立的 IO）。锁的意图只是防同一条
 * 连接上重叠扫描互相覆盖结果 —— 这组用例把那个意图固定成机制。
 */
class KeyedMutexTest {

    @Test
    fun `同一 key 串行 - 第二个任务等第一个完成后才进入临界区`() = runBlocking {
        val locks = KeyedMutex()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = launch {
            locks.withLock("conn-a") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val second = launch {
            locks.withLock("conn-a") { secondEntered = true }
        }
        yield()
        assertFalse(secondEntered, "同 key 的第二个任务不应在第一个释放前进入")

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered)
    }

    @Test
    fun `不同 key 并发 - 一个 key 被占住不影响另一个`() = runBlocking {
        val locks = KeyedMutex()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var otherKeyEntered = false

        val holder = launch {
            locks.withLock("conn-a") {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()

        val other = launch {
            locks.withLock("conn-b") { otherKeyEntered = true }
        }
        other.join()
        assertTrue(otherKeyEntered, "另一个 key 不应被 conn-a 挡住")

        release.complete(Unit)
        holder.join()
    }

    @Test
    fun `最后一个持有者退出后回收条目`() = runBlocking {
        val locks = KeyedMutex()
        locks.withLock("conn-a") { }
        locks.withLock("conn-a") { }
        assertEquals(0, locks.activeKeyCount, "没有持有者时不应留下空 Mutex")

        val gate = CompletableDeferred<Unit>()
        val job = launch { locks.withLock("conn-b") { gate.await() } }
        yield()
        assertEquals(1, locks.activeKeyCount, "持有期间应保留该 key 的条目")
        gate.complete(Unit)
        job.join()
        assertEquals(0, locks.activeKeyCount)
    }

    @Test
    fun `block 抛异常也归还锁 - 后续任务不被卡死`() = runBlocking {
        val locks = KeyedMutex()
        runCatching { locks.withLock("conn-a") { error("boom") } }
        assertEquals(0, locks.activeKeyCount)

        var ran = false
        locks.withLock("conn-a") { ran = true }
        assertTrue(ran, "异常路径泄漏的锁会把同 key 的后续扫描永久卡住")
    }
}
