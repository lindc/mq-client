package com.mqclient.core.scheduler

import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.EventBus
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getString
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 调度器的行为契约测试：
 *  - `runOnStart` / `initialDelayMs` / 默认首次延迟三种首次执行语义；
 *  - 同 ID 重新注册会顶掉旧任务（并发注册也不会漏出取消不到的协程）；
 *  - `cancel` / `cancelByPrefix` / `cancelAll` 的取消范围；
 *  - 任务抛异常后自己照常进入下一轮，且不影响别的任务，同时上报失败事件；
 *  - scope 已取消时不留下清不掉的僵尸登记。
 *
 * 时间相关的用例都用「短间隔（30-60ms）+ 轮询等待 + 宽松超时」的写法，断言里不出现
 * 「等了 N 毫秒所以一定跑了 M 次」这种和机器快慢绑定的假设。
 */
class JobSchedulerTest {

    /** 建一个调度器 + 一条总线；测完统一收掉，不留悬空协程。 */
    private fun withScheduler(body: suspend CoroutineScope.(JobScheduler, EventBus) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus(scope)
        val scheduler = JobScheduler(scope, bus)
        try {
            body(scheduler, bus)
        } finally {
            scheduler.cancelAll()
            bus.cancelAll()
            scope.cancel()
        }
    }

