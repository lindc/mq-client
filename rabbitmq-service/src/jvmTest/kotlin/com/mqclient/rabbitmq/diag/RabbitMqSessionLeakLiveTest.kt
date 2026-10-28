package com.mqclient.rabbitmq.diag

import com.mqclient.core.ConnectionStore
import com.mqclient.rabbitmq.RabbitMqAdminClient
import com.mqclient.rabbitmq.RabbitMqMessageSession
import com.mqclient.rabbitmq.rabbitMqConfigFromOptions
import com.mqclient.rabbitmq.rabbitMqConnectionFactory
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.mqclient.testing.assumeNotNull
import com.mqclient.testing.assumeLive

/**
 * 真机验证：消息会话的 open / fetch / close 循环**不泄漏连接**。
 *
 * 验证的是这次修复的核心断言 —— 旧实现在 `basicGet` 卡住时会把 channel 互斥锁永久
 * 持有，`close()` 排在锁上等死，每条会话漏一个 `Connection`（= 1 TCP socket +
 * 心跳线程 + consumer executor）。表现就是「第一次打开快，第二次慢，第三次更慢」。
 *
 * 三个断言：
 *  1. **不泄漏**：open/fetch/close 循环 N 次后，AMQP 线程数回落到基线附近；
 *  2. **close 幂等且不挂起**：连调三次都立即返回，不抛异常；
 *  3. **close 后操作快速失败**：`fetchNext` 立刻抛「已关闭」，而不是挂起等锁。
 *
 * 需要 `~/.mqclient/config.toml` 里有可用的 RabbitMQ 连接，且队列里最好有消息。
 * 没有配置 / 没有网络时打印 SKIP 并通过 —— 不让环境问题污染离线 CI。
 */
class RabbitMqSessionLeakLiveTest {

    /** 循环次数：够看出线性增长，又不至于把测试拖成分钟级。 */
    private val cycles = 8

