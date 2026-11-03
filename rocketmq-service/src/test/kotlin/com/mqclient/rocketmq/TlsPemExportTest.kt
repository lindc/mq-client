package com.mqclient.rocketmq

import com.mqclient.core.ServiceException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [pemCertOrNull] / [isPemFile] 的 PEM 检测回归：真实证书解析在 KeyStore 全链路
 * 冒烟覆盖（`:app-ui:run`），这里用构造的 PEM / 二进制文件验证分流逻辑。
 */
class TlsPemExportTest {

    private lateinit var pemCert: Path
    private lateinit var pemKey: Path
    private lateinit var binary: Path
    private lateinit var missing: Path

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("rmq-pem-test")
        pemCert = dir.resolve("ca.crt").apply {
            Files.write(this, "-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----\n".toByteArray())
        }
        pemKey = dir.resolve("only.key").apply {
            Files.write(this, "-----BEGIN PRIVATE KEY-----\nxyz\n-----END PRIVATE KEY-----\n".toByteArray())
        }
        binary = dir.resolve("store.jks").apply {
            Files.write(this, byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte()))
        }
        missing = dir.resolve("nope.crt")
    }

    @AfterTest
    fun tearDown() {
        listOf(pemCert, pemKey, binary).forEach { Files.deleteIfExists(it) }
        Files.deleteIfExists(pemCert.parent)
    }

    @Test
    fun `PEM 证书 - 返回原文件路径直接透传`() {
        assertEquals(pemCert, pemCertOrNull(pemCert.toString()))
        assertTrue(isPemFile(pemCert.toString()))
    }

    @Test
    fun `只有私钥的 PEM - 抛可读错误`() {
        val error = assertFailsWith<ServiceException> { pemCertOrNull(pemKey.toString()) }
        assertEquals("rocketmq_err_pem_no_certificate", error.errorKey)
        assertTrue("No certificate" in error.message.orEmpty())
        assertTrue(isPemFile(pemKey.toString()))
    }

    @Test
    fun `二进制证书库 - 返回 null 走 KeyStore 加载`() {
        assertNull(pemCertOrNull(binary.toString()))
        assertFalse(isPemFile(binary.toString()))
    }

    @Test
    fun `文件不存在 - 统一可读文案`() {
        val error = assertFailsWith<ServiceException> { pemCertOrNull(missing.toString()) }
        assertEquals("rocketmq_err_cert_file_missing", error.errorKey)
        val error2 = assertFailsWith<ServiceException> { isPemFile(missing.toString()) }
        assertEquals("rocketmq_err_cert_file_missing", error2.errorKey)
    }

    @Test
    fun `判型规则 - p12 与 pfx 走 PKCS12 其余走 JKS`() {
        assertEquals("PKCS12", keystoreTypeFor("/tmp/a.p12"))
        assertEquals("PKCS12", keystoreTypeFor("/tmp/A.PFX"))
        assertEquals("JKS", keystoreTypeFor("/tmp/b.jks"))
        assertEquals("JKS", keystoreTypeFor("/tmp/c.pem")) // PEM 不进 KeyStore 加载，但判型规则本身不特判
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
        assertNotNull(pem)
    }
}
