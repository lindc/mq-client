package com.mqclient.ibmmq.diag

import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test

/**
 * IBM MQ SSL 连接的最小基准测试（无 UI、无项目依赖，只隔离验证 TLS 链路）。
 *
 * 运行方式（所有参数可用系统属性覆盖，默认值就是当前测试环境）：
 *
 * ```
 * ./gradlew :ibm-mq-service:jvmTest --tests "com.mqclient.ibmmq.diag.IbmMQSslDiagTest"
 * ```
 *
 * 可覆盖的系统属性（gradle 传法：`-Dmqdiag.xxx=...`）：
 *  - mqdiag.enabled          —— **必须为 true 才会真正连远端**；默认跳过（避免日常
 *                                测试误触网络）
 *  - mqdiag.host / mqdiag.port / mqdiag.channel / mqdiag.qmgr / mqdiag.user / mqdiag.pass
 *  - mqdiag.keyStore / mqdiag.keyStorePassword / mqdiag.trustStore / mqdiag.trustStorePassword
 *  - mqdiag.cipher          —— 留空 = 自动协商（不设 SSL_CIPHER_SUITE 属性）
 *  - mqdiag.mappings        —— com.ibm.mq.cfg.useIBMCipherMappings，默认 false（OpenJDK 标准 TLS_* 名）
 *  - mqdiag.customContext   —— true 时显式构建 SSLContext（KeyManager/TrustManager 直读 p12）并
 *                              SSLContext.setDefault(...)，绕开「JSSE 系统属性在默认上下文缓存后
 *                              才设置 → 不生效」的时序坑（主代码 2393 的头号嫌疑）
 *  - mqdiag.debug           —— true 时在最早期设 javax.net.debug=ssl:handshake
 *
 * 每次连接前打印全部生效配置；成功打印 CONNECTED OK 并断开，失败打印 MQ 原因码与消息。
 */
class IbmMQSslDiagTest {

    private fun prop(key: String, default: String): String = System.getProperty("mqdiag.$key") ?: default

    private fun props(): Map<String, String> = mapOf(
        "host" to prop("host", "www.chillbear.top"),
        "port" to prop("port", "11208"),
        "channel" to prop("channel", "DEV.ADMIN.SVRCONN"),
        "qmgr" to prop("qmgr", "QM2"),
        "user" to prop("user", "mqadmin"),
        "pass" to prop("pass", "Cs63yDmpaLe8ybXC"),
        "keyStore" to prop("keyStore", "C:\\Users\\206630672\\Documents\\data\\mq\\client.p12"),
        "keyStorePassword" to prop("keyStorePassword", "MqTlsDev2026"),
        "trustStore" to prop("trustStore", "C:\\Users\\206630672\\Documents\\data\\mq\\trust.p12"),
        "trustStorePassword" to prop("trustStorePassword", "MqTlsDev2026"),
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
        if (!prop("enabled", "false").toBoolean()) {
            println("IbmMQSslDiagTest skipped — set -Dmqdiag.enabled=true to run against a real queue manager")
            return
        }
        val cfg = props()

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
