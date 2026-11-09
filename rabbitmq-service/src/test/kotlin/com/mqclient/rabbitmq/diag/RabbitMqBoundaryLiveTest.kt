package com.mqclient.rabbitmq.diag

import com.mqclient.core.ConnectionStore
import com.mqclient.rabbitmq.RabbitMqConfig
import com.mqclient.rabbitmq.RabbitMqMessageSession
import com.mqclient.rabbitmq.rabbitMqConfigFromOptions
import com.mqclient.rabbitmq.rabbitMqConnectionFactory
import com.mqclient.testing.assumeLive
import com.mqclient.testing.assumeNotNull
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 真机边界场景：**大消息、并发拉取、unacked 生命周期**。
 *
 * 这些是单测覆盖不到的：`RabbitMqSessionRequeueTest` 用假 channel 验证账本语义，
 * 但真实的 broker 有 frameMax、prefetch、channel 上限与 message TTL 等约束，只有连上
 * 才能知道我们的实现在边界上是什么行为。
 *
 * 每个用例自己建/删临时队列，不碰环境里已有的队列；没有 config 或 broker 不在时
 * 走 `assumeLive` 记 SKIP（本文件全部是纯客户端路径，一律用 assumption 而不是
 * 直接建连，避免「配置在但 broker 停了」把门禁弄红）。
 */
class RabbitMqBoundaryLiveTest {

    private fun configOrSkip(): RabbitMqConfig {
        val info = assumeNotNull(
            "LIVE SKIP: config.toml 里没有 RabbitMQ 连接",
            ConnectionStore.connections.value.firstOrNull { it.serviceId == "rabbitmq" },
        )
        return rabbitMqConfigFromOptions(info.options)
    }

    /** 建一条会话用的连接；连不上 = SKIP（环境问题不该让门禁红）。 */
    private suspend fun connectionOrSkip(config: RabbitMqConfig): Connection =
        try {
            withContext(Dispatchers.IO) { rabbitMqConnectionFactory(config).newConnection() }
        } catch (error: Throwable) {
            assumeLive("LIVE SKIP: AMQP 连接失败（${error::class.simpleName}: ${error.message}）", false)
            @Suppress("UNREACHABLE_CODE")
            throw error
        }

    private fun uniqueQueue(prefix: String) = "$prefix.${System.nanoTime()}"

    /** 建一个临时队列并返回名字；调用方负责删。 */
    private suspend fun declareQueue(
        channel: Channel,
        name: String,
        arguments: Map<String, Any?> = emptyMap(),
    ) {
        withContext(Dispatchers.IO) {
            // RabbitMQ 4.x 禁止「瞬时 + 非独占」队列（541 transient_nonexcl_queues）。
            // 用独占 + autoDelete：连接关闭时 broker 自动删，不需要单独清理。
            channel.queueDeclare(name, false, true, true, arguments)
        }
    }

    /* ------------------------------------------------------------------ */

    /**
     * 大消息：接近 `frameMax` 与超过它的边界。
     *
     * AMQP 0-9-1 的 body 会被拆成多个 frame 传输，`basicGet` 要把它们重组回来。
     * 这里发 512B / 128KB / 4MB 三档，验证 `body.size` 与内容无损 —— 尤其是
     * **多 frame 重组**（128KB 远超默认 frameMax 131072？见打印的实际值）。
     */
    @Test
    fun liveLargeMessagesRoundTripIntact() {
        runBlocking {
            val config = configOrSkip()
            val connection = connectionOrSkip(config)
            val queue = uniqueQueue("mqclient.live.large")
            try {
                val channel = withContext(Dispatchers.IO) { connection.createChannel() }
                declareQueue(channel, queue)

                val factory = rabbitMqConnectionFactory(config)
                println("=========== RABBITMQ LARGE MESSAGE DIAG ===========")
                println("  requestedFrameMax  = ${factory.requestedFrameMax}")
                println("  connection frameMax = ${connection.negotiatedMaxFrameSize()}")

                val sizes = listOf(512, 128 * 1024, 4 * 1024 * 1024)
                val sent = sizes.map { size ->
                    val body = ByteArray(size) { (it % 251).toByte() }
                    withContext(Dispatchers.IO) {
                        channel.basicPublish("", queue, null, body)
                    }
                    size to body
                }

                val session = RabbitMqMessageSession.onSharedConnection(queue, connection, channel)
                try {
                    val fetched = session.fetchNext(sizes.size)
                    assertEquals(sizes.size, fetched.size, "应取回全部 ${sizes.size} 条")
                    sizes.indices.forEach { i ->
                        val (size, body) = sent[i]
                        val got = fetched[i].body
                        assertEquals(size, got.size, "第 ${i + 1} 条大小不符（$size）")
                        assertTrue(body.contentEquals(got), "第 ${i + 1} 条内容被破坏（size=$size）")
                        println("  size=$size -> fetched=${got.size} 一致")
                    }
                    // 交错验证：大消息之间的边界不能串位（每条内容都是自己的模式）。
                    assertEquals(sizes.size, fetched.map { it.deliveryTag }.toSet().size, "deliveryTag 应互不相同")
                    session.requeueAll()
                } finally {
                    session.close()
                }
            } finally {
                cleanup(connection, queue)
            }
        }
    }

