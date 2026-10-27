package com.mqclient.rabbitmq.diag

import com.mqclient.core.ConnectionStore
import com.mqclient.rabbitmq.RabbitMqAdminClient
import com.mqclient.rabbitmq.RabbitMqMessageSession
import com.mqclient.rabbitmq.rabbitMqConfigFromOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