    @Test
    fun liveSessionOpenCloseDoesNotLeakConnections() = runBlocking {
        // 无环境 = SKIP（assumption），不是 PASSED。
        val info = assumeNotNull(
            "LIVE SKIP: config.toml 里没有 RabbitMQ 连接",
            ConnectionStore.connections.value.firstOrNull { it.serviceId == "rabbitmq" },
        )
        val config = rabbitMqConfigFromOptions(info.options)
        println("=========== RABBITMQ SESSION LEAK DIAG ===========")
        println("  connection = ${info.name} (${info.id})")
        println("  target     = ${config.host}:${config.amqpPort} vhost=${config.vhost}")
        println("  user       = ${config.username}")

        // ---- 选一个能拉取的队列：优先有消息的，其次任意一个 ----
        // assumption 异常会穿出 withTimeout（它不是 CancellationException），所以
        // 直接 assume 即可 —— 不再需要「返回 null 再在表达式外判空」的老写法。
        val queueName = withTimeout(20_000) {
            RabbitMqAdminClient(config).use { admin ->
                val queues = runCatching { admin.queues() }.getOrElse { error ->
                    assumeLive("LIVE SKIP: 管理 API 不可用（${error.message}）", false)
                    @Suppress("UNREACHABLE_CODE")
                    throw error
                }
                val withMessages = queues.firstOrNull { it.messages > 0 }
                val picked = assumeNotNull(
                    "LIVE SKIP: vhost 里没有队列",
                    withMessages ?: queues.firstOrNull(),
                )
                println(
                    "  queue      = ${picked.name} (messages=${picked.messages}" +
                        "${if (withMessages != null) "，有消息可拉" else "，空队列"}）",
                )
                picked.name
            }
        }

        // ---- 基线：先跑一次让 amqp-client 的线程池建起来，再记基线 ----
        withTimeout(30_000) {
            RabbitMqMessageSession.open(config, queueName).close()
        }
        settle()
        val baseline = amqpThreads()
        val baselineServerConns = serverConnectionCount(config)
        println("  --- 基线 ---")
        println("  本地 AMQP 线程数 = $baseline")
        println("  服务端连接数     = ${baselineServerConns ?: "查不到（管理 API 不可用或无权限）"}")

        // ---- 循环 open / fetch / close ----
        val samples = mutableListOf<Int>()
        var totalFetched = 0
        repeat(cycles) { index ->
            val elapsed = measureTimeMillis {
                withTimeout(30_000) {
                    val session = RabbitMqMessageSession.open(config, queueName)
                    try {
                        // 少量拉取：只是为了走一遍 basicGet 与 heldTags 记账，
                        // 拉到的消息在 close 时由 requeueHeldOnChannel 放回队列。
                        val batch = session.fetchNext(5)
                        totalFetched += batch.size
                        assertEquals(batch.size, session.heldCount(), "heldCount 应等于本次拉到的条数")
                    } finally {
                        session.close()
                    }
                }
            }
            settle()
            val threads = amqpThreads()
            samples += threads
            println("  cycle ${index + 1}/$cycles: ${elapsed}ms, 本地 AMQP 线程=$threads")
        }

        settle()
        val finalThreads = amqpThreads()
        val finalServerConns = serverConnectionCount(config)
        println("  --- 结果 ---")
        println("  总共拉到 $totalFetched 条（已 requeue 回队列）")
        println("  本地 AMQP 线程：基线=$baseline  各轮=$samples  最终=$finalThreads")
        println("  服务端连接数：基线=$baselineServerConns  最终=$finalServerConns")

        // 允许少量波动：amqp-client 回收线程不是同步的，也会保留少量池化线程。
        val slack = 2
        assertTrue(
            finalThreads <= baseline + slack,
            "循环 $cycles 次后本地 AMQP 线程数应回落到基线附近：" +
                "基线=$baseline，最终=$finalThreads，各轮=$samples —— " +
                "线性增长即说明会话没有真正关闭（连接/线程泄漏）。",
        )

        // 服务端视角（更硬）：broker 亲口说现在有几个客户端连着。
        // 泄漏的话这里会线性增长 —— 这是「第一次快、第二次慢」的直接证据。
        if (baselineServerConns != null && finalServerConns != null) {
            assertTrue(
                finalServerConns <= baselineServerConns + slack,
                "循环 $cycles 次后 broker 上的连接数应回落到基线：" +
                    "基线=$baselineServerConns，最终=$finalServerConns —— " +
                    "线性增长即说明每次会话都在 broker 侧留下了一条未关闭的连接。",
            )
        } else {
            println("  注意：服务端连接数不可用（账号可能缺 monitoring tag），本节断言跳过")
        }

        // ---- close 幂等：连调三次都该立即返回 ----
        val closeElapsed = measureTimeMillis {
            withTimeout(15_000) {
                val session = RabbitMqMessageSession.open(config, queueName)
                try {
                    session.close()
                    session.close()
                    session.close()
                } finally {
                    session.close()
                }
            }
        }
        println("  close ×4（幂等）耗时 = ${closeElapsed}ms")
        assertTrue(closeElapsed < 10_000, "close 应当立即返回，实测 ${closeElapsed}ms（疑似在锁上等待）")

        // ---- close 之后 fetchNext 应快速失败，而不是挂起 ----
        val failedFast = measureTimeMillis {
            withTimeout(10_000) {
                val session = RabbitMqMessageSession.open(config, queueName)
                session.close()
                val error = runCatching { session.fetchNext(1) }.exceptionOrNull()
                assertTrue(
                    error is IllegalStateException,
                    "close 后 fetchNext 应抛 IllegalStateException，实际=${error?.let { "${it::class.simpleName}: ${it.message}" }}",
                )
                println("  close 后 fetchNext -> ${error?.message}")
            }
        }
        println("  close 后失败耗时 = ${failedFast}ms")
        assertTrue(failedFast < 5_000, "close 后的操作应立刻失败，实测 ${failedFast}ms")

        settle()
        println("  收尾 AMQP 线程数 = ${amqpThreads()}")
        println("=========== DIAG DONE ===========")
    }