    /**
     * 并发拉取：同一会话被多个协程同时 `fetchNext` 时，**不能重复交付同一批**，
     * 也不能让 deliveryTag 账本错乱。
     *
     * 生产上 UI 有 `fetchInFlight` 单飞守卫，但那是 UI 层的；会话自身的
     * `channelOps` 才是最后一道闸。这里绕过 UI 直接压会话。
     */
    @Test
    fun liveConcurrentFetchDoesNotDuplicate() {
        runBlocking {
            val config = configOrSkip()
            val connection = connectionOrSkip(config)
            val queue = uniqueQueue("mqclient.live.concurrent")
            try {
                val channel = withContext(Dispatchers.IO) { connection.createChannel() }
                declareQueue(channel, queue)

                val total = 60
                repeat(total) { i ->
                    withContext(Dispatchers.IO) {
                        channel.basicPublish("", queue, null, "concurrent-$i".toByteArray())
                    }
                }

                val session = RabbitMqMessageSession.onSharedConnection(queue, connection, channel)
                try {
                    // 4 路并发、每路要 20 条 —— 合计 80 > 60，天然会撞到「取空」。
                    val pages = (1..4).map {
                        async(Dispatchers.Default) { runCatching { session.fetchNext(20) } }
                    }.awaitAll()

                    val delivered = pages.mapNotNull { it.getOrNull() }.flatten()
                    val bodies = delivered.map { String(it.body) }
                    println("  pages=${pages.map { p -> p.getOrNull()?.size ?: "ERR:${p.exceptionOrNull()?.message}" }}")
                    println("  delivered=${delivered.size} unique=${bodies.toSet().size}")
                    println("  heldCount=${session.heldCount()}")

                    assertEquals(
                        bodies.size,
                        bodies.toSet().size,
                        "并发拉取交付了重复消息：${bodies.groupBy { it }.filterValues { it.size > 1 }.keys}",
                    )
                    assertEquals(
                        delivered.size,
                        session.heldCount(),
                        "账本条数必须等于实际交付条数",
                    )
                    assertTrue(delivered.size <= total, "不能凭空多出消息")
                    // 生产上 fetchInFlight 是单飞守卫：并发的后续请求应被明确拒绝，
                    // 而不是各自排到 channelOps 上、把同一批消息取出两次。
                    val rejected = pages.count { it.isFailure }
                    println("  concurrently rejected=$rejected/${pages.size}（单飞守卫生效）")
                    assertTrue(
                        rejected > 0 || pages.count { it.getOrNull()?.isNotEmpty() == true } == 1,
                        "并发 fetchNext 必须有单飞守卫：要么被拒，要么只有一路真正执行",
                    )
                    session.requeueAll()
                } finally {
                    session.close()
                }
            } finally {
                cleanup(connection, queue)
            }
        }
    }

    /**
     * unacked 生命周期：`basicGet(autoAck=false)` 之后消息**仍在队列**（对其他消费者
     * 不可见），`requeueAll` 后重新可见且顺序不被破坏。
     *
     * 这条是「只读浏览」这个核心承诺的真机证明 —— 单测里只有假 channel 的计数器。
     */
    @Test
    fun liveUnackedMessagesStayInQueueUntilRequeued() {
        runBlocking {
            val config = configOrSkip()
            val connection = connectionOrSkip(config)
            val queue = uniqueQueue("mqclient.live.unacked")
            try {
                val channel = withContext(Dispatchers.IO) { connection.createChannel() }
                declareQueue(channel, queue)
                val bodies = (1..5).map { "unacked-$it" }
                bodies.forEach { body ->
                    withContext(Dispatchers.IO) { channel.basicPublish("", queue, null, body.toByteArray()) }
                }

                // 旁观者 channel：用来确认「unacked 期间别人拿不到」。
                val observer = withContext(Dispatchers.IO) { connection.createChannel() }
                val session = RabbitMqMessageSession.onSharedConnection(queue, connection, channel)
                try {
                    val fetched = session.fetchNext(5)
                    assertEquals(5, fetched.size)
                    assertEquals(bodies, fetched.map { String(it.body) }, "顺序应与投递顺序一致")

                    // 此时 5 条都在 unacked：旁观者 basicGet 应该拿不到。
                    val peek = withContext(Dispatchers.IO) { observer.basicGet(queue, true) }
                    println("  observer basicGet while unacked = ${peek?.let { String(it.body) } ?: "null(符合预期)"}")
                    assertTrue(peek == null, "unacked 期间其它消费者不应拿到消息，实际拿到 ${peek?.let { String(it.body) }}")

                    session.requeueAll()
                    assertEquals(0, session.heldCount(), "requeue 后账上应为空")

                    // 放回后旁观者应能按原顺序拿到。
                    val after = (1..5).mapNotNull {
                        withContext(Dispatchers.IO) { observer.basicGet(queue, true)?.let { r -> String(r.body) } }
                    }
                    println("  after requeue, observer got = $after")
                    assertEquals(bodies, after, "requeue 后应完整放回且顺序不变")
                } finally {
                    session.close()
                    runCatching { observer.close() }
                }
            } finally {
                cleanup(connection, queue)
            }
        }
    }

