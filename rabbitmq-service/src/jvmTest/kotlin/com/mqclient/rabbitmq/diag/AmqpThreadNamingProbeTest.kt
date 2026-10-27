package com.mqclient.rabbitmq.diag

import com.mqclient.core.ConnectionStore
import com.mqclient.rabbitmq.rabbitMqConfigFromOptions
import com.mqclient.rabbitmq.rabbitMqConnectionFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 探针：确认 amqp-client 连接到底创建了哪些线程（名字长什么样）。
 *
 * 存在的理由：之前的泄漏诊断用「线程名包含 AMQP」计数，结果恒为 0 —— 需要确认
 * 是「没有线程」还是「名字对不上」，否则那个指标就是空的、结论不可信。
 */
class AmqpThreadNamingProbeTest {

    @Test
    fun probeThreadNames() = runBlocking {
        val info = ConnectionStore.connections.value.firstOrNull { it.serviceId == "rabbitmq" }
        if (info == null) {
            println("PROBE SKIP: 没有 RabbitMQ 连接")
            return@runBlocking
        }
        val config = rabbitMqConfigFromOptions(info.options)

        fun snapshot(tag: String): Int {
            val all = Thread.getAllStackTraces().keys.filter { it.isAlive }
            val amqp = all.filter { it.name.startsWith("AMQP Connection ", ignoreCase = true) }
            println("PROBE[$tag] 总线程=${all.size} 连接线程=${amqp.size} 名字=${amqp.map { it.name }}")
            return amqp.size
        }

        assertEquals(0, snapshot("before"), "开始前不应有 AMQP 连接线程")
        val conn = withContext(Dispatchers.IO) { rabbitMqConnectionFactory(config).newConnection() }
        delay(1_200)
        assertEquals(
            1,
            snapshot("after-open"),
            "一条 Connection 应对应恰好 1 个 `AMQP Connection <host>:<port>` 线程 —— " +
                "这个指标被 RabbitMqSharedConnectionLiveTest 用作「物理连接数」的证据。",
        )

        val ch = withContext(Dispatchers.IO) { conn.createChannel() }
        delay(600)
        assertEquals(1, snapshot("after-channel"), "channel 不额外起线程，连接数应仍为 1")

        runCatching { withContext(Dispatchers.IO) { ch.abort() } }
        runCatching { withContext(Dispatchers.IO) { conn.close(3_000) } }
        delay(1_500)
        assertEquals(
            0,
            snapshot("after-close"),
            "close(3s) 后线程必须归零 —— 否则就是连接/线程泄漏。",
        )
    }
}
