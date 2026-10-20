package com.mqclient.rocketmq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RocketMqConfig] 的 options 往返与解析回落、TLS 转换层的纯函数（判型 / PEM 编码）。
 * KeyStore 全链路加载在无头测试里没有现成证书库，靠真机冒烟覆盖（`:app-ui:run`）。
 */
class RocketMqConfigTest {

    @Test
    fun `options 往返 - 全字段一致`() {
        val config = RocketMqConfig(
            namesrvAddr = "10.0.0.1:9876;10.0.0.2:9876",
            accessKey = "ak",
            secretKey = "sk",
            tlsEnabled = true,
            tlsVerifyServer = false,
            trustStorePath = "/tmp/trust.p12",
            trustStorePassword = "store-pass",
            keyStorePath = "/tmp/client.jks",
            keyStorePassword = "key-pass",
            connectionTimeout = 42,
            objectScanIntervalMinutes = 7,
            statusScanIntervalSeconds = 9,
        )
        assertEquals(config, rocketMqConfigFromOptions(config.toOptions()))
    }

    @Test
    fun `options 往返 - 默认值`() {
        assertEquals(RocketMqConfig(), rocketMqConfigFromOptions(RocketMqConfig().toOptions()))
    }

    @Test
    fun `布尔解析 - 只认 true 与 false 字面量，垃圾值回落默认`() {
        val options = mapOf(
            "tlsEnabled" to "TRUE",
            "tlsVerifyServer" to "garbage",
        )
        val config = rocketMqConfigFromOptions(options)
        assertTrue(config.tlsEnabled)
        assertTrue(config.tlsVerifyServer) // 垃圾值 → 默认 true

        val off = rocketMqConfigFromOptions(mapOf("tlsEnabled" to "false"))
        assertFalse(off.tlsEnabled)
    }

    @Test
    fun `truststore 路径 - 反斜杠归一为正斜杠`() {
        val config = rocketMqConfigFromOptions(mapOf("trustStorePath" to "C:\\certs\\trust.p12"))
        assertEquals("C:/certs/trust.p12", config.trustStorePath)
    }

    @Test
    fun `keystore 判型 - p12 与 pfx 走 PKCS12，其余走 JKS`() {
        assertEquals("PKCS12", keystoreTypeFor("/tmp/a.p12"))
        assertEquals("PKCS12", keystoreTypeFor("/tmp/A.PFX"))
        assertEquals("JKS", keystoreTypeFor("/tmp/b.jks"))
        assertEquals("JKS", keystoreTypeFor("/tmp/c"))
    }

    @Test
    fun `PEM 编码 - 头尾标签与 64 列 Base64`() {
        val der = ByteArray(200) { it.toByte() }
        val pem = pemBlock(der, "CERTIFICATE")
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.endsWith("\n-----END CERTIFICATE-----\n"))
        val body = pem.removePrefix("-----BEGIN CERTIFICATE-----\n")
            .removeSuffix("\n-----END CERTIFICATE-----\n")
        assertTrue(body.lines().all { it.length <= 64 })
        assertEquals(der.toList(), java.util.Base64.getMimeDecoder().decode(body).toList())
    }
}