    /**
     * 关闭会话时**必须**把未 ack 的消息还给队列（close 的双保险路径）。
     *
     * 这是用户最容易撞上的：看完消息直接关对话框。若 close 没 requeue，消息会一直
     * 卡在 unacked 直到连接断 —— 别的消费者永远看不到。
     */
    @Test
    fun liveCloseRequeuesUnackedMessages() {
        runBlocking {
            val config = configOrSkip()
            val connection = connectionOrSkip(config)
            val queue = uniqueQueue("mqclient.live.close")
            try {
                val channel = withContext(Dispatchers.IO) { connection.createChannel() }
                declareQueue(channel, queue)
                val bodies = (1..3).map { "close-$it" }
                bodies.forEach { body ->
                    withContext(Dispatchers.IO) { channel.basicPublish("", queue, null, body.toByteArray()) }
                }

                val session = RabbitMqMessageSession.onSharedConnection(queue, connection, channel)
                assertEquals(3, session.fetchNext(3).size)
                session.close()

                // close 之后用新 channel 读：3 条都应回来。
                val observer = withContext(Dispatchers.IO) { connection.createChannel() }
                try {
                    val after = (1..3).mapNotNull {
                        withContext(Dispatchers.IO) { observer.basicGet(queue, true)?.let { r -> String(r.body) } }
                    }
                    println("  after close, got = $after")
                    assertEquals(bodies, after, "close 必须把未 ack 的消息全部放回队列")
                } finally {
                    runCatching { observer.close() }
                }
            } finally {
                cleanup(connection, queue)
            }
        }
    }

    /**
     * 队列不存在时的错误语义：`open` 走 passive 声明，应立刻得到 404 而不是等到
     * 第一次 `basicGet`；且**不能**把共享连接弄坏（别的对话框还在用）。
     */
    @Test
    fun liveMissingQueueFailsFastWithoutBreakingConnection() {
        runBlocking {
            val config = configOrSkip()
            val connection = connectionOrSkip(config)
            val missing = uniqueQueue("mqclient.live.missing")
            try {
                val channel = withContext(Dispatchers.IO) { connection.createChannel() }
                val startedAt = System.currentTimeMillis()
                val error = runCatching { withContext(Dispatchers.IO) { channel.queueDeclarePassive(missing) } }
                    .exceptionOrNull()
                val elapsed = System.currentTimeMillis() - startedAt
                println("  passive declare missing queue -> ${error?.let { "${it::class.simpleName}: ${it.message?.take(80)}" }} in ${elapsed}ms")
                assertTrue(error != null, "不存在的队列应报错")
                assertTrue(elapsed < 5_000, "应快速失败（channelRpcTimeout 内），实际 ${elapsed}ms")
                assertTrue(connection.isOpen, "一次 404 不应把共享连接弄坏")
                runCatching { channel.close() }
            } finally {
                runCatching { withContext(Dispatchers.IO) { connection.close() } }
            }
        }
    }

    /* ------------------------------ 夹具 ------------------------------ */

    private suspend fun cleanup(connection: Connection, queue: String) {
        runCatching {
            val ch = withContext(Dispatchers.IO) { connection.createChannel() }
            withContext(Dispatchers.IO) { ch.queueDelete(queue) }
            runCatching { ch.close() }
        }
        runCatching { withContext(Dispatchers.IO) { connection.close() } }
    }

    private fun Connection.negotiatedMaxFrameSize(): Int =
        try {
            serverProperties["frame_max"]?.let { (it as? Number)?.toInt() } ?: -1
        } catch (e: Exception) {
            -1
        }
}
