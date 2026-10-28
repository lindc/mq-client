package com.mqclient.rabbitmq

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 会话账本（`RabbitMqModule.openSessions`）的**销账机制**。
 *
 * 真实缺陷：账本原先只在 `RabbitMqModule.closeMessageSessionAsync` 里 `remove`，而
 * 对话框的 `QueueSessionSlot` 有两条直接调 `session.close()` 的路径（换队列关旧会话、
 * 槽已销毁时关迟到的会话）。它们不经过那个函数，于是每换一次队列、每「建连中 → 关闭」
 * 一次，账本就多一条**已关闭**的会话，强引用着共享 `Connection`，直到应用退出才被
 * `shutdown()` 清掉。
 *
 * 修法是把销账绑在 `close()` 上（`TrackedSession`），所以这里测的就是那条机制：
 * 无论谁关、关几次、关成功还是抛异常，账本都不能再留着它。
 *
 * 不连 broker：用替身实现 [RabbitMqQueueSession]，观测的全是账本语义。
 */
class RabbitMqSessionLedgerTest {

    /** 最小替身：只记录 close 被调了几次，其余成员给安全默认值。 */
    private class FakeSession(private val closeFailure: Throwable? = null) : RabbitMqQueueSession {
        var closeCalls = 0
            private set

        override suspend fun fetchNext(limit: Int): List<RabbitMqFetchedMessage> = emptyList()

        override suspend fun heldCount(): Int = 0

        override suspend fun ack(deliveryTag: Long) = Unit

        override suspend fun requeueAll() = Unit

        override suspend fun publishDirect(body: ByteArray, headers: Map<String, String>) = Unit

        override suspend fun forward(
            message: RabbitMqFetchedMessage,
            exchange: String,
            routingKey: String,
        ) = Unit

        override suspend fun close() {
            closeCalls++
            closeFailure?.let { throw it }
        }
    }

    /** 与生产同构的账本：[ConcurrentHashMap.newKeySet]。 */
    private fun ledger() = ConcurrentHashMap.newKeySet<RabbitMqQueueSession>()

    @Test
    fun closeRemovesSessionFromLedger() = runBlocking {
        val sessions = ledger()
        val tracked = RabbitMqModule.TrackedSession(FakeSession()) { sessions.remove(it) }
        sessions.add(tracked)
        assertTrue(tracked in sessions, "入账后应当能在账本里查到")

        tracked.close()

        assertFalse(tracked in sessions, "close() 之后必须销账 —— 否则账本会累积已关闭的会话")
    }

    /**
     * 关闭失败也要销账。
     *
     * 反过来的做法（失败就留在账上）看起来"更安全"，其实两端都错：账本留着一个调用方
     * 已经丢弃的句柄，语义上是泄漏；而 `shutdown()` 重试它也没有意义 —— abort 已经
     * 表明它不可能被关得更干净。
     */
    @Test
    fun closeFailureStillRemovesSessionFromLedger() = runBlocking {
        val sessions = ledger()
        val fake = FakeSession(closeFailure = IllegalStateException("boom"))
        val tracked = RabbitMqModule.TrackedSession(fake) { sessions.remove(it) }
        sessions.add(tracked)

        val error = runCatching { tracked.close() }.exceptionOrNull()

        assertEquals("boom", error?.message, "异常应原样上抛给调用方，而不是被吞掉")
        assertEquals(1, fake.closeCalls, "delegate.close() 只应被调一次")
        assertFalse(tracked in sessions, "关闭抛异常时同样要销账")
    }

    /** 重复关闭（对话框 onDispose 与模块 shutdown 各关一次）不改变语义。 */
    @Test
    fun repeatedCloseKeepsLedgerEmpty() = runBlocking {
        val sessions = ledger()
        val fake = FakeSession()
        val tracked = RabbitMqModule.TrackedSession(fake) { sessions.remove(it) }
        sessions.add(tracked)

        tracked.close()
        tracked.close()

        assertTrue(sessions.isEmpty(), "重复关闭后账本仍应为空")
        assertEquals(2, fake.closeCalls, "重复 close 会转发给 delegate（它自己负责幂等）")
    }

    /** 包装是委托，不是阉割：其他成员照旧透传。 */
    @Test
    fun delegatesNonCloseMembers() = runBlocking {
        val tracked = RabbitMqModule.TrackedSession(FakeSession()) { }
        assertEquals(0, tracked.heldCount())
        assertTrue(tracked.fetchNext(5).isEmpty())
    }
}
