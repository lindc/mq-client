package com.mqclient.ibmmq.diag

import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import com.mqclient.testing.assumeLive

/**
 * IBM MQ SSL 连接的最小基准测试（无 UI、无项目依赖，只隔离验证 TLS 链路）。
 *
 * 运行方式（**没有默认凭据**，参数全部显式传入）：
 *
 * ```
 * ./gradlew :ibm-mq-service:jvmTest --tests "com.mqclient.ibmmq.diag.IbmMQSslDiagTest" \
 *   -Dmqdiag.enabled=true -Dmqdiag.host=... -Dmqdiag.qmgr=... \
 *   -Dmqdiag.user=... -Dmqdiag.pass=... \
 *   -Dmqdiag.trustStore=... -Dmqdiag.trustStorePassword=...
 * ```
 *
 * 可覆盖的系统属性（gradle 传法：`-Dmqdiag.xxx=...`）：
 *  - mqdiag.enabled          —— **必须为 true 才会真正连远端**；默认跳过（避免日常
 *                                测试误触网络）
 *  - mqdiag.host / mqdiag.port / mqdiag.channel / mqdiag.qmgr / mqdiag.user / mqdiag.pass
 *  - mqdiag.keyStore / mqdiag.keyStorePassword / mqdiag.trustStore / mqdiag.trustStorePassword
 *  - mqdiag.cipher          —— 留空 = 自动协商（不设 SSL_CIPHER_SUITE 属性）
 *  - mqdiag.mappings        —— com.ibm.mq.cfg.useIBMCipherMappings，默认 false（OpenJDK 标准 TLS_* 名）
 *  - mqdiag.customContext   —— true 时显式构建 SSLContext 并 `SSLContext.setDefault(...)`，
 *                              用于验证「默认上下文」这条假设。**仅诊断用**：主题路径
 *                              （IbmMqTls）不会替换进程默认上下文，那是全局副作用。
 *  - mqdiag.debug           —— true 时在最早期设 javax.net.debug=ssl:handshake
 *
 * 每次连接前打印全部生效配置；成功打印 CONNECTED OK 并断开，失败打印 MQ 原因码与消息。
 */
class IbmMQSslDiagTest {

    private fun prop(key: String, default: String): String = System.getProperty("mqdiag.$key") ?: default

    /**
     * 全部参数都**没有默认凭据**：口令 / 证书路径一律由 `-Dmqdiag.*` 传入。
     *
     * 这里曾经把开发环境的真实口令与证书路径写成默认值并提交进 git —— 与
     * CLAUDE.md 里「`connect.txt` 含明文 ACL 密钥，禁止入 git」是同一条纪律，
     * 只是当时漏了这个文件。默认值留空后，缺参数会在连接阶段明确失败（而不是
     * 在某人机器上「刚好能连」），也不会随着仓库分发给所有人。
     */
    private fun props(): Map<String, String> = mapOf(
        "host" to prop("host", ""),
        "port" to prop("port", "1414"),
        "channel" to prop("channel", "SYSTEM.DEF.SVRCONN"),
        "qmgr" to prop("qmgr", ""),
        "user" to prop("user", ""),
        "pass" to prop("pass", ""),
        "keyStore" to prop("keyStore", ""),
        "keyStorePassword" to prop("keyStorePassword", ""),
        "trustStore" to prop("trustStore", ""),
        "trustStorePassword" to prop("trustStorePassword", ""),
        "cipher" to prop("cipher", ""),
        "mappings" to prop("mappings", "false"),
        "customContext" to prop("customContext", "false"),
    )

    /** 显式从 p12 构建 SSLContext（不走 JSSE 系统属性）。 */
    private fun buildContext(keyStorePath: String, keyStorePassword: String, trustStorePath: String, trustStorePassword: String): SSLContext {
        val password = keyStorePassword.toCharArray()

        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(keyStorePath).use { keyStore.load(it, password) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)

        val trustStore = KeyStore.getInstance("PKCS12")
        FileInputStream(trustStorePath).use { trustStore.load(it, trustStorePassword.toCharArray()) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, tmf.trustManagers, null)
        return ctx
    }

