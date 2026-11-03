package com.mqclient.ibmmq

import com.ibm.mq.constants.MQConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 连接属性的唯一组装点（[mqProperties]）—— 这里错了，测试连接和正式连接会**同时**错。
 *
 * 重点覆盖加密套件：留空**不是**「自动协商」。真机实测（MQ 客户端 10.0.0.5 对
 * QM2 的 `ECDHE_RSA_AES_128_GCM_SHA256` 通道）留空必报 `MQRC_JSSE_ERROR (2397)`，
 * `ANY_TLS12_OR_HIGHER` / 逗号列表 / IBM 风格名都报 `2400`，只有 Java 套件名能连上。
 * 所以 [mqProperties] 必须给出一个具体默认值 —— 否则 UI 默认项就是一条死路。
 */
class IbmMqPropertiesTest {

    private fun props(
        sslEnabled: Boolean = true,
        cipher: String = "",
        peerName: String = "",
        mode: ConnectionMode = ConnectionMode.CLIENT,
    ) = mqProperties(
        IbmMQConfig(
            mode = mode,
            queueManager = "QM2",
            host = " mq.example.com ",
            port = 1414,
            channel = " DEV.ADMIN.SVRCONN ",
            username = "mqadmin",
            password = "secret",
            sslEnabled = sslEnabled,
            cipherSuite = cipher,
            peerName = peerName,
        ),
    )

    @Test
    fun `blank cipher falls back to a concrete java suite name`() {
        val properties = props(cipher = "  ")

        assertEquals(
            DEFAULT_SSL_CIPHER_SUITE,
            properties[MQConstants.SSL_CIPHER_SUITE_PROPERTY],
            "留空必须落成一个具体套件名 —— 空串会让 JSSE 报 2397",
        )
        assertTrue(
            DEFAULT_SSL_CIPHER_SUITE.startsWith("TLS_"),
            "客户端映射已关掉（useIBMCipherMappings=false），默认值必须是 Java 套件名",
        )
    }

    @Test
    fun `explicit cipher wins over the default`() {
        val properties = props(cipher = "TLS_RSA_WITH_AES_128_CBC_SHA256")

        assertEquals("TLS_RSA_WITH_AES_128_CBC_SHA256", properties[MQConstants.SSL_CIPHER_SUITE_PROPERTY])
    }

    @Test
    fun `cipher is trimmed`() {
        val properties = props(cipher = "  TLS_AES_256_GCM_SHA384 ")

        assertEquals("TLS_AES_256_GCM_SHA384", properties[MQConstants.SSL_CIPHER_SUITE_PROPERTY])
    }

    @Test
    fun `ssl disabled sets no tls property`() {
        val properties = props(sslEnabled = false)

        assertFalse(properties.containsKey(MQConstants.SSL_CIPHER_SUITE_PROPERTY))
        assertFalse(properties.containsKey(MQConstants.SSL_PEER_NAME_PROPERTY))
    }

    @Test
    fun `binding mode sets no network or tls property`() {
        // BINDING 是同机 JNI：没有 TCP，也就没有 TLS 通道。
        val properties = props(mode = ConnectionMode.BINDING)

        assertFalse(properties.containsKey(MQConstants.HOST_NAME_PROPERTY))
        assertFalse(properties.containsKey(MQConstants.PORT_PROPERTY))
        assertFalse(properties.containsKey(MQConstants.SSL_CIPHER_SUITE_PROPERTY))
    }

    @Test
    fun `peer name is only sent when configured`() {
        assertFalse(props().containsKey(MQConstants.SSL_PEER_NAME_PROPERTY))
        assertEquals("CN=mqclient,O=Dev", props(peerName = "CN=mqclient,O=Dev")[MQConstants.SSL_PEER_NAME_PROPERTY])
    }

    @Test
    fun `network values are trimmed`() {
        val properties = props(sslEnabled = false)

        assertEquals("mq.example.com", properties[MQConstants.HOST_NAME_PROPERTY])
        assertEquals("DEV.ADMIN.SVRCONN", properties[MQConstants.CHANNEL_PROPERTY])
    }

    @Test
    fun `credentials are only sent when non-blank`() {
        val withUser = props(sslEnabled = false)
        assertEquals("mqadmin", withUser[MQConstants.USER_ID_PROPERTY])
        assertEquals("secret", withUser[MQConstants.PASSWORD_PROPERTY])

        val anonymous = mqProperties(IbmMQConfig(host = "h", sslEnabled = false))
        assertFalse(anonymous.containsKey(MQConstants.USER_ID_PROPERTY))
        assertFalse(anonymous.containsKey(MQConstants.PASSWORD_PROPERTY))
    }
}
