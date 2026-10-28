package com.mqclient.ibmmq

import java.io.File
import java.nio.file.Files
import com.mqclient.testing.assumeLive
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * IBM MQ 客户端的 TLS 证书注入窗口（[IbmMqTls]）。
 *
 * 这些用例守的是此前真实存在的不对称：证书只写进了「测试连接」路径，正式连接
 * (`IbmMQConnectionManager.open`) 一个字节都没拿到；而测试路径还把进程级
 * `SSLContext` 默认上下文换成了 MQ 那个 truststore，且从不还原。
 *
 * 现在两条路径共用 [IbmMqTls.withTls]，覆盖点：
 *  - 窗口内 6 个 JSSE 属性是本连接的值，窗口外严格还原；
 *  - 留空的字段不会继承上一条连接的证书（A 自签 → B 用默认链，不能串味）；
 *  - 证书在**建连之前**预校验：文件缺失 / 口令错 / 无私钥条目给可读文案；
 *  - 客户端写死 JKS 读 keystore，所以 PKCS12 必须转成同口令的临时 JKS，
 *    且临时文件带私钥、用完即删。
 *
 * 证书用 JDK 自带的 `keytool` 现场生成（离线、无外部依赖）。找不到 keytool 时
 * 相关用例记 SKIP —— 与 LiveTest 的假设语义一致：没验过就明说，不伪装成通过。
 */
class IbmMqTlsTest {

