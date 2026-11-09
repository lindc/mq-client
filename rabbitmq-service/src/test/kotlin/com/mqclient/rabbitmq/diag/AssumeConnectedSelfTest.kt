package com.mqclient.rabbitmq.diag

import com.mqclient.testing.assumeConnected
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.rabbitmq.client.ConnectionFactory

/**
 * 验证 [assumeConnected] 的方向性：连不上 → SKIP，而不是 FAIL。
 *
 * 这是门禁可信度的自检 —— 用**一定连不上**的端口（localhost:1，未监听）触发，
 * 断言测试以 skipped 结束。如果这条变成 FAIL，说明兜底失效、RabbitMQ 停机时
 * 全量门禁又会红。
 */
class AssumeConnectedSelfTest {

    @Test
    fun unreachableTargetIsSkippedNotFailed(): Unit {
        runBlocking {
        val factory = ConnectionFactory().apply {
            host = "127.0.0.1"
            port = 1            // 保留端口，不可能有 AMQP 服务
            username = "x"
            password = "y"
            connectionTimeout = 1_000
            handshakeTimeout = 1_000
        }
        val connection = assumeConnected("SelfTest-unreachable") {
            runBlocking(Dispatchers.IO) { factory.newConnection() }
        }
        // 走到这里说明兜底失效了。
        connection.close()
        fail("连不上时应当 SKIP（AssumptionViolatedException），不该走到这里")
        }
    }
}
