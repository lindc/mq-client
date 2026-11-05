package com.mqclient.rabbitmq.ui

import com.mqclient.rabbitmq.RabbitMqFetchedMessage
import com.mqclient.rabbitmq.RabbitMqQueueSession
import com.mqclient.rabbitmq.RabbitMqSessionOpener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [QueueSessionSlot] 的契约测试：**开新的必然先关旧的**。
 *
 * 修的是 P1-6：`session` 原先只是一个 `mutableStateOf`，不按 queueName 取 key，同一对话框
 * 实例换队列时它仍指向旧连接，直接覆盖就漏掉那条 AMQP `Connection` + `Channel`
 * （旧 channel 上的未 ack 消息也一直不还给队列）。
 *
 * 现在「先关旧、再开新」是槽的语义而不是调用方的约定 —— 对话框里没有第二条开会话的路，
 * 所以这组测试覆盖的正是生产路径本身（不是一条可能被绕过的辅助函数）。
 *
 * 用替身会话记录调用次序，不连 broker：真实会话需要 AMQP 的 `Connection`/`Channel`，
 * 单测里造不出来，这正是抽 [RabbitMqQueueSession] / [RabbitMqSessionOpener] 的目的。
 *
 * **覆盖边界**：本组测试只覆盖会话句柄的次序与归属；对话框在重开后清空消息列表 / `held` /
 * `detail` 属于 UI 状态，仍在 `RabbitMqQueueMessagesDialog` 的 `LaunchedEffect` 里
 * （deliveryTag 是 channel 级的，旧 tag 在新会话上会指向别的消息）。
 */
class QueueSessionSlotTest {

    @Test
    fun `open closes the previous session before opening the new one`() = runBlocking {
        val events = mutableListOf<String>()
        val old = RecordingSession("old", events)
        val fresh = RecordingSession("new", events)
        // 第一次返回 old（模拟「对话框已经打开过一次」），第二次返回 fresh（换队列）。
        val slot = QueueSessionSlot(sequenceOpener(events, old, fresh))
        slot.open(connectionId = "c1", queueName = "q1")
        events.clear()

        val opened = slot.open(connectionId = "c1", queueName = "q2")

        assertSame(fresh, opened, "应该返回新开的会话")
        assertEquals(listOf("close:old", "open:q2"), events, "必须先关旧的、再开新的")
        assertEquals(1, old.closeCount, "旧会话应当被关闭恰好一次")
    }

    @Test
    fun `first open does not try to close anything`() = runBlocking {
        val events = mutableListOf<String>()
        val fresh = RecordingSession("new", events)
        val slot = QueueSessionSlot(sequenceOpener(events, fresh))

        val opened = slot.open(connectionId = "c1", queueName = "q1")

        assertSame(fresh, opened)
        assertEquals(listOf("open:q1"), events)
    }

    @Test
    fun `open closes the old session even when opening the new one fails`() = runBlocking {
        val events = mutableListOf<String>()
        val old = RecordingSession("old", events)
        // 第一次开成功（把 old 放进槽），第二次抛 —— 模拟「换队列时新连接建不起来」。
        var attempts = 0
        val slot = QueueSessionSlot(
            opener = object : RabbitMqSessionOpener {
                override suspend fun open(
                    connectionId: String,
                    queueName: String,
                ): RabbitMqQueueSession {
                    events += "open:$queueName"
                    if (attempts++ > 0) throw IllegalStateException("未连接")
                    return old
                }
            },
        )
        slot.open(connectionId = "c1", queueName = "q1")
        events.clear()

        assertFailsWith<IllegalStateException> {
            slot.open(connectionId = "c1", queueName = "q2")
        }

        assertEquals(listOf("close:old", "open:q2"), events, "开新的失败也要先把旧的关掉")
        assertTrue(old.isClosed)
        assertNull(slot.detach(), "开失败后槽里不该还留着已经被关掉的旧会话")
    }

