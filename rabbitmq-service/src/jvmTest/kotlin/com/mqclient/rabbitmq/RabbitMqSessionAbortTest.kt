package com.mqclient.rabbitmq

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ChannelContinuationTimeoutException
import com.rabbitmq.client.Connection
import com.rabbitmq.client.impl.AMQImpl
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * 回归：**`abort()` 卡住时不得把 `channelOps` 一起钉死**。
 *
 * ## 现场症状（真机）
 *
 * 「第一次读取消息详细信息就超时，之后彻底不动」。日志显示后台扫描一直健康、进程正常
 * 退出，说明不是 JVM 死锁 —— 是**一个会话把自己锁死了**。
 *
 * ## 机制
 *
 * `ChannelN.abort(int, String)` 走 `close(...)` → `quiescingRpc(Channel.Close, null)`
 * → `quiescingTransmit` → `Frame.writeTo(DataOutputStream)` → 落到
 * `SocketFrameHandler.writeFrame`：拿**每连接一把**的 `_outputStreamLock`，直接写裸
 * socket。Java 的 `SO_TIMEOUT` 只作用于**读**，socket 写没有超时 —— 对端不读时它会
 * 永久阻塞。
 *
 * 旧实现把它同步调在 `channelOps` 临界区里（`drain` / `ack` / `requeueAll` / `publish`
 * 的 catch → `discardChannel`），于是：
 *
 * ```
 * basicGet 等不到回复 → 5s 超时（用户看到「超时」）
 *   → discardChannel → abort() 永久阻塞在 socket 写上
 *   → channelOps（Mutex）永不释放 → 后续每次操作都挤在同一把锁上 → 界面「死」了
 * ```
 *
 * ## 本测试怎么复现
 *
 * 不用 broker：用动态代理造一条 `abort()` **永久阻塞**的假 channel、`basicGet()` 抛
 * `TimeoutException`（模拟 RPC 超时）。于是可以确定性地问两个问题：
 *
 *  1. `fetchNext` 会不会返回？（旧实现：永不返回 → 这条断言直接超时失败）
 *  2. 第二次 `fetchNext` 还能不能进？—— 能，才说明锁被释放了。
 *
 * 另外断言 abort 跑在 `rabbitmq-channel-abort` 后台线程上，而不是调用方线程：
 * 这是「挪出锁」这件事本身可被观测的形态，而不是靠读代码相信。
 */
class RabbitMqSessionAbortTest {

    /** `abort()` 里阻塞的闸门：测试期间不打开（模拟对端永不读取）。 */
    private val neverOpened = CountDownLatch(1)

    /**
     * 测试结束必须放闸。
     *
     * 否则那两条卡在 `abort()` 里的后台线程会留在**进程级** abort 池里，后面同 JVM 的
     * 其它测试（如 `RabbitMqSessionLeakLiveTest` 的 close 路径）拿不到工人，abort 就只能
     * 排队 / 被丢 —— 变成一条测试污染另一条。
     */
    @AfterTest
    fun releaseBlockedAborts() {
        repeat(4) { neverOpened.countDown() }
    }

    /** abort 已经开始（用于断言它真的被调度出去了）。 */
    private val abortStarted = CountDownLatch(2)

    /** 每次 abort 的调用线程名。 */
    private val abortThreads = CopyOnWriteArrayList<String>()

    /** 每次 basicGet 的调用线程名（应该都是测试自己的 fetch 线程）。 */
    private val getThreads = CopyOnWriteArrayList<String>()

