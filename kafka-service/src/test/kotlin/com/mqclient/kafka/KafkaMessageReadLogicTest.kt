package com.mqclient.kafka

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition

/**
 * [KafkaMessageReadLogic] 的纯逻辑单测（离线，不连 broker）+ 连接属性四块复用断言。
 */
class KafkaMessageReadLogicTest {

    private val tp0 = TopicPartition("t", 0)
    private val tp1 = TopicPartition("t", 1)
    private val tps = listOf(tp0, tp1)

    private fun record(
        partition: Int,
        offset: Long,
        timestamp: Long,
        value: ByteArray = byteArrayOf(1),
    ): ConsumerRecord<ByteArray, ByteArray> = ConsumerRecord(
        "t", partition, offset, timestamp,
        org.apache.kafka.common.record.TimestampType.CREATE_TIME,
        -1L, -1, -1, null, value,
        org.apache.kafka.common.header.internals.RecordHeaders(),
    )

    // ---------------------------------------------------- computeSeekTargets

    @Test
    fun `Latest - seek 到 end-N`() {
        val targets = KafkaMessageReadLogic.computeSeekTargets(
            KafkaReadStart.Latest(count = 10), tps,
            beginnings = mapOf(tp0 to 0L, tp1 to 5L),
            ends = mapOf(tp0 to 100L, tp1 to 50L),
        )
        assertEquals(90L, targets[tp0])
        // tp1: earliest 5, end 50 → 50-10 = 40（> earliest，不钳位）
        assertEquals(40L, targets[tp1])
    }

    @Test
    fun `Latest - end-N 跨过 retention 起点时钳到 earliest`() {
        val targets = KafkaMessageReadLogic.computeSeekTargets(
            KafkaReadStart.Latest(count = 100), listOf(tp0),
            beginnings = mapOf(tp0 to 42L),
            ends = mapOf(tp0 to 100L),
        )
        // 100 - 100 = 0 < earliest 42 → 钳位（否则 OutOfRange）
        assertEquals(42L, targets[tp0])
    }

    @Test
    fun `Earliest - seek 到 beginning，缺表项回 0`() {
        val targets = KafkaMessageReadLogic.computeSeekTargets(
            KafkaReadStart.Earliest(count = 10), tps,
            beginnings = mapOf(tp0 to 7L),
            ends = emptyMap(),
        )
        assertEquals(7L, targets[tp0])
        assertEquals(0L, targets[tp1])
    }

    @Test
    fun `AtOffset 与 AtTimestamp - 纯函数不参与（service 层直调 consumer）`() {
        val targets = KafkaMessageReadLogic.computeSeekTargets(
            KafkaReadStart.AtOffset(0, 5), tps, emptyMap(), emptyMap(),
        )
        assertTrue(targets.isEmpty())
        val targets2 = KafkaMessageReadLogic.computeSeekTargets(
            KafkaReadStart.AtTimestamp(1_000), tps, emptyMap(), emptyMap(),
        )
        assertTrue(targets2.isEmpty())
    }

    // ---------------------------------------------------- mergeAndCap

    @Test
    fun `排序 - timestamp 升序再 partition 再 offset`() {
        val merged = KafkaMessageReadLogic.mergeAndCap(
            listOf(
                record(1, 0, 200),
                record(0, 5, 100),
                record(0, 3, 100),
            ),
            count = 10,
        )
        assertEquals(
            listOf(0 to 3L, 0 to 5L, 1 to 0L),
            merged.map { it.partition() to it.offset() },
        )
    }

    @Test
    fun `排序 - 无时间戳(-1)排最后`() {
        val merged = KafkaMessageReadLogic.mergeAndCap(
            listOf(record(0, 0, -1), record(0, 1, 100)),
            count = 10,
        )
        assertEquals(listOf(1L, 0L), merged.map { it.offset() })
    }

    @Test
    fun `截断 - 超采时保留末尾（最新）的 N 条`() {
        val records = (0 until 10).map { record(0, it.toLong(), it.toLong()) }
        val merged = KafkaMessageReadLogic.mergeAndCap(records, count = 3)
        assertEquals(listOf(7L, 8L, 9L), merged.map { it.offset() })
    }

    @Test
    fun `截断 - count 非正返回空`() {
        assertTrue(KafkaMessageReadLogic.mergeAndCap(listOf(record(0, 0, 0)), count = 0).isEmpty())
    }

    // ---------------------------------------------------- nextCursorOf

    @Test
    fun `游标 - 只记实际读到的分区，值为 maxOffset+1`() {
        val cursor = KafkaMessageReadLogic.nextCursorOf(
            listOf(record(0, 4, 1), record(0, 7, 2), record(2, 9, 3)),
        )
        assertEquals(mapOf(0 to 8L, 2 to 10L), cursor)
    }

    @Test
    fun `游标 - 空记录返回空表`() {
        assertTrue(KafkaMessageReadLogic.nextCursorOf(emptyList()).isEmpty())
    }

    // ---------------------------------------------------- toConnectionProperties

    @Test
    fun `连接属性 - SASL_SSL 复用四块且不带 AdminClient 专属超时`() {
        val config = KafkaConfig(
            bootstrapServers = "b1:9092",
            securityProtocol = "SASL_SSL",
            saslMechanism = "SCRAM-SHA-256",
            username = "app",
            password = "sec\"ret",
            truststorePath = "/tmp/t.p12",
            truststorePassword = "store-pass",
            connectionTimeout = 15,
        )
        val props: Properties = config.toConnectionProperties()
        assertEquals("b1:9092", props.getProperty("bootstrap.servers"))
        assertEquals("SASL_SSL", props.getProperty("security.protocol"))
        assertEquals("/tmp/t.p12", props.getProperty("ssl.truststore.location"))
        assertEquals("PKCS12", props.getProperty("ssl.truststore.type"))
        assertEquals("store-pass", props.getProperty("ssl.truststore.password"))
        assertEquals("SCRAM-SHA-256", props.getProperty("sasl.mechanism"))
        val jaas = props.getProperty("sasl.jaas.config")
        assertTrue(jaas.startsWith("org.apache.kafka.common.security.scram.ScramLoginModule required "))
        assertTrue(jaas.contains("username=\"app\""))
        // 双引号被转义，JAAS 解析不炸
        assertTrue(jaas.contains("password=\"sec\\\"ret\""))
        // AdminClient 专属键不得漏进 consumer/producer
        assertTrue(!props.containsKey("request.timeout.ms"))
        assertTrue(!props.containsKey("default.api.timeout.ms"))
        // Admin 属性 = 连接四块 + 两个超时（put 的是 Int，getProperty 读不到字符串形式）。
        // 15 秒配得比 MIN_ADMIN_REQUEST_TIMEOUT_MS（30 秒）小，按**下限**取值：
        // 这个值同时是全部 Admin 请求的上限，配成 15 秒会让大集群的正常请求注定超时
        // （消费组列表就是被它坑过的一个）。连接验证本身仍按 connectionTimeout 传参。
        val admin = config.toAdminProperties()
        assertEquals(KafkaConfig.MIN_ADMIN_REQUEST_TIMEOUT_MS, admin.get("request.timeout.ms"))
        assertEquals(KafkaConfig.MIN_ADMIN_REQUEST_TIMEOUT_MS, admin.get("default.api.timeout.ms"))
    }

    @Test
    fun `连接属性 - PLAINTEXT 无 SASL 与 TLS 键`() {
        val props = KafkaConfig(bootstrapServers = "b:9092").toConnectionProperties()
        assertEquals(2, props.size.toLong())
    }
}
