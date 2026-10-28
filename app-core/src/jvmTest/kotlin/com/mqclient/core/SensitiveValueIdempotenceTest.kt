package com.mqclient.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 敏感字段加密的**幂等性**（`encryptStoredValue` 遇到已是密文的值不再套一层）。
 *
 * 这条性质防的是一条数据永久损坏链：
 *  1. 用户换了机器 / 配置从别处拷贝 → `secret.key` 不是原来那把；
 *  2. 读配置时每个敏感字段解密失败 → [decryptStoredValue] 保留密文（设计如此：
 *     宁可不显示也不能抹掉）；
 *  3. 用户改了任意一个设置并保存 → 若保存时把「密文当明文」再加密一层，
 *     磁盘上就变成「密文的密文」；
 *  4. 之后即使把正确的 `secret.key` 放回去，解出来也只是 `enc:v1:…` 字面量，
 *     而且每保存一次多嵌一层 —— 真正不可逆。
 *
 * 用 `mqclient.config.dir` 把配置目录重定向到临时目录（与 [ConfigManagerTest]
 * 同一套 test hook），不碰真实的 `~/.mqclient`。
 */
class SensitiveValueIdempotenceTest {

    private lateinit var dir: File
    private var previousOverride: String? = null

    @BeforeTest
    fun setUp() {
        previousOverride = System.getProperty(DIR_PROPERTY)
        dir = Files.createTempDirectory("mqclient-sensitive-").toFile()
        System.setProperty(DIR_PROPERTY, dir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        val previous = previousOverride
        if (previous == null) System.clearProperty(DIR_PROPERTY) else System.setProperty(DIR_PROPERTY, previous)
        dir.deleteRecursively()
    }

    @Test
    fun `encrypt then decrypt round-trips`() {
        val stored = encryptStoredValue("s3cr3t-pa55")

        assertTrue(stored.startsWith(ENCRYPTED_VALUE_PREFIX), "密文必须带 enc:v1: 前缀")
        assertFalse(stored.contains("s3cr3t-pa55"), "密文里不该出现明文")
        assertEquals("s3cr3t-pa55", decryptStoredValue(stored))
    }

    @Test
    fun `encrypting an already encrypted value is a no-op`() {
        val once = encryptStoredValue("hunter2")
        val twice = encryptStoredValue(once)
        val thrice = encryptStoredValue(twice)

        assertEquals(once, twice, "已是密文必须原样返回，否则每保存一次多一层")
        assertEquals(once, thrice)
        assertEquals("hunter2", decryptStoredValue(thrice))
    }

    @Test
    fun `undecryptable value survives a save cycle unchanged`() {
        // 模拟「密钥换了」：用一把本进程不认识的密钥造密文。
        val foreignKey = ByteArray(32) { 0x5A }
        val foreign = ENCRYPTED_VALUE_PREFIX +
            EncryptionService.encrypt("original-secret".encodeToByteArray(), foreignKey)

        // 解密失败 → 保留密文（既有语义，不在这里改）。
        assertEquals(foreign, decryptStoredValue(foreign), "解不开时必须保留原文，不能抹成空串")

        // 关键断言：把读到的值原样写回（真实的「改个设置再保存」），磁盘上不应变化。
        assertEquals(foreign, encryptStoredValue(foreign), "重新加密必须幂等，否则密文被套第二层")
    }

    @Test
    fun `plaintext values still get encrypted`() {
        // 守卫不能让正常明文「漏加密」：不含前缀的值一律加密。
        val stored = encryptStoredValue("PlainText!")

        assertTrue(stored.startsWith(ENCRYPTED_VALUE_PREFIX))
        assertNotEquals("PlainText!", stored)
        assertEquals("PlainText!", decryptStoredValue(stored))
    }

    @Test
    fun `options map serializer only encrypts named sensitive keys and stays idempotent`() {
        val foreignKey = ByteArray(32) { 0x11 }
        val foreign = ENCRYPTED_VALUE_PREFIX +
            EncryptionService.encrypt("user-password".encodeToByteArray(), foreignKey)

        val stored = Json.encodeToString(
            SensitiveOptionsSerializer,
            mapOf("password" to "user-password", "host" to "mq.example.com"),
        )
        assertFalse(stored.contains("user-password"), "password 命中敏感关键字，必须加密")
        assertTrue(stored.contains("mq.example.com"), "非敏感键保持明文")

        // 已是密文（解密失败后留在内存里的形态）→ 序列化时不得再套一层。
        val rewritten = Json.encodeToString(SensitiveOptionsSerializer, mapOf("password" to foreign))
        assertTrue(rewritten.contains(foreign), "密文再序列化必须原样保留，不能被套第二层")
    }

    @Test
    fun `encrypted value written by another key is not silently blanked`() {
        // 与上面同一场景，但断言「不丢数据」这一面：解不开的字段仍保留原文，
        // 而不是被替换成空串（空串会让用户以为密码没了，掩盖真正的问题）。
        val foreignKey = ByteArray(32) { 0x22 }
        val foreign = ENCRYPTED_VALUE_PREFIX +
            EncryptionService.encrypt("abc".encodeToByteArray(), foreignKey)

        val decoded = decryptStoredValue(foreign)
        assertTrue(decoded.startsWith(ENCRYPTED_VALUE_PREFIX))
        assertNotEquals("", decoded)
    }

    private companion object {
        const val DIR_PROPERTY = "mqclient.config.dir"
    }
}