    @Test
    fun connect() {
        // 默认不连远端 = SKIP（不是 PASSED）：否则 CI 报告里这个「诊断测试」
        // 永远是绿的，看不出它其实一次都没跑。
        assumeLive(
            "IbmMQSslDiagTest skipped — set -Dmqdiag.enabled=true to run against a real queue manager",
            prop("enabled", "false").toBoolean(),
        )
        val cfg = props()
        // 参数缺失要「说清楚缺哪一个」，而不是拿空串去连接、最后报一个 MQRC 2393。
        val missing = listOf("host", "qmgr", "user").filter { cfg[it].isNullOrBlank() }
        check(missing.isEmpty()) {
            "mqdiag.enabled=true 时必须提供：${missing.joinToString { "-Dmqdiag.$it" }}（口令与证书路径同样由系统属性传入，不入库）"
        }

        // javax.net.debug 必须在任何 JSSE 类加载前设置；gradle 每次跑测试都是新 JVM，
        // 这里是本测试的第一个动作，来得及。
        if (prop("debug", "false").toBoolean()) {
            System.setProperty("javax.net.debug", "ssl:handshake")
        }

        println("=========== IBM MQ SSL DIAG ===========")
        cfg.forEach { (k, v) ->
            val value = if (k == "pass" || k == "keyStorePassword" || k == "trustStorePassword") "***" else v
            println("  $k = $value")
        }
        println("  java.version = ${System.getProperty("java.version")}")
        println("  java.vendor  = ${System.getProperty("java.vendor")}")

        // 1. cipher 映射：OpenJDK 用标准 TLS_* 名，必须关掉 IBM 映射（2397 的来源）。
        System.setProperty("com.ibm.mq.cfg.useIBMCipherMappings", cfg["mappings"]!!)
        println("  com.ibm.mq.cfg.useIBMCipherMappings = ${cfg["mappings"]}")

        // 2. JSSE 系统属性路径（模拟主代码 IbmMQService.testConnection 的做法）。
        System.setProperty("javax.net.ssl.keyStore", cfg["keyStore"]!!)
        System.setProperty("javax.net.ssl.keyStorePassword", cfg["keyStorePassword"]!!)
        System.setProperty("javax.net.ssl.keyStoreType", "PKCS12")
        System.setProperty("javax.net.ssl.trustStore", cfg["trustStore"]!!)
        System.setProperty("javax.net.ssl.trustStorePassword", cfg["trustStorePassword"]!!)
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")

        // 3. 显式上下文路径（可选）：先验证 keystore 能否加载，再整体替换默认上下文 ——
        //    此后 MQ 客户端拿到的 SSLContext 一定带客户端证书，不受「默认上下文已被
        //    缓存」影响。
        if (cfg["customContext"] == "true") {
            val ctx = buildContext(
                cfg["keyStore"]!!, cfg["keyStorePassword"]!!,
                cfg["trustStore"]!!, cfg["trustStorePassword"]!!,
            )
            SSLContext.setDefault(ctx)
            println("  [customContext] default SSLContext replaced (KM=${ctx.provider})")
        }

        val properties = java.util.Hashtable<String, Any>()
        properties[MQConstants.HOST_NAME_PROPERTY] = cfg["host"]!!
        properties[MQConstants.PORT_PROPERTY] = cfg["port"]!!.toInt()
        properties[MQConstants.CHANNEL_PROPERTY] = cfg["channel"]!!
        properties[MQConstants.USER_ID_PROPERTY] = cfg["user"]!!
        properties[MQConstants.PASSWORD_PROPERTY] = cfg["pass"]!!
        val cipher = cfg["cipher"]!!.trim()
        if (cipher.isNotEmpty()) {
            properties[MQConstants.SSL_CIPHER_SUITE_PROPERTY] = cipher
        }
        println("  SSL_CIPHER_SUITE = ${if (cipher.isEmpty()) "(auto-negotiate)" else cipher}")

        println("----------- connecting -----------")
        try {
            val manager = MQQueueManager(cfg["qmgr"]!!, properties)
            try {
                println(">>> CONNECTED OK to ${cfg["qmgr"]} @ ${cfg["host"]}:${cfg["port"]}")
            } finally {
                manager.disconnect()
                println(">>> disconnected cleanly")
            }
        } catch (e: Exception) {
            println(">>> CONNECT FAILED")
            println("    exception : ${e.javaClass.name}")
            println("    message   : ${e.message}")
            println("    cause     : ${e.cause}")
            e.printStackTrace()
        }
        println("=========== DIAG END ===========")
    }
}