    private lateinit var dir: File
    private val touchedProperties = IbmMqTlsTestJSSE_PROPERTIES.associateWith { System.getProperty(it) }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("mqclient-tls-").toFile()
        // 每条用例都从「没有任何 JSSE 配置」开始：属性残留会让断言失去意义。
        IbmMqTlsTestJSSE_PROPERTIES.forEach { System.clearProperty(it) }
    }

    @AfterTest
    fun tearDown() {
        touchedProperties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------ 窗口语义

    @Test
    fun `tls disabled touches no global property`() = runBlocking {
        val config = IbmMQConfig(sslEnabled = false, trustStorePath = "ignored.p12")

        val result = IbmMqTls.withTls(config) {
            assertNoProperty("未启用 SSL 时不该写 trustStore", System.getProperty("javax.net.ssl.trustStore"))
            "ran"
        }

        assertEquals("ran", result)
        assertNoProperty("窗口外也不该留下属性", System.getProperty("javax.net.ssl.trustStore"))
    }

    @Test
    fun `binding mode never touches tls material`() = runBlocking {
        // BINDING 是同机 JNI，没有 TLS 通道 —— 即使配置里勾了 SSL 也不该动全局属性。
        val config = IbmMQConfig(
            mode = ConnectionMode.BINDING,
            sslEnabled = true,
            trustStorePath = "definitely-missing.p12",
        )

        IbmMqTls.withTls(config) {
            assertNoProperty("BINDING 不该写 trustStore", System.getProperty("javax.net.ssl.trustStore"))
        }
    }

    @Test
    fun `window applies this connection's certificates and restores afterwards`() = runBlocking {
        val trust = keystoreFile("trust.p12")
        System.setProperty("javax.net.ssl.trustStore", "/pre-existing/truststore.jks")
        System.setProperty("javax.net.ssl.trustStorePassword", "preexisting")

        val config = IbmMQConfig(
            sslEnabled = true,
            trustStorePath = trust.absolutePath,
            trustStorePassword = PASSWORD,
        )
        IbmMqTls.withTls(config) {
            assertEquals(trust.absolutePath, System.getProperty("javax.net.ssl.trustStore"))
            assertEquals(PASSWORD, System.getProperty("javax.net.ssl.trustStorePassword"))
            assertEquals("PKCS12", System.getProperty("javax.net.ssl.trustStoreType"))
            assertEquals("false", System.getProperty(USE_IBM_CIPHER_MAPPINGS))
        }

        // 还原：属性是进程级的，窗口外必须回到进入前的值（不是「上一次连接的值」）。
        assertEquals("/pre-existing/truststore.jks", System.getProperty("javax.net.ssl.trustStore"))
        assertEquals("preexisting", System.getProperty("javax.net.ssl.trustStorePassword"))
    }

    @Test
    fun `a later connection with no truststore does not inherit the previous one`() = runBlocking {
        // 这就是旧实现的真 bug：applySslSystemProperties 对空字段是「跳过」而不是「清空」，
        // 于是 B（留空 = 用 JVM 默认信任链）会拿 A 的自签 CA 去握手，测试结论是假的。
        val trust = keystoreFile("trust.p12")
        IbmMqTls.withTls(
            IbmMQConfig(sslEnabled = true, trustStorePath = trust.absolutePath, trustStorePassword = PASSWORD),
        ) { }

        IbmMqTls.withTls(IbmMQConfig(sslEnabled = true)) {
            assertEquals(
                "",
                System.getProperty("javax.net.ssl.trustStore").orEmpty(),
                "留空的 truststore 必须是「未配置」，不能继承上一条连接",
            )
            assertEquals("", System.getProperty("javax.net.ssl.keyStore").orEmpty())
        }
    }

    // ------------------------------------------------------------ 预校验

    @Test
    fun `missing truststore fails before the block runs`() = runBlocking {
        var ran = false
        val error = assertFailsWith<IllegalStateException> {
            IbmMqTls.withTls(
                IbmMQConfig(sslEnabled = true, trustStorePath = dir.resolve("nope.p12").absolutePath),
            ) { ran = true }
        }

        assertFalse(ran, "证书不可用时不该开始建连")
        assertTrue(error.message!!.contains("证书文件不存在"), "错误文案要能直接给用户看：${error.message}")
    }

    @Test
    fun `wrong truststore password is reported readably`() = runBlocking {
        val trust = keystoreFile("trust.p12")
        val error = assertFailsWith<IllegalStateException> {
            IbmMqTls.withTls(
                IbmMQConfig(sslEnabled = true, trustStorePath = trust.absolutePath, trustStorePassword = "wrong"),
            ) { }
        }

        assertTrue(
            error.message!!.contains("证书库加载失败"),
            "口令错必须给出可读原因，而不是让握手报 2393：${error.message}",
        )
    }

    @Test
    fun `keystore without a private key entry is rejected`() = runBlocking {
        // 只有证书条目的库不能做双向认证：与其让握手静默失去客户端证书，不如直接拒绝。
        val certStore = trustOnlyStore("certs.p12")
        val error = assertFailsWith<IllegalStateException> {
            IbmMqTls.withTls(
                IbmMQConfig(
                    sslEnabled = true,
                    keyStorePath = certStore.absolutePath,
                    keyStorePassword = PASSWORD,
                ),
            ) { }
        }

        assertTrue(error.message!!.contains("没有私钥条目"), "错误文案：${error.message}")
    }

    // --------------------------------------------------- PKCS12 → JKS 转换

    @Test
    fun `pkcs12 keystore is converted to a temporary jks for the client`() = runBlocking {
        val keyStoreFile = keystoreFile("client.p12")
        val config = IbmMQConfig(
            sslEnabled = true,
            keyStorePath = keyStoreFile.absolutePath,
            keyStorePassword = PASSWORD,
        )

        lateinit var seenPath: String
        IbmMqTls.withTls(config) {
            // 客户端写死 KeyStore.getInstance("JKS")，所以窗口里必须已经是 JKS。
            assertEquals("JKS", System.getProperty("javax.net.ssl.keyStoreType"))
            seenPath = System.getProperty("javax.net.ssl.keyStore").orEmpty()
            assertTrue(seenPath.endsWith(".jks"), "应指向转换出来的临时 JKS：$seenPath")
            assertTrue(File(seenPath).isFile, "窗口内临时 JKS 必须已存在")

            // 同口令可解，且私钥条目还在（否则客户端证书发不出去）。
            val converted = KeyStore.getInstance("JKS").apply {
                File(seenPath).inputStream().use { load(it, PASSWORD.toCharArray()) }
            }
            assertNotNull(
                converted.getEntry(ALIAS, KeyStore.PasswordProtection(PASSWORD.toCharArray())),
                "转换后的库必须保留私钥条目",
            )
        }

        // 含私钥的临时文件不能留在临时目录里。
        assertFalse(File(seenPath).exists(), "临时 keystore 必须在窗口结束时删除")
    }

    @Test
    fun `jks keystore is passed through unchanged`() = runBlocking {
        val jks = jksKeystoreFile("client.jks")
        IbmMqTls.withTls(
            IbmMQConfig(sslEnabled = true, keyStorePath = jks.absolutePath, keyStorePassword = PASSWORD),
        ) {
            assertEquals(jks.absolutePath, System.getProperty("javax.net.ssl.keyStore"))
            assertEquals("JKS", System.getProperty("javax.net.ssl.keyStoreType"))
        }
        assertTrue(jks.exists(), "原文件不该被动过")
    }

    @Test
    fun `keystore type follows the file extension`() {
        assertEquals("PKCS12", IbmMqTls.typeOf("a/b/client.p12"))
        assertEquals("PKCS12", IbmMqTls.typeOf("a/b/client.PFX"))
        assertEquals("JKS", IbmMqTls.typeOf("a/b/client.jks"))
        assertEquals("JKS", IbmMqTls.typeOf("a/b/client"))
    }

    // --------------------------------------------------------------- 工具

    /** 生成一个 PKCS12 keystore（含带私钥的自签证书），同一文件也可当 truststore。 */
    private fun keystoreFile(name: String): File = keytool(name, storeType = "PKCS12", alias = ALIAS)

    /** 生成一个 JKS keystore（客户端写死按 JKS 读的正是这种）。 */
    private fun jksKeystoreFile(name: String): File = keytool(name, storeType = "JKS", alias = ALIAS)

    private fun keytool(name: String, storeType: String, alias: String): File {
        val file = File(dir, name)
        val keytool = keytoolBinary()
        assumeKeytool(keytool)
        val process = ProcessBuilder(
            keytool!!.absolutePath,
            "-genkeypair",
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-storetype", storeType,
            "-keystore", file.absolutePath,
            "-storepass", PASSWORD,
            // 必须显式给 keypass：不给的话 keytool 会**等 stdin 输入**，而 ProcessBuilder
            // 的 stdin 是一条没人写的管道 —— 测试会挂到超时（踩过一次）。
            "-keypass", PASSWORD,
            "-dname", "CN=mqclient-test",
            "-validity", "1",
            "-noprompt",
        ).redirectErrorStream(true).start()
        // 关掉 stdin：即使还有别的交互提示，keytool 拿到的也是 EOF 而不是永久等待。
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        check(finished && process.exitValue() == 0 && file.isFile) {
            "keytool 生成 $name 失败（timeout=$finished, exit=${runCatching { process.exitValue() }.getOrNull()}）：$output"
        }
        return file
    }

    /** 只有证书条目、没有私钥的库。 */
    private fun trustOnlyStore(name: String): File {
        val source = keystoreFile("source-for-$name")
        val store = KeyStore.getInstance("PKCS12").apply {
            source.inputStream().use { load(it, PASSWORD.toCharArray()) }
        }
        val target = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        target.setCertificateEntry("trusted", store.getCertificate(ALIAS))
        val file = File(dir, name)
        file.outputStream().use { target.store(it, PASSWORD.toCharArray()) }
        return file
    }

    private fun keytoolBinary(): File? {
        val home = System.getProperty("java.home") ?: return null
        val candidates = listOf("keytool", "keytool.exe").map { File(home, "bin/$it") }
        return candidates.firstOrNull { it.isFile }
    }

    private fun assumeKeytool(binary: File?) {
        // 与 LiveTest 同一套 assumption 语义：没验过记 SKIP，不伪装成 PASSED。
        assumeLive("跳过：当前 JDK 没有 keytool（${System.getProperty("java.home")}）", binary != null)
    }

    /** 断言属性未设置并说明原因（`assertNull` 的消息参数在部分平台上不显示）。 */
    private fun assertNoProperty(message: String, value: String?) {
        assertTrue(value == null, "$message，实际为 $value")
    }

    private companion object {
        const val PASSWORD = "changeit"
        const val ALIAS = "mqclient"
    }
}

/** [IbmMqTls] 会读写的 JSSE 属性（测试里独立写一份，避免把实现细节当契约导出）。 */
private val IbmMqTlsTestJSSE_PROPERTIES = listOf(
    "javax.net.ssl.trustStore",
    "javax.net.ssl.trustStorePassword",
    "javax.net.ssl.trustStoreType",
    "javax.net.ssl.keyStore",
    "javax.net.ssl.keyStorePassword",
    "javax.net.ssl.keyStoreType",
    USE_IBM_CIPHER_MAPPINGS,
)
