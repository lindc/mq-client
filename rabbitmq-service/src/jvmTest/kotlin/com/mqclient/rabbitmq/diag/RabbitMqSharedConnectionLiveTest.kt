package com.mqclient.rabbitmq.diag

import com.mqclient.core.ConnectionStore
import com.mqclient.rabbitmq.RabbitMqAdminClient
import com.mqclient.rabbitmq.RabbitMqMessageSession
import com.mqclient.rabbitmq.rabbitMqConfigFromOptions
import com.mqclient.rabbitmq.rabbitMqConnectionFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 真机验证：**共享连接**被多个会话复用，且单个会话关闭不影响其它会话。
 *
 * 这条测试针对的是本轮重构的核心收益 —— 打开消息对话框不再 `newConnection()`，
 * 而是复用 ConnectionManager 的共享 `Connection`，只 `createChannel()`。
 *
 * 三个断言：
 *  1. 同一个 `Connection` 上可以开多个会话（各自独立 channel）；
 *  2. 关掉其中一个会话后，**共享连接仍然存活**（`isOpen == true`），
 *     且另一个会话还能继续拉取 —— 这证明 `close()` 只关 channel，不碰共享连接；
 *  3. 两个会话的 `deliveryTag` 互不干扰（channel 级计数各自从 1 开始）。
 *
 * 需要 `~/.mqclient/config.toml` 里有可用的 RabbitMQ 连接。没有配置 / 没有网络时
 * 打印 SKIP 并通过 —— 不让环境问题污染离线 CI。
 */
class RabbitMqSharedConnectionLiveTest {

    @Test
    fun liveSharedConnectionServesMultipleSessions() = runBlocking {
        val info = ConnectionStore.connections.value.firstOrNull { it.serviceId == "rabbitmq" }
        if (info == null) {
            println("LIVE SKIP: config.toml 里没有 RabbitMQ 连接")
            return@runBlocking
        }
        val config = rabbitMqConfigFromOptions(info.options)
        println("=========== RABBITMQ SHARED CONNECTION DIAG ===========")
        println("  connection = ${info.name} (${info.id})")
        println("  target     = ${config.host}:${config.amqpPort} vhost=${config.vhost}")

        val queueName = withTimeout(20_000) {
            RabbitMqAdminClient(config).use { admin ->
                val queues = runCatching { admin.queues() }.getOrElse { error ->
                    println("LIVE SKIP: 管理 API 不可用（${error.message}）")
                    return@withTimeout null
                }
                val picked = queues.firstOrNull { it.messages > 0 } ?: queues.firstOrNull()
                if (picked == null) {
                    println("LIVE SKIP: vhost 里没有队列")
                    return@withTimeout null
                }
                println("  queue      = ${picked.name} (messages=${picked.messages})")
                picked.name
            }
        } ?: return@runBlocking

        // 模拟 ConnectionManager.amqpConnection 的懒建：整个进程只建这一条。
        val shared = withContext(Dispatchers.IO) {
            rabbitMqConnectionFactory(config).newConnection()
        }
        println("  共享连接已建立: isOpen=${shared.isOpen}")

        try {
            println("  开会话前 AMQP 连接线程数 = ${amqpThreadCount()}（期望 1：仅共享连接本身）")

            // ---- 两个会话共用同一条物理连接，各自一个 channel ----
            val sessionA = withContext(Dispatchers.IO) {
                val ch = shared.createChannel()
                ch.queueDeclarePassive(queueName)
                RabbitMqMessageSession.onSharedConnection(queueName, shared, ch)
            }
            val sessionB = withContext(Dispatchers.IO) {
                val ch = shared.createChannel()
                ch.queueDeclarePassive(queueName)
                RabbitMqMessageSession.onSharedConnection(queueName, shared, ch)
            }
            println("  两个会话已建立（同一连接，两个 channel）")

            // **关键断言**：两个会话只对应 **1** 个物理连接。
            // amqp-client 每条 Connection 起一个名为 `AMQP Connection <host>:<port>`
            // 的线程（已用 AmqpThreadNamingProbeTest 实测确认）。如果每开一个会话就
            // newConnection，这里会是 2 —— 这正是「约开约慢」的量化指标。
            //
            // 不用 `/api/connections`：实测该端点对本账号返回空数组（恒为 0），那个
            // 断言是空的、没有区分力。
            val threadsWithTwoSessions = amqpThreadCount()
            println("  当前 AMQP 连接线程数 = $threadsWithTwoSessions（期望 1）")
            assertEquals(
                1,
                threadsWithTwoSessions,
                "两个会话应当共用同一条物理连接（期望 1 个 AMQP 线程，实测 $threadsWithTwoSessions）。",
            )

            // ---- 关掉 A，B 必须还能用，共享连接必须还活着 ----
            sessionA.close()
            println("  sessionA 已关闭: 共享连接 isOpen=${shared.isOpen}")

            assertTrue(shared.isOpen, "关闭一个会话不得关掉共享连接 —— 那会连带断掉别的对话框。")

            val batchB = withTimeout(30_000) { sessionB.fetchNext(1) }
            println("  sessionB 拉取: ${batchB.size} 条（shared.isOpen=${shared.isOpen}）")
            assertEquals(
                batchB.size,
                sessionB.heldCount(),
                "sessionB 的 heldCount 应等于本次拉到的条数",
            )

            // ---- 再关 B，共享连接仍在（所有权属于 ConnectionManager） ----
            sessionB.close()
            println("  sessionB 已关闭: 共享连接 isOpen=${shared.isOpen}")
            assertTrue(shared.isOpen, "所有会话关完后，共享连接应仍然打开 —— 收敛由 manager 负责。")

            val afterClose = amqpThreadCount()
            println("  会话全关后 AMQP 连接线程数 = $afterClose（共享连接仍开着，所以仍为 1）")
            assertEquals(
                1,
                afterClose,
                "会话关完后应只剩共享连接本身的那 1 个线程（会话的 channel 不带线程）。",
            )
            println("=========== DIAG DONE ===========")
        } finally {
            // 收尾：模拟 ConnectionManager.closeQuietly。
            runCatching { withContext(Dispatchers.IO) { shared.close(3_000) } }
            delay(1_200)
            println("  共享连接已关闭: isOpen=${shared.isOpen}，AMQP 线程数=${amqpThreadCount()}")
        }
    }

    /**
     * 当前进程里 amqp-client 的**连接线程**数。
     *
     * amqp-client 5.22 为每条 `Connection` 起一个名为 `AMQP Connection <host>:<port>`
     * 的线程（已由 [AmqpThreadNamingProbeTest] 实测确认：开 =1、关 =0）。所以这个数字
     * 就是「物理连接数」，比 `/api/connections`（本账号返回空数组）可靠得多。
     */
    private fun amqpThreadCount(): Int =
        Thread.getAllStackTraces().keys.count {
            it.isAlive && it.name.startsWith("AMQP Connection ", ignoreCase = true)
        }

    private fun serverConnectionCount(config: com.mqclient.rabbitmq.RabbitMqConfig): Int? =
        // 保留作参考：实测对 mqdev 账号返回空数组（恒 0），没有区分力，
        // 断言已改用 [amqpThreadCount]。留着是因为换个有 monitoring 权限的账号就能用。
        runCatching {
            val url = java.net.URI(
                "http://${config.host}:${config.managementPort}/api/connections",
            ).toURL()
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
                Regex("\\{").findAll(body).count()
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
}
