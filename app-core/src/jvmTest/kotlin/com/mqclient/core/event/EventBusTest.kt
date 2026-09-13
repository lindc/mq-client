package com.mqclient.core.event

import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.MODULE_ID_KEY
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.core.spi.onOwnEvents
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 事件总线的行为契约测试：
 *  - 四种订阅方式（精确 / 前缀 / 后缀 / 通配）各自的匹配语义；
 *  - 单个 handler 抛异常不影响其他 handler，也不影响它自己后续的投递；
 *  - SUSPEND 背压下事件一条不丢，且同一条订阅内顺序不乱；
 *  - `Job.cancel()` 之后不再投递；
 *  - ServiceContext 发事件时自动补 moduleId / source。
 *
 * 注意：replay=0 意味着「订阅协程真正开始 collect 之前」发出的事件会被丢弃，所以每个
 * 用例在发事件前都先 `awaitSubscribers()` —— 这是生产代码的启动时序要求（Main.kt 里也
 * 这么做的），不是测试的权宜之计。
 *
 * handler 跑在 Dispatchers.Default 上，所以断言前一律用 [await] 等状态就位，
 * 收集容器用线程安全的实现。
 */
class EventBusTest {

    /** 建一条总线 + 一个受控 scope；测完统一收掉，不留悬空协程。 */
    private fun withBus(
        reporter: EventBusErrorReporter = EventBusErrorReporter.Logging,
        body: suspend CoroutineScope.(EventBus) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus(scope, reporter)
        try {
            body(bus)
        } finally {
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
    fun `exact subscription receives only its own event`() = withBus { bus ->
        val received = CopyOnWriteArrayList<String>()
        val job = bus.on("core.app.started") { received += it.name }
        assertTrue(bus.awaitSubscribers(1_000L))

        bus.emitSync("core.app.started")
        bus.emitSync("core.app.closing")

        await { received.size >= 1 }
        delay(50) // 给「不该来」的那条事件一点到达时间
        assertEquals(listOf("core.app.started"), received.toList())

        job.cancel()
    }

    @Test
    fun `prefix suffix and wildcard subscriptions filter independently`() = withBus { bus ->
        val prefix = CopyOnWriteArrayList<String>()
        val suffix = CopyOnWriteArrayList<String>()
        val all = CopyOnWriteArrayList<String>()
        bus.onPrefix("ibm-mq.") { prefix += it.name }
        bus.onSuffix(".scan.completed") { suffix += it.name }
        bus.onAll { all += it.name }
        assertTrue(bus.awaitSubscribers(1_000L))

        bus.emitSync("ibm-mq.scan.started")
        bus.emitSync("ibm-mq.scan.completed")
        bus.emitSync("rabbitmq.scan.completed")

        await { prefix.size == 2 && suffix.size == 2 && all.size == 3 }
        assertEquals(listOf("ibm-mq.scan.started", "ibm-mq.scan.completed"), prefix.toList())
        assertEquals(listOf("ibm-mq.scan.completed", "rabbitmq.scan.completed"), suffix.toList())
    }

    @Test
    fun `a throwing handler is isolated from the others`() {
        val reported = CopyOnWriteArrayList<Throwable>()
        val throwingCalls = AtomicInteger()

        withBus(EventBusErrorReporter { _, _, error -> reported += error }) { bus ->
            val healthy = CopyOnWriteArrayList<String>()
            bus.onAll {
                throwingCalls.incrementAndGet()
                error("boom")
            }
            bus.onAll { healthy += it.name }
            assertTrue(bus.awaitSubscribers(1_000L))

            bus.emitSync("a.b.c")
            bus.emitSync("a.b.d")

            await { healthy.size == 2 && reported.size == 2 }
            // 会抛异常的订阅没有死掉：第二个事件它同样进了 handler。
            assertEquals(2, throwingCalls.get())
            assertEquals(listOf("a.b.c", "a.b.d"), healthy.toList())
        }
    }

    @Test
    fun `every event reaches a slow subscriber in order`() = withBus { bus ->
        val seen = CopyOnWriteArrayList<Int>()
        bus.on("tick") { event ->
            delay(1) // 故意比发送慢，把 64 条缓冲区填满，逼出发送方背压
            seen += event.getInt("i") ?: -1
        }

        assertTrue(bus.awaitSubscribers(1_000L))

        // emitSync 会挂起，所以放到独立协程里发，避免发送和处理互相卡住。
        val producer = launch { repeat(80) { bus.emitSync("tick", mapOf("i" to it)) } }
        producer.join()

        await(5_000) { seen.size == 80 }
        assertEquals((0 until 80).toList(), seen.toList())
    }

    @Test
    fun `async emit stays bounded when a subscriber stalls forever`() = withBus { bus ->
        val release = CompletableDeferred<Unit>()
        bus.on("stuck") { release.await() } // 永不消费：缓冲区和降级队列都会被填满
        assertTrue(bus.awaitSubscribers(1_000L))

        val total = 10_000
        repeat(total) { bus.emit("stuck") }

        // 旧实现在这里会为**每一条**塞不进去的事件各起一条协程（永不丢、协程数 = total），
        // 一条慢订阅者就能把进程拖垮。新实现最多留住「缓冲 + 队列」那么多条。
        val retained = (total - bus.droppedEventCount).toInt()
        assertTrue(bus.droppedEventCount > 0, "降级路径必须封顶：超量的事件要被丢掉并计数")
        assertTrue(
            retained <= EventBus.BUFFER_CAPACITY + EventBus.OVERFLOW_QUEUE_CAPACITY + 8,
            "留住的事件必须被「缓冲 + 队列」容量封住，实际留了 $retained 条",
        )
        release.complete(Unit)
    }

    @Test
    fun `async emit keeps order across the overflow queue`() = withBus { bus ->
        val seen = CopyOnWriteArrayList<Int>()
        val release = CompletableDeferred<Unit>()
        // 处理第一条时就卡住：缓冲区被填满并保持，逼剩下的事件全走降级队列。
        // 放开之后正好能验证「缓冲里的」和「队列里的」是不是按原顺序拼回来的。
        bus.on("tick") { event ->
            release.await()
            seen += event.getInt("i") ?: -1
        }
        assertTrue(bus.awaitSubscribers(1_000L))

        val total = 400 // 超过「缓冲 + 队列」容量，逼出丢包
        repeat(total) { bus.emit("tick", mapOf("i" to it)) }
        release.complete(Unit)

        val retained = (total - bus.droppedEventCount).toInt()
        await(10_000) { seen.size == retained }
        assertTrue(bus.droppedEventCount > 0)
        assertEquals((0 until retained).toList(), seen.toList(), "降级队列必须是 FIFO")
    }

    @Test
    fun `cancelling the returned job stops delivery`() = withBus { bus ->
        val seen = CopyOnWriteArrayList<String>()
        val job = bus.on("x.y.z") { seen += it.name }
        assertTrue(bus.awaitSubscribers(1_000L))

        bus.emitSync("x.y.z")
        await { seen.size == 1 }

        job.cancel()
        job.join() // 等收集协程退出，避免竞态

        bus.emitSync("x.y.z")
        delay(100)
        assertEquals(1, seen.size)
        assertEquals(0, bus.subscriptionCount) // 取消后注册表里也不再有它
    }

    @Test
    fun `awaitSubscribers waits until the collectors are live`() = withBus { bus ->
        val seen = CopyOnWriteArrayList<String>()
        bus.on("ready") { seen += it.name }

        assertTrue(bus.awaitSubscribers(1_000L))
        bus.emitSync("ready")

        await { seen.size == 1 }
    }

    @Test
    fun `async emit delivers without blocking the caller`() = withBus { bus ->
        val seen = CopyOnWriteArrayList<String>()
        bus.on("async") { seen += it.name }
        assertTrue(bus.awaitSubscribers(1_000L))

        bus.emit("async")

        await { seen.size == 1 }
    }

    @Test
    fun `payload accessors tolerate boxed numbers while get stays strict`() = withBus { bus ->
        val seen = CopyOnWriteArrayList<String>()
        bus.on("numbers") { event ->
            // JSON 反序列化常把数字变成 Long：get<Int> 严格（null），getInt 会转换。
            seen += "${event.get<Int>("i")}|${event.getInt("i")}|${event.getString("name")}"
        }

        assertTrue(bus.awaitSubscribers(1_000L))
        bus.emitSync("numbers", mapOf("i" to 7L, "name" to "QUEUE1"))

        await { seen.size == 1 }
        assertEquals("null|7|QUEUE1", seen.first())
    }

    @Test
    fun `subscribing with a cancelled scope leaves no phantom subscription`() = withBus { bus ->
        val dead = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        dead.cancel() // 协程体永远不会执行 → 不该进注册表
        bus.on("phantom", dead) { }

        delay(100)
        assertEquals(0, bus.subscriptionCount, "幻影订阅会常驻注册表，并把 awaitSubscribers 拖到超时")
        assertTrue(bus.awaitSubscribers(1_000L))
    }

    @Test
    fun `a racing cancelAll never orphans a subscription`() = withBus { bus ->
        val subscriberScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val jobs = CopyOnWriteArrayList<Job>()
        // 注册和 cancelAll 并发跑，逼出「取快照」与「清空表」之间的空窗。
        val registrars = (1..8).map {
            launch(Dispatchers.Default) {
                repeat(200) { jobs += bus.on("race", subscriberScope) { } }
            }
        }
        val canceller = launch(Dispatchers.Default) { repeat(500) { bus.cancelAll() } }
        registrars.forEach { it.join() }
        canceller.join()

        bus.cancelAll() // 收尾：并发期间之后才注册进来的，这一下全收

        // 每一条订阅要么被某次 cancelAll 取消，要么还留在注册表里等着被取消。
        // 漏网的那些是「已被移出注册表、job 却没被取消」，只能靠下面的断言发现。
        assertTrue(jobs.all { it.isCancelled }, "有 ${jobs.count { !it.isCancelled }} 条订阅成了漏网协程")

        subscriberScope.cancel()
    }

    @Test
    fun `module context emits with its own prefix and source`() = withBus { bus ->
        val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val context = ServiceContext.of("ibm-mq", bus, moduleScope, JobScheduler(moduleScope, bus))
        val own = CopyOnWriteArrayList<AppEvent>()
        val foreign = CopyOnWriteArrayList<AppEvent>()
        context.onOwnEvents { own += it }
        bus.onPrefix("rabbitmq.") { foreign += it }
        assertTrue(bus.awaitSubscribers(1_000L))

        context.emit("ibm-mq.scan.completed", mapOf("connectionId" to "mq-01"))

        await { own.size == 1 }
        delay(50)
        assertEquals(0, foreign.size)
        assertEquals("ibm-mq", own.first().source)
        assertEquals("ibm-mq", own.first().getString(MODULE_ID_KEY))
        assertTrue(own.first().timestamp > 0L)

        moduleScope.cancel()
    }
}
