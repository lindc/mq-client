package com.mqclient.kafka

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

/**
 * 句柄隔离规范：**重量级 Producer 共享只建一次，Consumer 每次读取独立新建**。
 *
 * 本测试钉死连接级部分（Producer 懒建复用、属性单一来源）。Consumer 已不再由
 * [KafkaMessageClients] 持有，改由 `KafkaMessageService.withBrowseConsumer` 每次
 * 新建并在 finally close —— 编译期即保证本类无法交出共享 Consumer。
 *
 * **不需要 broker**：`KafkaProducer` 的构造只做配置校验（网络发生在首次 send），
 * 所以句柄身份可以在离线环境验证。真正的连通性由 `KafkaMessageDiagTest`
 *（`-Dkafkadiag.enabled=true`）覆盖。
 */
class KafkaMessageClientsTest {

    private fun config() = KafkaConfig(
        bootstrapServers = "127.0.0.1:9092",
        connectionTimeout = 5,
    )

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
    fun `close is idempotent and leaves no shared consumer`() = runBlocking {
        val clients = KafkaMessageClients(config())
        clients.producer()
        clients.closeQuietly()
        // 重复断开（模块 shutdown / 连接重建竞态）不应抛异常，也不再有共享 Consumer
        // 需要 wakeup；只验证收尾幂等。
        clients.closeQuietly()
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