    /**
     * 共享连接上的 **channel 记账**：每一轮「createChannel → passive 声明 → 拉取 → close」
     * 都必须把 channel 号还给 amqp-client 的分配器，且拉到的消息必须回到队列。
     *
     * ## 为什么盯 channel 号，而不是线程数 / 连接数
     *
     * 上面那条测试数的是 `AMQP Connection` 线程与 broker 连接数 —— 那是**独占连接**路径
     * （`ownsConnection = true`，每轮 newConnection + close），连接关掉时 channel 自然一起没了，
     * 所以它**一条 channel 泄漏都抓不到**。生产路径相反：连接由 ConnectionManager 共享，
     * 每个对话框只 `createChannel()`，泄漏就发生在「连接一直活着、channel 没人 abort」这个
     * 形态里。要观察的正是这种形态。
     *
     * `Channel.channelNumber` 是可靠的本地证据：amqp-client 的 `ChannelManager` 用
     * `IntAllocator` 发号，`ChannelN.close(...)` / `abort(...)` 都会经
     * `AMQPConnection.disconnectChannel` → `releaseChannelNumber` 把号还回去
     * （已对 amqp-client 5.22.0 字节码核对：`abort(int,String)` 走的就是带 `releaseChannel`
     * 的那条 close 路径）。所以**号被复用 = channel 被释放；号单调增长 = channel 在漏**。
     *
     * 另外两条断言走**消息**视角（自建诊断队列，不受别人干扰）：
     *  - 每轮都必须能重新拉满同一批消息（上一轮 close 把它们全部 requeue 了）；
     *  - 所有轮次结束后，队列里必须还能取回全部消息 —— 差一条就是“卡在某条
     *    没人关的 channel 上，对其他消费者不可见”，也就是用户能感知的「消息卡住」。
     *
     * 需要 `~/.mqclient/config.toml` 里有可用的 RabbitMQ 连接；无环境时 SKIP。
     * 账号必须能 declare 队列（诊断队列用完即删）；无权限时 SKIP。
     */
    @Test
    fun liveSharedConnectionReleasesChannelsAndUnackedMessages() = runBlocking {
        val info = assumeNotNull(
            "LIVE SKIP: config.toml 里没有 RabbitMQ 连接",
            ConnectionStore.connections.value.firstOrNull { it.serviceId == "rabbitmq" },
        )
        val config = rabbitMqConfigFromOptions(info.options)
        println("=========== RABBITMQ SHARED CHANNEL ACCOUNTING ===========")
        println("  connection = ${info.name} (${info.id})")
        println("  target     = ${config.host}:${config.amqpPort} vhost=${config.vhost}")

        // 一条共享连接跑完全部轮次 —— 与 ConnectionManager.amqpConnection 的形状一致。
        val shared = withContext(Dispatchers.IO) { rabbitMqConnectionFactory(config).newConnection() }
        println("  共享连接已建立: isOpen=${shared.isOpen}")

        // 自己的诊断队列：不受别人 unacked / 别的消费者干扰，断言才能是硬的。
        // 不用临时队列（exclusive / auto-delete）：那些在 setup channel 关掉时会消失。
        // **必须 durable**：RabbitMQ 4.x 默认禁用了 `transient_nonexcl_queues`，
        // pre-declare 非持久且非排他的队列会被 broker 以 541 INTERNAL_ERROR 拒掉。
        // 用完在 finally 里显式 queueDelete。
        val queueName = "mq-client-diag-" + java.util.UUID.randomUUID().toString().take(8)
        val messageCount = 5
        val setup = withContext(Dispatchers.IO) { shared.createChannel() }

        try {
            try {
                withContext(Dispatchers.IO) {
                    setup.queueDeclare(queueName, true, false, false, null)
                    repeat(messageCount) { index ->
                        setup.basicPublish("", queueName, null, "diag-$index".toByteArray())
                    }
                }
            } catch (error: Exception) {
                // 账号无 configure 权限 → 环境不支持这条诊断，不是回归。
                val detail = buildString {
                    append(error::class.simpleName)
                    append(": ").append(error.message ?: "<null>")
                    error.cause?.let { append(" / cause=" ).append(it::class.simpleName).append(": ").append(it.message ?: "<null>") }
                }
                assumeLive("LIVE SKIP: 无法声明诊断队列（$detail）", false)
            }
            println("  诊断队列   = $queueName（已投 $messageCount 条）")

            // 一轮 = 生产路径上「打开一次消息对话框 → 拉一批 → 关掉」。
            suspend fun cycle(index: Int): CycleResult {
                val channel: Channel = withContext(Dispatchers.IO) {
                    shared.createChannel().also { it.queueDeclarePassive(queueName) }
                }
                val session = RabbitMqMessageSession.onSharedConnection(queueName, shared, channel)
                val batch = try {
                    withTimeout(30_000) { session.fetchNext(messageCount) }
                } finally {
                    // close 自己会把未 ack 的 requeue 回队列并 abort channel。
                    session.close()
                }
                val result = CycleResult(channel.channelNumber, batch.size)
                println("  cycle ${index + 1}: channel=${result.channel} 拉到=${result.fetched}")
                return result
            }

            // 先跑一轮建立基线：第一个 channel 号就是「正常复用点」。
            val baseline = cycle(0)
            val samples = mutableListOf(baseline)
            val cycleCount = 10
            repeat(cycleCount) { index -> samples += cycle(index + 1) }
            val numbers = samples.map { it.channel }

            println("  channel 号序列 = $numbers（基线=${baseline.channel}）")
            // 允许多分配几个号：号池释放是同步的，但内部瞬态可能让号往上跳一格。
            // 真正的泄漏是**线性增长**（10 轮 → 号涨 10），不是跳一格。
            val slack = 3
            assertTrue(
                numbers.last() <= baseline.channel + slack,
                "共享连接上的 channel 号不该随开/关次数增长：基线=${baseline.channel}，" +
                    "${cycleCount + 1} 轮后=${numbers.last()}，序列=$numbers —— 号在涨说明每轮 abort 没生效，" +
                    "channel 泄漏会累积在共享连接上（正是「越用越慢」的假设成因）。",
            )

            // 每轮都拉到同样多条：拉不全就说明有消息被卡在某条 channel 上没回到队列。
            samples.forEachIndexed { index, sample ->
                assertEquals(
                    messageCount,
                    sample.fetched,
                    "第 ${index + 1} 轮应能完整拉回 $messageCount 条（上一轮 close 应把它们全部 requeue）" +
                        "，实际 ${sample.fetched} 条 —— 少了就是上一轮的 unacked 没释放。",
                )
            }

            // 最终硬证据：把队列里剩下的全部取出来（autoAck = true，取即确认），
            // 条数必须回到 $messageCount —— 一张“消息卡在死 channel 上”的账。
            val drainChannel = withContext(Dispatchers.IO) { shared.createChannel() }
            val drained = try {
                withContext(Dispatchers.IO) {
                    var count = 0
                    while (drainChannel.basicGet(queueName, true) != null) count++
                    count
                }
            } finally {
                runCatching { withContext(Dispatchers.IO) { drainChannel.abort() } }
            }
            println("  最后从队列取回 = $drained 条（期望 $messageCount）")
            assertEquals(
                messageCount,
                drained,
                "全部轮次结束后队列里应还有 $messageCount 条消息可读：实际 $drained 条 —— " +
                    "差额被卡在某条没人关的 channel 上（unacked 对其他消费者不可见）。",
            )

            assertTrue(shared.isOpen, "会话 close 不得关掉共享连接 —— 所有权在 ConnectionManager。")
            println("=========== DIAG DONE ===========")
        } finally {
            runCatching { withContext(Dispatchers.IO) { setup.queueDelete(queueName) } }
            runCatching { withContext(Dispatchers.IO) { setup.close() } }
            runCatching { withContext(Dispatchers.IO) { shared.close(3_000) } }
            delay(600)
            println("  共享连接已关闭: isOpen=${shared.isOpen}")
        }
    }