    /** 轮询等待条件成立；超时即测试失败。 */
    private suspend fun await(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(5)
        }
    }

    @Test
    fun `runOnStart job runs immediately and keeps repeating`() = withScheduler { scheduler, _ ->
        val runs = AtomicInteger()
        val id = ScheduledJob.idFor("ibm-mq", "c1", "scan.messages")

        scheduler.schedule(ScheduledJob(id = id, intervalMs = 40L, runOnStart = true) { runs.incrementAndGet() })

        await { runs.get() >= 3 }
        assertEquals(listOf(id), scheduler.getActiveJobs())
    }

    @Test
    fun `without runOnStart the first run waits for initialDelayMs`() = withScheduler { scheduler, _ ->
        val runs = AtomicInteger()
        scheduler.schedule(
            ScheduledJob(id = "slow-start", intervalMs = 5_000L, initialDelayMs = 150L) { runs.incrementAndGet() },
        )

        // 登记本身不内联执行（协程以 LAZY 起步），所以这句断言是确定性的。
        assertEquals(0, runs.get(), "注册时不该立刻执行")
        assertEquals(1, scheduler.activeJobCount)

        await(timeoutMillis = 3_000L) { runs.get() >= 1 }
    }

    @Test
    fun `without initialDelay the first run waits one full interval`() = withScheduler { scheduler, _ ->
        val runs = AtomicInteger()
        scheduler.schedule(ScheduledJob(id = "default-start", intervalMs = 60L) { runs.incrementAndGet() })

        assertEquals(0, runs.get(), "注册时不该立刻执行")
        await { runs.get() >= 1 }
    }

    @Test
    fun `scheduling the same id again replaces the old job`() = withScheduler { scheduler, _ ->
        val first = AtomicInteger()
        val second = AtomicInteger()

        scheduler.schedule(ScheduledJob(id = "dup", intervalMs = 30L, runOnStart = true) { first.incrementAndGet() })
        await { first.get() >= 1 }

        scheduler.schedule(ScheduledJob(id = "dup", intervalMs = 30L, runOnStart = true) { second.incrementAndGet() })
        await { second.get() >= 2 }

        // 先给旧任务一点时间把「当前这一轮」跑完，再看它是不是真的停了。
        delay(120L)
        val settled = first.get()
        delay(120L)

        assertEquals(settled, first.get(), "同 ID 重新注册后旧任务必须停止")
        assertEquals(1, scheduler.activeJobCount, "同 ID 只该留一条登记")
    }

    @Test
    fun `racing schedules of the same id never orphan a running job`() = withScheduler { scheduler, bus ->
        val started = AtomicInteger()
        bus.on(EventNames.SCHEDULER_JOB_STARTED) { started.incrementAndGet() }
        await { bus.subscriptionCount == 1 }
        assertTrue(bus.awaitSubscribers(1_000L))

        // 多线程抢着注册同一个 ID。旧实现把「取消旧的」和「登记新的」分成了两步
        // （先 cancel(id) 再 update），并发下会有一条活协程被挤出注册表 —— 它既不在
        // getActiveJobs 里、cancelAll 也够不着。只能靠「取消之后还在跑」把它逼出来。
        val racers = (1..8).map {
            launch(Dispatchers.Default) {
                repeat(150) {
                    scheduler.schedule(ScheduledJob(id = "raced", intervalMs = 2L, runOnStart = true) { })
                }
            }
        }
        racers.forEach { it.join() }

        assertEquals(listOf("raced"), scheduler.getActiveJobs(), "并发注册之后表里只该留一条")

        scheduler.cancelAll()
        delay(200L) // 等在途事件投递完
        val settled = started.get()
        delay(300L) // 漏网协程的间隔只有 2ms，够它再跑几十轮

        assertEquals(settled, started.get(), "cancelAll 之后还在跑，说明有协程没被登记也没被取消")
    }

    @Test
    fun `cancel stops one job and leaves the others running`() = withScheduler { scheduler, _ ->
        val a = AtomicInteger()
        val b = AtomicInteger()
        scheduler.schedule(ScheduledJob(id = "a", intervalMs = 30L, runOnStart = true) { a.incrementAndGet() })
        scheduler.schedule(ScheduledJob(id = "b", intervalMs = 30L, runOnStart = true) { b.incrementAndGet() })
        await { a.get() >= 2 && b.get() >= 2 }

        assertTrue(scheduler.cancel("a"))
        assertFalse(scheduler.cancel("a"), "重复取消返回 false，不抛异常")
        assertEquals(listOf("b"), scheduler.getActiveJobs())

        delay(120L)
        val aSettled = a.get()
        val bSettled = b.get()
        delay(120L)

        assertEquals(aSettled, a.get(), "被取消的任务不再执行")
        assertTrue(b.get() > bSettled, "其它任务不受影响")
    }

    @Test
    fun `cancelByPrefix cancels only the jobs of that connection`() = withScheduler { scheduler, _ ->
        val runs = AtomicInteger()
        val c1Messages = ScheduledJob.idFor("ibm-mq", "c1", "scan.messages")
        val c1Objects = ScheduledJob.idFor("ibm-mq", "c1", "scan.objects")
        val c2Messages = ScheduledJob.idFor("ibm-mq", "c2", "scan.messages")

        listOf(c1Messages, c1Objects, c2Messages).forEach { id ->
            scheduler.schedule(ScheduledJob(id = id, intervalMs = 30L, runOnStart = true) { runs.incrementAndGet() })
        }
        await { runs.get() >= 3 }

        assertEquals(2, scheduler.cancelByPrefix(ScheduledJob.prefixFor("ibm-mq", "c1")))
        assertEquals(listOf(c2Messages), scheduler.getActiveJobs(), "别的连接的任务必须留着")
        assertEquals(0, scheduler.cancelByPrefix(ScheduledJob.prefixFor("ibm-mq", "c1")), "再取消一次没有可选的了")
    }

    @Test
    fun `cancelAll clears every job`() = withScheduler { scheduler, _ ->
        val runs = AtomicInteger()
        repeat(3) { index ->
            scheduler.schedule(ScheduledJob(id = "j$index", intervalMs = 30L, runOnStart = true) { runs.incrementAndGet() })
        }
        await { runs.get() >= 3 }

        scheduler.cancelAll()
        assertEquals(emptyList(), scheduler.getActiveJobs())
        assertEquals(0, scheduler.activeJobCount)

        delay(100L)
        val settled = runs.get()
        delay(100L)
        assertEquals(settled, runs.get(), "cancelAll 之后不该再有任何执行")
    }

    @Test
    fun `a failing job is reported without killing itself or the others`() = withScheduler { scheduler, bus ->
        val failing = AtomicInteger()
        val healthy = AtomicInteger()
        val failures = CopyOnWriteArrayList<AppEvent>()
        val errors = CopyOnWriteArrayList<AppEvent>()
        bus.on(EventNames.SCHEDULER_JOB_FAILED) { failures += it }
        bus.on(EventNames.SCHEDULER_ERROR) { errors += it }

        scheduler.schedule(ScheduledJob(id = "boom", intervalMs = 30L, runOnStart = true) {
            failing.incrementAndGet()
            error("boom")
        })
        scheduler.schedule(ScheduledJob(id = "healthy", intervalMs = 30L, runOnStart = true) { healthy.incrementAndGet() })

        await { failures.isNotEmpty() }
        // 抛过异常之后，失败的任务自己照常进入下一轮，兄弟任务也一刻没停。
        await { failing.get() >= 3 }
        await { healthy.get() >= 3 }

        val failure = failures.first()
        assertEquals("boom", failure.getString("jobId"))
        assertEquals("boom", failure.getString("message"))
        assertEquals("IllegalStateException", failure.getString("error"))
        assertTrue(errors.isNotEmpty(), "core.scheduler.error 作为统一错误口也要收到")
        assertEquals(setOf("boom", "healthy"), scheduler.getActiveJobs().toSet(), "失败不终止任务")
    }

    @Test
    fun `invalid definitions are rejected at construction time`() {
        assertFailsWith<IllegalArgumentException>("空白 id") { ScheduledJob(id = "  ", intervalMs = 1L) {} }
        assertFailsWith<IllegalArgumentException>("零间隔会变成忙等") { ScheduledJob(id = "x", intervalMs = 0L) {} }
        assertFailsWith<IllegalArgumentException>("负间隔") { ScheduledJob(id = "x", intervalMs = -1L) {} }
        assertFailsWith<IllegalArgumentException>("负首次延迟") {
            ScheduledJob(id = "x", intervalMs = 1L, initialDelayMs = -1L) {}
        }
    }

    @Test
    fun `scheduling on a cancelled scope leaves no zombie entry`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus(scope)
        val scheduler = JobScheduler(scope, bus)
        try {
            scope.cancel()

            val runs = AtomicInteger()
            scheduler.schedule(ScheduledJob(id = "late", intervalMs = 30L, runOnStart = true) { runs.incrementAndGet() })

            assertEquals(emptyList(), scheduler.getActiveJobs(), "scope 已取消：不该留下永远清不掉的登记")
            delay(60L)
            assertEquals(0, runs.get(), "也不该执行")
        } finally {
            scheduler.cancelAll()
            bus.cancelAll()
            scope.cancel()
        }
    }
}
