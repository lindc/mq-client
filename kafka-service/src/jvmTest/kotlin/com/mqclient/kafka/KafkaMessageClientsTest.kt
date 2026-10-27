package com.mqclient.kafka

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

/**
 * 共享消息客户端**只建一次**。
 *
 * 这条测试钉死本轮重构的核心断言：同一条连接上反复读 / 发消息，用的必须是同一个
 * consumer / producer 实例，而不是每次新建。旧实现每读一次 `KafkaConsumer(...)`、
 * 每发一条 `KafkaProducer(...)`，那正是「逐次变慢」的来源。
 *
 * **不需要 broker**：`KafkaConsumer` / `KafkaProducer` 的构造只做配置校验（网络发生在
 * 首次 poll / send），所以句柄身份可以在离线环境验证。真正的连通性由
 * `KafkaMessageDiagTest`（`-Dkafkadiag.enabled=true`）覆盖。
 */
class KafkaMessageClientsTest {

    private fun config() = KafkaConfig(
        bootstrapServers = "127.0.0.1:9092",
        connectionTimeout = 5,
    )

    @Test
    fun `browse consumer is created once and reused`() = runBlocking {
        val clients = KafkaMessageClients(config())
        try {
            val first = clients.browseConsumer()
            val second = clients.browseConsumer()
            val third = clients.browseConsumer()

            assertNotNull(first)
            assertSame(first, second, "重复取用必须返回同一个 consumer —— 每次新建就是「逐次变慢」")
            assertSame(first, third)
        } finally {
            clients.closeQuietly()
        }
    }

    @Test
    fun `producer is created once and reused`() = runBlocking {
        val clients = KafkaMessageClients(config())
        try {
            val first = clients.producer()
            val second = clients.producer()

            assertSame(first, second, "重复取用必须返回同一个 producer —— 构造即起 sender 线程，很贵")
        } finally {
            clients.closeQuietly()
        }
    }

    @Test
    fun `close then reuse creates a fresh consumer`() = runBlocking {
        val clients = KafkaMessageClients(config())
        val first = clients.browseConsumer()
        clients.closeQuietly()

        val second = clients.browseConsumer()
        try {
            // 关闭后必须能重建（断开重连场景），且**不能**是已关闭的旧句柄。
            assertNotNull(second)
            kotlin.test.assertNotSame(first, second, "关闭之后必须重建，不能把已关的句柄交出去")
        } finally {
            clients.closeQuietly()
        }
    }

    @Test
    fun `consumer properties are the single source of truth`() {
        val props = KafkaMessageClients.browseConsumerProperties(config(), maxPollRecords = 42)

        // 固定组名（旧实现每次带 UUID → broker 侧攒一堆幽灵组）
        kotlin.test.assertEquals(KafkaMessageClients.BROWSE_GROUP_ID, props["group.id"])
        // 浏览不消费
        kotlin.test.assertEquals(false, props["enable.auto.commit"])
        kotlin.test.assertEquals(42, props["max.poll.records"])
    }
}