    private fun blockingAbortChannel(): Channel {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                // RPC 超时的形态：basicGet 等不到回复。
                // 必须丢**真的** ChannelContinuationTimeoutException：amqp-client 的
                // channelCall 只翻译它与 java.util.concurrent.TimeoutException；而代理
                // 抛受检异常会被包成 UndeclaredThrowableException（basicGet 只声明
                // IOException），那就测不到真正的翻译路径了。
                "basicGet" -> {
                    getThreads += Thread.currentThread().name
                    throw ChannelContinuationTimeoutException(
                        java.util.concurrent.TimeoutException("simulated channel rpc timeout"),
                        proxy,
                        7,
                        AMQImpl.Queue.DeclareOk("diag.queue", 0, 0),
                    )
                }
                // 关键：永久阻塞，模拟 socket 写卡死。
                "abort" -> {
                    abortThreads += Thread.currentThread().name
                    abortStarted.countDown()
                    neverOpened.await()
                    null
                }
                "getChannelNumber" -> 7
                "isOpen" -> true
                "toString" -> "BlockingAbortChannel"
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

    private fun connectionReturning(channel: Channel): Connection {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "createChannel" -> channel
                "isOpen" -> true
                "toString" -> "FakeSharedConnection"
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

    private fun defaultValue(type: Class<*>): Any? = when (type) {
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

    /** 在独立线程上跑一次 fetch，**有界等待**（卡住就失败，而不是把测试一起挂死）。 */
    private fun fetchWithDeadline(session: RabbitMqQueueSession, label: String): Throwable? {
        val future = CompletableFuture<Throwable?>()
        Thread(
            { future.complete(runBlocking { runCatching { session.fetchNext(1) }.exceptionOrNull() }) },
            "abort-test-$label",
        ).apply { isDaemon = true }.start()
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            fail(
                "$label：fetchNext 5s 内没返回 —— channel 的 abort() 卡住时仍然占着 channelOps，" +
                    "后续所有操作都会被永久阻塞（这正是「超时一次、之后彻底不动」的形态）。",
            )
        }
    }

    @Test
    fun `blocking abort does not wedge the session lock`() {
        val channel = blockingAbortChannel()
        val connection = connectionReturning(channel)
        val session = RabbitMqMessageSession.onSharedConnection("diag.queue", connection, channel)

        // 1. 第一次：RPC 超时 → 报废 channel（abort 卡死）
        val first = fetchWithDeadline(session, "first")
        assertTrue(
            first is RabbitMqOperationTimeoutException,
            "第一次 fetch 应把底层超时翻译成领域异常，实际=$first",
        )

        // 2. 第二次：必须还能进 channelOps（锁已释放），而不是排在死锁上
        val second = fetchWithDeadline(session, "second")
        assertTrue(
            second is RabbitMqOperationTimeoutException,
            "第二次 fetch 同样应快速失败（新 channel 也超时），实际=$second",
        )

        // 3. abort 必须跑在后台工人线程上 —— 这是「挪出锁」可观测的形态
        assertTrue(
            abortStarted.await(5, TimeUnit.SECONDS),
            "abort 没有被调度执行（期望至少两次频道报废各触发一次）",
        )
        assertEquals(
            abortThreads.size,
            abortThreads.count { it.startsWith("rabbitmq-channel-abort") },
            "abort 必须跑在后台工人线程上，实际线程=${abortThreads.toList()}",
        )
        assertTrue(
            abortThreads.none { it.startsWith("abort-test-") },
            "abort 不得在 fetch 的调用线程上执行，实际线程=${abortThreads.toList()}",
        )
        // 4. 反向确认：两个动作必须在**不同线程**上，否则「挪出锁」就是空话。
        //    注意 basicGet 跑在 Dispatchers.IO 的 worker 上（fetchNext 里 withContext(Dispatchers.IO)），
        //    不是测试自己造的那条线程 —— 所以比的是集合交集，而不是线程名前缀。
        val overlap = getThreads.toSet() intersect abortThreads.toSet()
        assertTrue(
            getThreads.isNotEmpty(),
            "前提不成立：basicGet 一次都没被调到（拉取根本没走到 RPC）",
        )
        assertTrue(
            overlap.isEmpty(),
            "abort 与 basicGet 不得在同一线程上：RPC 线程=${getThreads.toList()} abort 线程=${abortThreads.toList()}",
        )
    }
}
