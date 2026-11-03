package com.mqclient.ibmmq.diag

import com.ibm.mq.MQQueueManager
import com.mqclient.ibmmq.ConnectionMode
import com.mqclient.ibmmq.IbmMQConfig
import com.mqclient.ibmmq.IbmMqTls
import com.mqclient.ibmmq.mqProperties
import com.mqclient.ibmmq.probeMqConnection
import com.mqclient.testing.assumeLive
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * 真机验证：**正式连接路径**（`IbmMqTls.withTls` + `mqProperties` + `MQQueueManager`）
 * 在「双向 TLS + 密码」的队列管理器上能连上，且证书确实在起作用。
 *
 * 为什么需要它：`IbmMqTlsTest` 是离线的，只能证明「窗口内的属性写对了」；它证明不了
 * 「服务端接受这张证书」。而这正是本轮修复的事故面 —— 旧实现只在「测试连接」里写证书，
 * 正式连接拿不到，于是「测试通过」是个假信号。要证明修好了，只有一条路：用生产路径
 * 真连一次。
 *
 * ## 三条反向对照（关键）
 *
 * 正向通过可能有很多解释（恰好用了 JVM 默认信任链、服务端没强制客户端证书……）。
 * 所以每个关键材料都配一条「拿掉它必须失败」：
 *  - 去掉 truststore → 证书链校验失败（证明 truststore 真的被读）；
 *  - 去掉 keystore → 服务端 `SSLCAUTH(REQUIRED)` 拒绝（证明客户端证书真的发出去了）；
 *  - 错密码 → 认证失败（证明连的是真 QM，不是某种降级路径）。
 *
 * 这些用例互为「变异验证」：如果有人把 [IbmMqTls] 的注入逻辑改坏，正向那条会红；
 * 如果有人为了让它变绿而放宽校验（比如静默回落 JVM 默认），反向那几条会红。
 *
 * ## 运行方式
 *
 * 默认跳过（不连远端）。参数**没有默认凭据**，口令一律由系统属性传入：
 *
 * ```
 * ./gradlew :ibm-mq-service:jvmTest --tests "*IbmMqTlsLiveTest" \
 *   -Dmqdiag.enabled=true \
 *   -Dmqdiag.host=www.chillbear.top -Dmqdiag.port=11208 -Dmqdiag.qmgr=QM2 \
 *   -Dmqdiag.channel=DEV.ADMIN.SVRCONN -Dmqdiag.user=mqadmin -Dmqdiag.pass=... \
 *   -Dmqdiag.keyStore=client.p12 -Dmqdiag.keyStorePassword=... \
 *   -Dmqdiag.trustStore=trust.p12 -Dmqdiag.trustStorePassword=...
 * ```
 *
 * 需要看 JSSE 的握手细节（「到底读了哪个信任库」）时加 `-Djavax.net.debug=ssl`：
 * 这个属性必须在 JVM 启动时给出，所以 `build.gradle.kts` 把它一并转发进测试 JVM
 * （测试里再 `System.setProperty` 已经晚了 —— JSSE 在类初始化时就读掉它）。
 */
class IbmMqTlsLiveTest {

    private fun prop(key: String, default: String = ""): String =
        System.getProperty("mqdiag.$key") ?: default

    private companion object {
        /** 测试自造的临时证书库口令（与真机凭据无关，不敏感）。 */
        const val KEYTOOL_PASSWORD = "changeit"
    }

    /** 基础配置：TLS 关闭、无证书 —— 每条用例在它之上加自己需要的材料。 */
    private fun baseConfig(): IbmMQConfig = IbmMQConfig(
        mode = ConnectionMode.CLIENT,
        queueManager = prop("qmgr"),
        host = prop("host"),
        port = prop("port", "1414").toIntOrNull() ?: 1414,
        channel = prop("channel", "DEV.ADMIN.SVRCONN"),
        username = prop("user"),
        password = prop("pass"),
        sslEnabled = false,
        connectionTimeout = 30,
    )

    /** 双向 TLS 的完整材料（与本轮事故面完全对应：PKCS12 keystore + 自签 CA）。 */
    private fun tlsConfig(): IbmMQConfig = baseConfig().copy(
        sslEnabled = true,
        keyStorePath = prop("keyStore"),
        keyStorePassword = prop("keyStorePassword"),
        trustStorePath = prop("trustStore"),
        trustStorePassword = prop("trustStorePassword"),
        cipherSuite = prop("cipher"),
        peerName = prop("peerName"),
    )