    @Test
    fun `open still opens the new session when closing the old one fails`() = runBlocking {
        val events = mutableListOf<String>()
        val old = RecordingSession("old", events, closeFailure = IllegalStateException("channel 已断"))
        val fresh = RecordingSession("new", events)
        var opened = false
        val slot = QueueSessionSlot(
            opener = object : RabbitMqSessionOpener {
                override suspend fun open(
                    connectionId: String,
                    queueName: String,
                ): RabbitMqQueueSession {
                    events += "open:$queueName"
                    return if (opened) fresh else old.also { opened = true }
                }
            },
        )
        slot.open(connectionId = "c1", queueName = "q1")
        events.clear()

        val next = slot.open(connectionId = "c1", queueName = "q2")

        assertSame(fresh, next, "旧会话关不掉不该拦住重开")
        assertEquals(listOf("close:old", "open:q2"), events)
    }

    @Test
    fun `detach hands the session over exactly once`() = runBlocking {
        val events = mutableListOf<String>()
        val fresh = RecordingSession("new", events)
        val slot = QueueSessionSlot(sequenceOpener(events, fresh))
        slot.open(connectionId = "c1", queueName = "q1")

        assertSame(fresh, slot.detach())
        assertNull(slot.detach(), "取走后再取应该是空 —— 避免同一句柄被关两次")
    }

    @Test
    fun `dispose hands over the current session and refuses later opens`() = runBlocking {
        val events = mutableListOf<String>()
        val old = RecordingSession("old", events)
        val late = RecordingSession("late", events)
        var opened = 0
        val slot = QueueSessionSlot(
            opener = object : RabbitMqSessionOpener {
                override suspend fun open(
                    connectionId: String,
                    queueName: String,
                ): RabbitMqQueueSession {
                    events += "open:$queueName"
                    return if (opened++ == 0) old else late
                }
            },
        )
        slot.open(connectionId = "c1", queueName = "q1")

        assertSame(old, slot.dispose(), "dispose 应把当前会话交出去关")
        assertNull(slot.detach(), "dispose 之后槽里不该再有会话")
        assertFailsWith<IllegalStateException>(
            "槽已随对话框作废，再开会话必须被拒绝",
        ) {
            slot.open(connectionId = "c1", queueName = "q2")
        }
        assertTrue(
            late.isClosed,
            "被拒绝的那次 open 已经建好的连接必须当场关掉 —— 否则就是一条无人认领的泄漏",
        )
    }

    @Test
    fun `detach keeps the slot usable`() = runBlocking {
        val events = mutableListOf<String>()
        val first = RecordingSession("first", events)
        val second = RecordingSession("second", events)
        val slot = QueueSessionSlot(sequenceOpener(events, first, second))
        slot.open(connectionId = "c1", queueName = "q1")

        assertSame(first, slot.detach())
        val next = slot.open(connectionId = "c1", queueName = "q2")

        assertSame(second, next, "detach 不是销毁 —— 之后再开必须正常返回新会话")
        assertTrue(first.isClosed.not(), "detach 不负责关句柄（调用方接手）")
    }

    /** 每次 open 依序返回 [sessions] 里的会话（用完后重复最后一个）。 */
    private fun sequenceOpener(
        events: MutableList<String>,
        vararg sessions: RabbitMqQueueSession,
    ): RabbitMqSessionOpener {
        var index = 0
        return RabbitMqSessionOpener { _, queueName ->
            events += "open:$queueName"
            sessions[minOf(index++, sessions.lastIndex)]
        }
    }
}

/**
 * 记录调用次序的会话替身。只有 [close] 的行为被观察，其余成员保持空实现 ——
 * 本组测试只关心「何时关、关了几次」。
 */
private class RecordingSession(
    private val label: String,
    private val events: MutableList<String>,
    private val closeFailure: Throwable? = null,
) : RabbitMqQueueSession {

    var closeCount = 0
        private set

    val isClosed: Boolean get() = closeCount > 0

    override suspend fun fetchNext(limit: Int): List<RabbitMqFetchedMessage> = emptyList()

    override suspend fun heldCount(): Int = 0

    override fun lastKnownConsumerCount(): Int? = null

    override suspend fun ack(deliveryTag: Long) = Unit

    override suspend fun requeueAll() = Unit

    override suspend fun publishDirect(body: ByteArray, headers: Map<String, String>) = Unit

    override suspend fun forward(
        message: RabbitMqFetchedMessage,
        exchange: String,
        routingKey: String,
    ) = Unit

    override suspend fun close() {
        closeCount++
        events += "close:$label"
        closeFailure?.let { throw it }
    }
}
