package com.mqclient.kafka

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [KafkaConfig] 的 options roundtrip：扫描间隔两个新键与既有键一起落盘 / 还原，
 * 缺键 / 脏值回落默认值（与 ibm-mq 的 IbmMQConfig 同一套容错语义）。
 */
class KafkaConfigTest {

    @Test
    fun `roundtrip - 全字段落盘还原`() {
        val config = KafkaConfig(
            bootstrapServers = "host1:9092,host2:9092",
            securityProtocol = "SASL_SSL",
            saslMechanism = "SCRAM-SHA-256",
            username = "app",
            password = "secret",
            truststorePath = "/tmp/t.p12",
            truststorePassword = "store-pass",
            connectionTimeout = 15,
            objectScanIntervalMinutes = 30,
            lagScanIntervalSeconds = 60,
        )
        val restored = kafkaConfigFromOptions(config.toOptions())
        assertEquals(config, restored)
    }

    @Test
    fun `缺键 - 回落默认值（含扫描间隔）`() {
        val restored = kafkaConfigFromOptions(emptyMap())
        assertEquals(KafkaConfig(), restored)
        assertEquals(10, restored.objectScanIntervalMinutes)
        assertEquals(30, restored.lagScanIntervalSeconds)
    }

    @Test
    fun `脏值 - 解析失败回落默认值`() {
        val restored = kafkaConfigFromOptions(
            mapOf(
                "objectScanIntervalMinutes" to "not-a-number",
                "lagScanIntervalSeconds" to "30s",
            ),
        )
        assertEquals(KafkaConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES, restored.objectScanIntervalMinutes)
        assertEquals(KafkaConfig.DEFAULT_LAG_SCAN_INTERVAL_SECONDS, restored.lagScanIntervalSeconds)
    }

    @Test
    fun `可解析的越界值 - 原样透传（范围校验在表单层，与 IbmMQConfig 同语义）`() {
        val restored = kafkaConfigFromOptions(mapOf("lagScanIntervalSeconds" to "-5"))
        assertEquals(-5, restored.lagScanIntervalSeconds)
    }

    @Test
    fun `扫描间隔的合法值 - 原样保留`() {
        val restored = kafkaConfigFromOptions(
            mapOf("objectScanIntervalMinutes" to "1440", "lagScanIntervalSeconds" to "3600"),
        )
        assertEquals(1440, restored.objectScanIntervalMinutes)
        assertEquals(3600, restored.lagScanIntervalSeconds)
    }
}