    /** 一次「开 → 拉 → 关」的可观测结果。 */
    private data class CycleResult(val channel: Int, val fetched: Int)

    /**
     * 竞态：**close() 落在 ensureChannel() 的开 channel 期间**时，那条新 channel 必须被
     * abort，且不得留下未 ack 的消息。
     *
     * ## 为什么这条是必需的（上一条测试盖不住）
     *
     * 上一条走的是正常路径：每轮 close 都能拿到 `channelOps` → abort 生效 → 号被复用。
     * 但 `RabbitMqMessageSession.ensureChannel()` 的开头会先把 `channel` 置 null
     * （[discardChannel]，上一次 basicGet 失败造成的），重新 `createChannel()` +
     * `queueDeclarePassive()` 都是 RPC。**如果 close() 恰好在这两个 RPC 之间跑完**，它会
     * 看到 `channel == null` 而什么都没 abort；随后 ensureChannel 把新 channel 发布上去 ——
     * 这条 channel 永远不会被关，拉到的消息也就永远 unacked。
     *
     * 测试怎么稳定命中这个窗口：用一个 `Connection` 代理，把 `createChannel()` 放慢
     * 600ms（业务代码一行不改，只是把 RPC 的真实延迟拉宽到可控），然后在这段睡眠里调
     * `close()`：
     *  1. 先 abort 初始 channel，再 fetch 一次 —— 必然失败，`discardChannel` 把
     *     session.channel 置 null（这是进 ensureChannel 的前提）；
     *  2. 再 fetch 一次（它会在代理里睡）→ 200ms 后 close()；
     *  3. 等 fetch 回来，检查代理造出来的那条 channel 是否还活着。
     *
     * 断言是**修复后**的语义：channel 已被 abort、fetch 以 IllegalStateException 失败、
     * 队列里的消息一条不少。修复前这三条都会红 —— 这就是那个竞态的确凿证据。
     */
    @Test
    fun liveCloseDuringChannelOpenMustNotLeakChannel() = runBlocking {
        val info = assumeNotNull(
            "LIVE SKIP: config.toml 里没有 RabbitMQ 连接",
            ConnectionStore.connections.value.firstOrNull { it.serviceId == "rabbitmq" },
        )
        val config = rabbitMqConfigFromOptions(info.options)
        println("=========== RABBITMQ CLOSE-DURING-OPEN DIAG ===========")
        println("  target = ${config.host}:${config.amqpPort} vhost=${config.vhost}")

        val shared = withContext(Dispatchers.IO) { rabbitMqConnectionFactory(config).newConnection() }
        val queueName = "mq-client-diag-race-" + java.util.UUID.randomUUID().toString().take(8)
        val messageCount = 2
        val setup = withContext(Dispatchers.IO) { shared.createChannel() }
        var created: Channel? = null

        try {
            try {
                withContext(Dispatchers.IO) {
                    // durable：RabbitMQ 4.x 默认禁用 `transient_nonexcl_queues`。
                    setup.queueDeclare(queueName, true, false, false, null)
                    repeat(messageCount) { index ->
                        setup.basicPublish("", queueName, null, "race-$index".toByteArray())
                    }
                }
            } catch (error: Exception) {
                assumeLive("LIVE SKIP: 无法声明诊断队列（${error::class.simpleName}: ${error.message}）", false)
            }

            // 代理：只拦 createChannel（通知 → 入睡 → 记账），其余全部转发给真连接。
            // 用 CountDownLatch 而不是 sleep 猜测：fetch 真的进到 createChannel 了才往下走。
            val channelCreating = java.util.concurrent.CountDownLatch(1)
            val slowConnection = java.lang.reflect.Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, methodArgs ->
                val args = methodArgs ?: emptyArray<Any?>()
                if (method.name == "createChannel") {
                    channelCreating.countDown()
                    Thread.sleep(600)
                    method.invoke(shared, *args).also { created = it as Channel }
                } else {
                    method.invoke(shared, *args)
                }
            } as Connection

            // 初始 channel 先关掉：下一次 fetch 必定失败 → discardChannel 把 channel 置 null，
            // 于是再下一次 fetch 会走 ensureChannel（正是要撞的那个窗口）。
            val dead = withContext(Dispatchers.IO) { shared.createChannel() }
            withContext(Dispatchers.IO) { dead.abort(AMQP.REPLY_SUCCESS, "diag: pre-closed") }
            val session = RabbitMqMessageSession.onSharedConnection(queueName, slowConnection, dead)
            val firstError = runCatching { session.fetchNext(1) }.exceptionOrNull()
            println("  第一次 fetch（坏 channel）-> ${firstError?.let { it::class.simpleName } ?: "未失败"}")
            assertTrue(
                firstError != null,
                "前提不成立：已 abort 的 channel 上 fetch 应该失败，否则触发不到 ensureChannel。",
            )

            val raced = async(Dispatchers.IO) { runCatching { session.fetchNext(1) }.exceptionOrNull() }
            // 等 fetch 真的进到 createChannel（代理已经 countDown、正在 sleep）再关，
            // 否则 close 可能跑在 fetch 之前 —— 那样窗口没命中，测试就变成了摆拍。
            val reachedOpen = withContext(Dispatchers.IO) {
                channelCreating.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            session.close() // 此刻 session.channel == null → close 没有任何 channel 可 abort
            val racedError = raced.await()
            assertTrue(reachedOpen, "fetch 应当进到 ensureChannel 的 createChannel（否则没命中竞态窗口）。")

            println(
                "  竞态 fetch -> " +
                    (racedError?.let { "${it::class.simpleName}: ${it.message}" } ?: "成功（拉到了消息）"),
            )
            println("  竞态中新建的 channel -> ${created?.let { "isOpen=${it.isOpen}" } ?: "无"}")

            val leakedChannel = created
            assertTrue(leakedChannel != null, "代理应当造出过一条 channel（否则竞态窗口没被命中）。")
            assertTrue(
                !leakedChannel.isOpen,
                "close() 落在开 channel 期间时，那条迟到的新 channel 必须被 abort：" +
                    "它还活着就说明它永远不会被关 —— 共享连接上多一条死 channel，" +
                    "拉到的消息永久 unacked（对其他消费者不可见）。",
            )
            assertTrue(
                racedError is IllegalStateException,
                "会话已关闭时，迟到的 fetch 应当快速失败（IllegalStateException），" +
                    "实际=${racedError?.let { "${it::class.simpleName}: ${it.message}" } ?: "成功"}",
            )

            val available = withContext(Dispatchers.IO) {
                val probe = shared.createChannel()
                try {
                    var count = 0
                    while (probe.basicGet(queueName, true) != null) count++
                    count
                } finally {
                    runCatching { probe.abort() }
                }
            }
            println("  竞态后队列可取回 = $available 条（期望 $messageCount）")
            assertEquals(
                messageCount,
                available,
                "竞态期间拉到的消息不得卡住：队列里应还有 $messageCount 条可读，实际 $available 条。",
            )
            println("=========== DIAG DONE ===========")
        } finally {
            runCatching { withContext(Dispatchers.IO) { setup.queueDelete(queueName) } }
            runCatching { withContext(Dispatchers.IO) { setup.close() } }
            runCatching { withContext(Dispatchers.IO) { shared.close(3_000) } }
            delay(400)
        }
    }

