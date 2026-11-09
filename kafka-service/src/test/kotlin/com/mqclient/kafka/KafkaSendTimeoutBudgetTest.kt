package com.mqclient.kafka

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 发送等待上限必须**严格覆盖** producer 自己的失败判定时间。
 *
 * 旧实现 `.get(connectionTimeout + 10s)`（默认 40s）比 `delivery.timeout.ms = 60s`
 * 短，于是 40–60s 这个窗口里 UI 已经报「发送失败」、消息却可能已被 broker 收下 ——
 * 用户点重试就是真实的重复消息。`Future.get` 超时不会取消发送，sender 线程会继续
 * 重试到 delivery 截止。
 *
 * Kafka 的语义是两段：`send()` 最多阻塞 `max.block.ms`（= connectionTimeout），
 * 返回后才开始算 `delivery.timeout.ms`。所以等待上限必须是两者之和再加余量。
 */
class KafkaSendTimeoutBudgetTest {

    @Test
    fun `send wait covers metadata wait plus delivery timeout`() {
        val config = KafkaConfig(connectionTimeout = 30)

        val metadataBudget = config.connectionTimeout * 1_000L
        val deliveryBudget = KafkaMessageClients.DELIVERY_TIMEOUT_MS.toLong()
        val wait = KafkaMessageClients.sendWaitMs(config)

        assertTrue(
            wait > metadataBudget + deliveryBudget,
            "等待上限 $wait ms 必须严格大于 max.block.ms($metadataBudget) + delivery.timeout.ms($deliveryBudget)，" +
                "否则超时上报的「失败」可能只是「还没结论」，消息仍会投递出去",
        )
    }

    @Test
    fun `send wait grows with the configured connection timeout`() {
        val short = KafkaMessageClients.sendWaitMs(KafkaConfig(connectionTimeout = 5))
        val long = KafkaMessageClients.sendWaitMs(KafkaConfig(connectionTimeout = 60))

        assertTrue(long > short, "connectionTimeout 变大时，max.block.ms 也会变大，等待上限必须跟着涨")
    }

    @Test
    fun `producer properties set the same delivery timeout the wait budget assumes`() {
        val props = KafkaMessageClients.producerProperties(KafkaConfig())

        val configured = props["delivery.timeout.ms"]?.toString()
        assertTrue(
            configured == KafkaMessageClients.DELIVERY_TIMEOUT_MS.toString(),
            "producer 的 delivery.timeout.ms 与等待预算必须同源，实际=$configured",
        )
    }
}