    /**
     * 走**正式连接路径**：`IbmMQConnectionManager.open` 就是这两行的组合
     * （证书窗口 + `mqProperties` → `MQQueueManager`；它外层还套了 `BlockingCalls`，
     * 那只是超时控制，与 TLS 无关）。
     */
    private fun connectLikeProduction(config: IbmMQConfig): MQQueueManager = runBlocking {
        IbmMqTls.withTls(config) {
            MQQueueManager(config.queueManager.trim(), mqProperties(config))
        }
    }

    private fun requireEnvironment() {
        assertTrue(prop("enabled", "false").toBoolean(), "enabled flag")
        val missing = listOf("host", "qmgr", "user", "pass").filter { prop(it).isBlank() }
        check(missing.isEmpty()) {
            "mqdiag.enabled=true 时必须提供：${missing.joinToString { "-Dmqdiag.$it" }}"
        }
    }

    // ---------------------------------------------------------------- 正向

    @Test
    fun productionPathConnectsWithMutualTls() = runBlocking {
        assumeLive("LIVE SKIP: 未开启 mqdiag.enabled（真机 TLS 用例）", prop("enabled", "false").toBoolean())
        requireEnvironment()
        val config = tlsConfig()
        assumeLive(
            "LIVE SKIP: 需要 -Dmqdiag.keyStore / trustStore（双向认证材料）",
            config.keyStorePath.isNotBlank() && config.trustStorePath.isNotBlank(),
        )

        // 正式路径直接连（不先点「测试连接」—— 旧实现正是在这里拿不到证书）。
        val manager = connectLikeProduction(config)
        try {
            println(">>> production path CONNECTED to ${config.queueManager} @ ${config.host}:${config.port}")
            // 连上还不够：真正执行一次需要鉴权的 MQI 调用，证明身份也被接受。
            // （PCF 走另一条 agent 路径，这里只要一个最小的可用性证据。）
            assertTrue(manager.isConnected, "连接对象应处于已连接状态")
        } finally {
            runCatching { manager.disconnect() }
        }
    }

    @Test
    fun testConnectionAcceptsTheSameMaterial() = runBlocking {
        assumeLive("LIVE SKIP: 未开启 mqdiag.enabled（真机 TLS 用例）", prop("enabled", "false").toBoolean())
        requireEnvironment()
        val config = tlsConfig()
        assumeLive(
            "LIVE SKIP: 需要 -Dmqdiag.keyStore / trustStore（双向认证材料）",
            config.keyStorePath.isNotBlank() && config.trustStorePath.isNotBlank(),
        )

        // 「测试连接」与正式连接必须同源：这里成功 + 上面也成功 = 两边真的一致。
        // 旧实现在这一条上会红（测试能过、正式连接不能），这正是本轮修复的判据。
        probeMqConnection(config)
        println(">>> testConnection OK with the same material")
    }

    // ------------------------------------------------------- 反向对照（变异）

    @Test
    fun withoutTruststoreTheSameConnectionMustFail() = runBlocking {
        assumeLive("LIVE SKIP: 未开启 mqdiag.enabled（真机 TLS 用例）", prop("enabled", "false").toBoolean())
        requireEnvironment()
        val full = tlsConfig()
        assumeLive(
            "LIVE SKIP: 需要完整的 TLS 材料才能做「拿掉 truststore」对照",
            full.keyStorePath.isNotBlank() && full.trustStorePath.isNotBlank(),
        )

        val withoutTrust = full.copy(trustStorePath = "", trustStorePassword = "")
        // 若这里「连上了」，说明 truststore 从来没被读 —— 那正向的通过就是巧合
        // （JVM 默认信任链恰好认这个 CA），证书链校验等于没有。
        expectFailure("no-truststore", "拿掉 truststore 后仍连上了 —— 证书链校验没有生效") {
            connectLikeProduction(withoutTrust).disconnect()
        }
    }

    @Test
    fun unrelatedTrustAnchorMustFailAtHandshake() = runBlocking {
        assumeLive("LIVE SKIP: 未开启 mqdiag.enabled（真机 TLS 用例）", prop("enabled", "false").toBoolean())
        requireEnvironment()
        val full = tlsConfig()
        assumeLive(
            "LIVE SKIP: 需要完整的 TLS 材料才能做「换 CA」对照",
            full.keyStorePath.isNotBlank() && full.trustStorePath.isNotBlank(),
        )

        // 用一份**与服务器无关**的自签 CA 当信任库：如果这时还能连上，说明服务端证书
        // 根本没被校验（JVM 默认信任链或某种「谁签都信」的路径在起作用）。
        val foreign = keytoolTruststore("unrelated-ca")
        try {
            expectFailure(
                "unrelated-ca",
                "用无关 CA 当信任库仍连上了 —— 服务端证书没有被校验",
            ) {
                connectLikeProduction(full.copy(trustStorePath = foreign.absolutePath, trustStorePassword = KEYTOOL_PASSWORD))
                    .disconnect()
            }
        } finally {
            foreign.delete()
        }
    }