    /** 当前进程里 amqp-client 创建的线程数（连接、心跳、consumer executor 都带 AMQP 前缀）。 */
    private fun amqpThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name.contains("AMQP", ignoreCase = true) }

    /**
     * 从**服务端**看当前有多少客户端连着（`GET /api/connections`）。
     *
     * 比本地线程计数可靠得多：线程名是 amqp-client 的实现细节（换版本就可能变），
     * 而 broker 的连接表是事实 —— 泄漏的话这里会线性增长。
     *
     * 需要账号有 `monitoring` tag；没有权限 / 管理 API 不可达时返回 null，
     * 由调用方跳过该断言并打印提示。
     */
    private fun serverConnectionCount(config: com.mqclient.rabbitmq.RabbitMqConfig): Int? =
        runCatching {
            val url = java.net.URL(
                "http://${config.host}:${config.managementPort}/api/connections",
            )
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty(
                "Authorization",
                "Basic " + java.util.Base64.getEncoder().encodeToString(
                    "${config.username}:${config.password}".toByteArray(Charsets.UTF_8),
                ),
            )
            try {
                if (connection.responseCode != 200) return null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                // 只数数组里的对象个数，不解析 JSON：连接表就是一层对象数组。
                Regex("\\{").findAll(body).count()
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    /** 给线程回收留一点时间：amqp-client 的 executor 关闭是异步的。 */
    private suspend fun settle() {
        runCatching { kotlinx.coroutines.delay(400) }
    }

    @Suppress("unused")
    private fun rethrowUnlessCancellation(error: Throwable): Throwable {
        if (error is CancellationException) throw error
        return error
    }
}
