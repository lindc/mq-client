package com.mqclient.rabbitmq.diag

import com.mqclient.core.ConnectionStore
import com.mqclient.rabbitmq.rabbitMqConfigFromOptions
import com.mqclient.rabbitmq.rabbitMqConnectionFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.mqclient.testing.assumeNotNull
import com.mqclient.testing.assumeConnected

/**
 * 真机验证：心跳与超时配置真的落到了连接上。
 *
 * 这条测试针对本轮加固的两个断言：
 *  1. `requestedHeartbeat = 60` 确实传到了 `ConnectionFactory`；
 *  2. 建出的连接**实际协商出的**心跳间隔非 0（broker 会回一个它认可的值，
 *     通常等于客户端请求值；若为 0 说明心跳被禁用，静默死连接就探测不到）。
 *
 * 断言 2 是真正重要的那条：只看 `factory.requestedHeartbeat` 只能证明「我们设了」，
 * 证明不了「连接上生效了」。`Connection.getHeartbeat()` 是协商结果。
 */
class RabbitMqHeartbeatLiveTest {

    @Test
    fun liveHeartbeatIsNegotiated() = runBlocking {
        // 无环境 = SKIP（assumption），不是 PASSED —— 旧写法 return 让 CI 永远绿。
        val info = assumeNotNull(
            "LIVE SKIP: config.toml 里没有 RabbitMQ 连接",
            ConnectionStore.connections.value.firstOrNull { it.serviceId == "rabbitmq" },
        )
        val config = rabbitMqConfigFromOptions(info.options)
        println("=========== RABBITMQ HEARTBEAT DIAG ===========")
        println("  target = ${config.host}:${config.amqpPort}")

        val factory = rabbitMqConnectionFactory(config)
        println("  factory.requestedHeartbeat = ${factory.requestedHeartbeat}")
        assertEquals(60, factory.requestedHeartbeat, "requestedHeartbeat 必须是 60 秒")
        println("  factory.channelRpcTimeout = ${factory.channelRpcTimeout}")
        println("  factory.shutdownTimeout   = ${factory.shutdownTimeout}")
        println("  factory.handshakeTimeout  = ${factory.handshakeTimeout}")
        // 30s：basicGet 的返回包含整个消息体，真机实测公网约 700KB/s，
        // 5s 会让 3.5MB 以上的消息必然超时（见 AMQP_CHANNEL_RPC_TIMEOUT_MS 的说明）。
        assertEquals(30_000, factory.channelRpcTimeout)
        assertEquals(3_000, factory.shutdownTimeout)

        // 环境未就绪 = SKIP（不是 FAIL）：门禁只该反映代码问题。
        val connection = assumeConnected("RabbitMQ") {
            runBlocking(Dispatchers.IO) { factory.newConnection() }
        }
        try {
            val negotiated = connection.getHeartbeat()
            println("  连接协商出的心跳（getHeartbeat）= $negotiated 秒")
            assertTrue(
                negotiated > 0,
                "协商出的心跳必须 > 0 —— 0 意味着心跳被禁用，断网/休眠造成的静默死连接" +
                    "永远探测不到，isOpen 会一直是 true，每次 basicGet 都硬等 5s。",
            )
            // broker 通常原样接受客户端请求值；不强求精确相等（版本差异允许向下调整）。
            assertTrue(
                negotiated <= 60,
                "协商值不应超过请求值（60），实测 $negotiated",
            )
            println("  connection.isOpen = ${connection.isOpen}")
        } finally {
            runCatching { withContext(Dispatchers.IO) { connection.close(3_000) } }
            println("=========== DIAG DONE ===========")
        }
    }
}