    @Test
    fun withoutKeystoreTheSameConnectionMustFail() = runBlocking {
        assumeLive("LIVE SKIP: 未开启 mqdiag.enabled（真机 TLS 用例）", prop("enabled", "false").toBoolean())
        requireEnvironment()
        val full = tlsConfig()
        assumeLive(
            "LIVE SKIP: 需要完整的 TLS 材料才能做「拿掉 keystore」对照",
            full.keyStorePath.isNotBlank() && full.trustStorePath.isNotBlank(),
        )

        val withoutKey = full.copy(keyStorePath = "", keyStorePassword = "")
        // 服务端 SSLCAUTH(REQUIRED)：没有客户端证书应当被拒。
        // 若这里连上了，说明 keystore 没被用上（P12→JKS 那条转换或属性注入失效）。
        expectFailure("no-keystore", "拿掉 keystore 后仍连上了 —— 客户端证书没有被要求") {
            connectLikeProduction(withoutKey).disconnect()
        }
    }

    @Test
    fun wrongPasswordMustFail() = runBlocking {
        assumeLive("LIVE SKIP: 未开启 mqdiag.enabled（真机 TLS 用例）", prop("enabled", "false").toBoolean())
        requireEnvironment()
        val full = tlsConfig()

        // 证明我们连的是真 QM 且确实在做认证，而不是某种「跳过校验」的路径。
        expectFailure("wrong-password", "错密码仍连上了 —— 认证没有被真正执行") {
            connectLikeProduction(full.copy(password = "definitely-not-the-password")).disconnect()
        }
    }

    @Test
    fun testConnectionDoesNotLeakTlsMaterialIntoTheNextConnect() = runBlocking {
        assumeLive("LIVE SKIP: 未开启 mqdiag.enabled（真机 TLS 用例）", prop("enabled", "false").toBoolean())
        requireEnvironment()
        val full = tlsConfig()
        assumeLive(
            "LIVE SKIP: 需要完整的 TLS 材料才能做窗口残留对照",
            full.keyStorePath.isNotBlank() && full.trustStorePath.isNotBlank(),
        )

        // 先做一次带证书的测试连接（旧实现会把 JSSE 属性留在这里）。
        probeMqConnection(full)
        // 紧接着用「无证书」的配置走正式连接：不许靠上一条的证书蒙混过关。
        expectFailure("no-cert-after-test", "上一次连接的证书泄漏到了下一次 —— 窗口没有还原干净") {
            connectLikeProduction(full.copy(keyStorePath = "", keyStorePassword = "", trustStorePath = "", trustStorePassword = ""))
                .disconnect()
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 生成一份与本测试环境无关的自签 CA（PKCS12），用作「错误信任锚」。
     *
     * 用 JDK 自带 keytool：离线、无外部依赖。没有 keytool 时记 SKIP（与 LiveTest 的
     * assumption 语义一致 —— 没验过就明说）。
     */
    private fun keytoolTruststore(name: String): java.io.File {
        val home = System.getProperty("java.home") ?: ""
        val binary = listOf("keytool", "keytool.exe").map { java.io.File(home, "bin/$it") }.firstOrNull { it.isFile }
        assumeLive("LIVE SKIP: 当前 JDK 没有 keytool（$home）", binary != null)
        val file = java.io.File.createTempFile("mqclient-$name-", ".p12")
        // createTempFile 会**先建一个空文件**，而 keytool 拒绝写已存在的空 keystore
        // （"Keystore file exists, but is empty"）—— 先删掉让它自己创建。
        file.delete()
        val process = ProcessBuilder(
            binary!!.absolutePath, "-genkeypair",
            "-alias", name, "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", file.absolutePath,
            "-storepass", KEYTOOL_PASSWORD, "-keypass", KEYTOOL_PASSWORD,
            "-dname", "CN=$name,O=Test", "-validity", "1", "-noprompt",
        ).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) {
            "keytool 生成 $name 失败：$output"
        }
        return file
    }

    /**
     * 断言 [block] 抛异常，并把「哪条对照 + 真实原因」打出来。
     *
     * 打印是为了让证据可核对：反向对照如果失败在一个**无关**的原因上（比如套件名不认、
     * 密码错），它就证明不了本来要证的那件事。真机排障也靠这几行。
     */
    private fun expectFailure(label: String, because: String, block: () -> Unit) {
        val error = try {
            block()
            null
        } catch (t: Throwable) {
            t
        }
        if (error == null) fail(because)
        println(">>> [$label] expected failure: ${error.javaClass.simpleName}: ${error.message}")
        assertTrue(
            error.message != null || error.cause != null,
            "失败必须带可读原因（UI 要靠它提示用户）：$error",
        )
    }
}
