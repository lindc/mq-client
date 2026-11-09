package com.mqclient.rabbitmq

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.ChannelContinuationTimeoutException
import com.rabbitmq.client.Connection
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.GetResponse
import com.rabbitmq.client.impl.AMQImpl
import com.mqclient.core.ServiceException
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 未 ack 消息的账本（`heldTags`）与 `requeueAll` 的失败语义。
 *
 * ## 两条真实缺陷
 *
 * 1. **`discardChannel` 不清账**：channel 被报废后，它上面的 deliveryTag 全部失效；
 *    下一次 `ensureChannel` 新建的 channel 从 tag=1 重新计数，此时 `ack(tag)` /
 *    `requeueAll()` 会拿死 channel 的 tag 去打新 channel —— AMQP 0-9-1 对未知 tag
 *    回 406 PRECONDITION_FAILED，把刚建好的 channel 再打死一轮。同时 `heldCount()`
 *    还在报「N 条待处理」，而这些消息界面根本看不到。
 * 2. **`requeueAll` 吞错**：旧实现先把 `heldTags` 清空、再对每条 `basicNack` 做
 *    `runCatching`，失败被丢弃 → 调用方永远以为成功，实际消息还卡在 unacked。
 *
 * 不用 broker：动态代理造 channel，`basicGet` / `basicNack` 的行为按用例配置。
 */
class RabbitMqSessionRequeueTest {

    /** 假 channel 的行为开关。 */
    private class FakeBroker(
        var getFails: Boolean = false,
        var nackFails: Boolean = false,
    ) {
        private val nextTag = AtomicLong(1)
        val nacks = mutableListOf<Long>()
        val aborts = mutableListOf<String>()

        fun channel(): Channel {
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "basicGet" -> {
                        if (getFails) {
                            throw ChannelContinuationTimeoutException(
                                java.util.concurrent.TimeoutException("simulated rpc timeout"),
                                proxy,
                                7,
                                AMQImpl.Queue.DeclareOk("diag.queue", 0, 0),
                            )
                        }
                        val tag = nextTag.getAndIncrement()
                        GetResponse(
                            Envelope(tag, false, "", "diag.queue"),
                            AMQP.BasicProperties.Builder().messageId("m$tag").build(),
                            "body-$tag".toByteArray(),
                            0,
                        )
                    }
                    "basicNack" -> {
                        val tag = (args?.get(0) as? Long) ?: -1L
                        if (nackFails) throw IOException("simulated nack failure")
                        nacks += tag
                        null
                    }
                    "abort" -> {
                        aborts += "abort"
                        null
                    }
                    "getChannelNumber" -> 7
                    "isOpen" -> true
                    "toString" -> "FakeChannel"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            }
            return Proxy.newProxyInstance(
                Channel::class.java.classLoader,
                arrayOf(Channel::class.java),
                handler,
            ) as Channel
        }

        fun connection(channel: Channel): Connection {
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "createChannel" -> channel
                    "isOpen" -> true
                    "toString" -> "FakeConnection"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            }
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
                handler,
            ) as Connection
        }

        fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0.0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> ' '
            else -> null
        }
    }

    private fun session(broker: FakeBroker): RabbitMqQueueSession {
        val channel = broker.channel()
        return RabbitMqMessageSession.onSharedConnection("diag.queue", broker.connection(channel), channel)
    }

    @Test
    fun `requeueAll reports failure instead of silently claiming success`() = runBlocking {
        val broker = FakeBroker()
        val session = session(broker)
        session.fetchNext(3)
        assertEquals(3, session.heldCount())

        broker.nackFails = true

        val error = assertFailsWith<ServiceException> { session.requeueAll() }
        assertEquals("rabbitmq_err_requeue_incomplete", error.errorKey)
        // channel 已报废：broker 关闭它时会把未 ack 的消息整体重入队，账上也就清零了。
        assertEquals(0, session.heldCount(), "报废 channel 后账上的 tag 必须清掉")
    }

    @Test
    fun `requeueAll requeues every held tag on success`() = runBlocking {
        val broker = FakeBroker()
        val session = session(broker)
        session.fetchNext(2)

        session.requeueAll()

        assertEquals(listOf(1L, 2L), broker.nacks, "每条未 ack 的消息都要 basicNack(requeue)")
        assertEquals(0, session.heldCount())
    }

    @Test
    fun `a discarded channel drops its stale delivery tags`() = runBlocking {
        val broker = FakeBroker()
        val session = session(broker)
        session.fetchNext(4)
        assertEquals(4, session.heldCount())

        // 下一次 basicGet 超时 → channel 报废。
        broker.getFails = true
        runCatching { session.fetchNext(1) }

        assertEquals(
            0,
            session.heldCount(),
            "channel 报废后 tag 已失效，留在账上会让 heldCount 撒谎，" +
                "还会在新建 channel 上触发 406 PRECONDITION_FAILED",
        )
    }

    @Test
    fun `session keeps working on a fresh channel after a discard`() = runBlocking {
        val broker = FakeBroker()
        val session = session(broker)
        session.fetchNext(2)

        broker.getFails = true
        runCatching { session.fetchNext(1) }
        broker.getFails = false

        // 新 channel 的 tag 从 1 重新开始；账上若还留着旧 tag，这里的 requeue 会拿
        // 旧 tag 去打新 channel（broker 会以 406 关掉它）。
        session.fetchNext(2)
        assertEquals(2, session.heldCount())
        session.requeueAll()
        assertEquals(0, session.heldCount())
        assertTrue(broker.aborts.isNotEmpty(), "被报废的 channel 应该走到 abort")
    }
}
