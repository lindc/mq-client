package com.mqclient.core.concurrent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [HandleSlot] 的语义：懒建一次、关闭与创建互斥、断开后拒绝创建、重连后可再建。
 *
 * 这些断言钉死的是**孤儿句柄**那条竞态：创建（读 / 发路径）与关闭（断开路径）曾用
 * 不同的锁，交错下会建出一个挂在已断开连接上的句柄，只有应用退出才会被兜底关掉。
 * 在 RocketMQ 上那还意味着未加密私钥 PEM 永久留在临时目录。
 */
class HandleSlotTest {

    private class Handle(val id: Int)

    @Test
    fun `acquire creates once and reuses`() {
        val slot = HandleSlot<Handle>("测试句柄")
        val created = AtomicInteger(0)
        val factory = {
            Handle(created.incrementAndGet()).also { }
        }

        val first = slot.acquire(create = factory)
        val second = slot.acquire(create = factory)
        val third = slot.acquire(create = factory)

        assertSame(first, second, "重复取用必须返回同一个句柄 —— 每次新建就是「越用越慢」")
        assertSame(first, third)
        assertEquals(1, created.get(), "工厂只应被调用一次")
    }

    @Test
    fun `take marks disposed and hands the handle out exactly once`() {
        val slot = HandleSlot<Handle>("测试句柄")
        val handle = slot.acquire { Handle(1) }

        assertSame(handle, slot.take())
        assertNull(slot.take(), "取走后再取应为空 —— 避免同一句柄被关两次")
        assertNull(slot.peek())
        assertTrue(slot.isDisposed)
    }

    @Test
    fun `acquire after take is refused`() {
        val slot = HandleSlot<Handle>("测试句柄")
        slot.acquire { Handle(1) }
        slot.take()

        val error = assertFailsWith<IllegalStateException> {
            slot.acquire { Handle(2) }
        }
        assertTrue(
            error.message.orEmpty().contains("已断开"),
            "断开后创建必须被拒，实际=${error.message}",
        )
    }

    @Test
    fun `reopen allows creation again`() {
        val slot = HandleSlot<Handle>("测试句柄")
        val first = slot.acquire { Handle(1) }
        slot.take()
        slot.reopen()

        val second = slot.acquire { Handle(2) }

        assertNotSame(first, second, "重连后应是新句柄，不能把已关的那个交回去")
        assertEquals(2, second.id)
        assertTrue(!slot.isDisposed)
    }

    @Test
    fun `canCreate guard is evaluated inside the monitor`() {
        val slot = HandleSlot<Handle>("测试句柄")
        var allowed = false

        assertFailsWith<IllegalStateException> {
            slot.acquire(canCreate = { allowed }) { Handle(1) }
        }
        assertNull(slot.peek(), "守卫拒绝时不应留下任何句柄")

        allowed = true
        assertNotNull(slot.acquire(canCreate = { allowed }) { Handle(2) })
    }

    /**
     * 并发压力：N 个线程同时 acquire，工厂只能被调用一次。
     *
     * 这条覆盖的是「双检锁写错」的经典形态（锁外读 + 锁内赋值但漏了二次检查）——
     * 那种写法在压力下会建出多个句柄，而每个 KafkaProducer 都带 sender 线程。
     */
    @Test
    fun `concurrent acquire creates exactly one handle`() {
        repeat(50) {
            val slot = HandleSlot<Handle>("测试句柄")
            val created = AtomicInteger(0)
            val start = CountDownLatch(1)
            val done = CountDownLatch(8)
            val results = java.util.Collections.synchronizedList(mutableListOf<Handle>())

            repeat(8) {
                Thread {
                    start.await()
                    results += slot.acquire {
                        created.incrementAndGet()
                        Handle(created.get())
                    }
                    done.countDown()
                }.also { it.isDaemon = true }.start()
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS), "并发 acquire 未在 5s 内完成")

            assertEquals(1, created.get(), "并发下工厂必须只被调用一次")
            assertEquals(1, results.toSet().size, "所有调用方必须拿到同一个句柄")
        }
    }

    /**
     * 创建与关闭的交错：创建方在监视器内跑工厂时，关闭方的 take 必须等它结束，
     * 然后**拿到**那个刚建好的句柄 —— 而不是让创建方把它写进一个已经断开的槽。
     */
    @Test
    fun `take waits for in-flight creation and receives the handle`() {
        val slot = HandleSlot<Handle>("测试句柄")
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val taken = java.util.concurrent.atomic.AtomicReference<Handle?>()
        var created: Handle? = null

        val creator = Thread {
            created = slot.acquire {
                factoryEntered.countDown()
                // 模拟慢构造（KafkaConsumer 拉元数据 / RocketMQ start 注册组）
                releaseFactory.await(5, TimeUnit.SECONDS)
                Handle(7)
            }
        }.apply { isDaemon = true; start() }

        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS), "工厂未在 5s 内开始")
        val taker = Thread {
            taken.set(slot.take())
        }.apply { isDaemon = true; start() }

        // take 此刻应阻塞在监视器上：创建还没结束。
        Thread.sleep(200)
        assertNull(taken.get(), "take 不应在创建完成前拿到句柄")

        releaseFactory.countDown()
        creator.join(5_000)
        taker.join(5_000)

        val takenHandle = taken.get()
        assertNotNull(takenHandle, "创建先完成时，take 必须拿到那个句柄并负责关掉")
        assertSame(created, takenHandle, "关闭方拿到的必须就是刚建好的那个句柄")
        assertNull(slot.peek())
    }
}
