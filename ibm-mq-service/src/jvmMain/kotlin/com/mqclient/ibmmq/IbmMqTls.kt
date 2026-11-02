package com.mqclient.ibmmq

import com.mqclient.core.ServiceException
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.KeyStoreException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * IBM MQ 客户端 TLS 证书注入的**唯一入口**。
 *
 * ## 为什么证书必须走「全局属性 + 窗口」这条路
 *
 * MQ 基础 Java 客户端（`com.ibm.mq.allclient`）**没有**「每条连接一个 truststore」的
 * 连接属性。它的 TLS 材料只有三个来源，全部是进程级的：
 *
 *  1. `javax.net.ssl.trustStore*` —— 客户端自己建 `SSLContext` 时用默认 TrustManager，
 *     后者按这几个属性解析信任库（`RemoteTCPConnection.getNonFipsSocketFactory`
 *     里 `SSLContext.init(keyManagers, null, null)`）；
 *  2. `javax.net.ssl.keyStore` / `keyStorePassword` —— 同一个方法读它们建 KeyManager，
 *     **并且写死按 `JKS` 加载**（客户端源码里的 `KeyStore.getInstance("JKS")`）；
 *  3. `MQEnvironment.sslCertStores` / `sslSocketFactory` —— 也是静态字段，且换成
 *     socket factory 会一并绕开主机名校验。
 *
 * 所以「按连接指定证书」只能做成 [withTls] 这个窗口：快照 6 个 JSSE 属性 → 写入本连接
 * 的值 → 在窗口内完成连接建立 → 还原。这与 RocketMQ 侧
 * (`RocketMqTlsStart`) 的做法同构 —— 那边的证书字段同样是进程级静态字段。
 *
 * ## 两个必须守住的细节
 *
 *  - **窗口互斥**：属性是进程级的，两条不同证书的连接并发建连会互相覆盖（A 的
 *    truststore 被 B 用上）。共用一把 [WINDOW] 锁，窗口内只有一次建连。
 *  - **留空的字段要显式清掉**：只写非空值的话，先测 A（有自签 truststore）再测 B
 *    （留空 = 用 JVM 默认）会拿到 A 的信任库 —— 测试结论是假的。快照 + 还原天然
 *    解决了跨调用的残留，窗口内则用 [apply] 把 6 个属性**全部**写成确定值。
 *
 * ## 为何不再 `SSLContext.setDefault(...)`
 *
 * 旧实现除了写 JSSE 属性，还额外 `SSLContext.setDefault(自定义上下文)`，而且从不还原。
 * 两个问题：MQ 客户端根本不读默认上下文（见上面第 1 条），所以对 MQ 没用；而进程里
 * 任何其它 TLS 使用者（AI 面板的 Ktor 客户端）会突然改用 MQ 那个 truststore ——
 * 测一次 SSL 连接就能把别处的信任链换掉。这是纯粹的风险，已移除。
 *
 * ## 客户端库的 JKS 限制
 *
 * MQ 读 `javax.net.ssl.keyStore` 时写死 `JKS`，而本项目的证书选择器收 `.p12` / `.pfx`。
 * 直接把 p12 路径塞进该属性只会让客户端「没有客户端证书」（握手在没有
 * `SSLCAUTH(REQUIRED)` 时可能还能过，双向认证时必失败，且错误码不指向证书）。
 * 因此 [prepare] 会把 PKCS12 keystore **转成同口令的临时 JKS**（POSIX 600，窗口结束
 * 即删），JKS 则原样使用。信任库不需要这层转换：JSSE 认 `trustStoreType`。
 */
internal object IbmMqTls {

    /** 建连窗口锁（进程级属性，只能一条连接一条连接地建）。 */
    private val WINDOW = Mutex()

    /** 客户端会读的全部 JSSE 属性；快照 / 还原 / 显式清空都按这张表。 */
    private val JSSE_PROPERTIES = listOf(
        "javax.net.ssl.trustStore",
        "javax.net.ssl.trustStorePassword",
        "javax.net.ssl.trustStoreType",
        "javax.net.ssl.keyStore",
        "javax.net.ssl.keyStorePassword",
        "javax.net.ssl.keyStoreType",
    )

    /**
     * 在与 [config] 的 TLS 设置一致的全局状态下执行 [block]（建连）。
     *
     * 未启用 SSL（或 BINDING 模式）时**不改任何全局状态**，直接执行 —— BINDING 是同机
     * JNI，没有 TLS 通道。
     *
     * 证书先在窗口外预校验 / 转换（[prepare]）：文件不存在、口令错、格式不对、keystore
     * 里没有私钥，都在这里变成可读文案 —— 否则要等客户端抛一个只说「连接失败」的 MQRC。
     *
     * 窗口内的 `block` 通常是阻塞调用（`MQQueueManager` 构造）。超时放弃时那条构造可能
     * 在窗口关闭后才完成，此刻属性已还原 —— 那条迟到的连接由调用方的 `onAbandoned`
     * 关闭，不参与业务。
     */
    suspend fun <T> withTls(config: IbmMQConfig, block: suspend () -> T): T {
        if (!config.usesTls) return block()
        return prepare(config).use { prepared ->
            WINDOW.withLock {
                val snapshot = JSSE_PROPERTIES.associateWith { System.getProperty(it) }
                try {
                    apply(prepared)
                    block()
                } finally {
                    snapshot.forEach { (key, value) ->
                        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
                    }
                }
            }
        }
    }

    /* ------------------------------- 证书准备 ------------------------------- */

    /** 一次建连实际要写进全局属性的值（keystore 可能是转换出来的临时 JKS）。 */
    private class Prepared(
        val trustStorePath: String?,
        val trustStorePassword: String?,
        val trustStoreType: String?,
        val keyStorePath: String?,
        val keyStorePassword: String?,
        val keyStoreType: String?,
        /** 为 PKCS12 转换出来的临时 JKS；窗口结束即删（含私钥，不能留在临时目录）。 */
        private val temporaryKeyStore: File?,
    ) : AutoCloseable {
        override fun close() {
            temporaryKeyStore?.let { file ->
                runCatching { Files.deleteIfExists(file.toPath()) }
            }
        }
    }

    /**
     * 校验并按需转换证书材料。失败一律抛带文件路径与原因的 [IllegalStateException]
     * （文案直接进 UI，与 RocketMQ 侧的证书错误同一种风格）。
     */
    private fun prepare(config: IbmMQConfig): Prepared {
        val trustStorePath = config.trustStorePath.trim().takeIf(String::isNotEmpty)
        val trustStorePassword = config.trustStorePassword
        val trustStoreType = trustStorePath?.let { typeOf(it) }
        // 预加载一次：口令错 / 文件坏在这里就报，而不是等握手时变成 2393。
        trustStorePath?.let { path ->
            loadStore(path, trustStoreType!!, trustStorePassword)
        }

        val keyStorePath = config.keyStorePath.trim().takeIf(String::isNotEmpty)
        val keyStorePassword = config.keyStorePassword
        val declaredKeyStoreType = keyStorePath?.let { typeOf(it) }
        val sourceKeyStore = keyStorePath?.let { path ->
            loadStore(path, declaredKeyStoreType!!, keyStorePassword).also { store ->
                requirePrivateKeyEntry(path, store)
            }
        }

        // 客户端写死 JKS：PKCS12 必须转换；JKS 原样用。
        val temporary = if (sourceKeyStore != null && declaredKeyStoreType == "PKCS12") {
            convertToTemporaryJks(sourceKeyStore, keyStorePassword)
        } else {
            null
        }

        return Prepared(
            trustStorePath = trustStorePath,
            trustStorePassword = trustStorePassword.takeIf { trustStorePath != null },
            trustStoreType = trustStoreType,
            keyStorePath = temporary?.absolutePath ?: keyStorePath,
            keyStorePassword = keyStorePassword.takeIf { keyStorePath != null },
            keyStoreType = if (keyStorePath != null) "JKS" else null,
            temporaryKeyStore = temporary,
        )
    }

    /**
     * 把证书库写成全局属性。
     *
     * 六个属性**全部**写确定值：留空的写成空串而不是「跳过」，否则窗口内的这一次建连
     * 会沿用上一条连接留下的值（快照只挡跨调用，挡不住同一窗口里的历史残留）。
     * 空串对 JSSE 的语义就是「未配置」（回落 JVM 默认信任库 / 不发客户端证书）。
     */
    private fun apply(prepared: Prepared) {
        // OpenJDK 上必须关掉 IBM 风格的 cipher 映射（`SSL_*` 名在 JDK 里不存在，报 2397）。
        // 模块初始化时已设过一次；这里再设是幂等的，覆盖「属性被外部清掉」的情况。
        System.setProperty(USE_IBM_CIPHER_MAPPINGS, "false")
        System.setProperty("javax.net.ssl.trustStore", prepared.trustStorePath.orEmpty())
        System.setProperty("javax.net.ssl.trustStorePassword", prepared.trustStorePassword.orEmpty())
        System.setProperty("javax.net.ssl.trustStoreType", prepared.trustStoreType.orEmpty())
        System.setProperty("javax.net.ssl.keyStore", prepared.keyStorePath.orEmpty())
        System.setProperty("javax.net.ssl.keyStorePassword", prepared.keyStorePassword.orEmpty())
        System.setProperty("javax.net.ssl.keyStoreType", prepared.keyStoreType.orEmpty())
    }

    /* --------------------------------- 工具 --------------------------------- */

    /** 按扩展名判定证书库类型（与 Kafka / RocketMQ 表单同一规则）。 */
    internal fun typeOf(path: String): String {
        val lower = path.lowercase()
        return if (lower.endsWith(".p12") || lower.endsWith(".pfx")) "PKCS12" else "JKS"
    }

    private fun loadStore(path: String, type: String, password: String): KeyStore {
        val file = File(path)
        if (!file.isFile) {
            throw ServiceException(
                "ibm_mq_err_cert_file_missing",
                args = listOf(path),
                fallbackMessage = "Certificate file not found: %1\$s",
            )
        }
        val store = try {
            KeyStore.getInstance(type)
        } catch (e: KeyStoreException) {
            throw ServiceException(
                "ibm_mq_err_keystore_type_unsupported",
                args = listOf(type, path),
                fallbackMessage = "Unsupported keystore type %1\$s: %2\$s", cause = e,
            )
        }
        try {
            FileInputStream(file).use { store.load(it, password.toCharArray()) }
        } catch (e: Exception) {
            // 口令错 / 内容与扩展名不符 / 文件被删都会落到这里。PKCS12 把「口令错」也报成
            // IOException，所以不要按异常类型分类，统一给「检查文件格式与口令」这句话。
            throw ServiceException(
                "ibm_mq_err_keystore_load_failed",
                args = listOf(path, e.message ?: e::class.simpleName ?: "unknown"),
                fallbackMessage = "Failed to load the keystore (check format and password): %1\$s (%2\$s)", cause = e,
            )
        }
        return store
    }

    /** 双向认证需要含私钥的库：只有证书条目的库连上去不会有客户端证书。 */
    private fun requirePrivateKeyEntry(path: String, store: KeyStore) {
        val hasKey = store.aliases().toList().any { alias ->
            runCatching { store.isKeyEntry(alias) }.getOrDefault(false)
        }
        if (!hasKey) {
            throw ServiceException(
                "ibm_mq_err_keystore_no_private_key",
                args = listOf(path),
                fallbackMessage = "No private key entry in the keystore (mutual TLS requires a keystore with a private key): %1\$s",
            )
        }
    }

    /**
     * PKCS12 → 同口令的临时 JKS（客户端写死 `KeyStore.getInstance("JKS")`）。
     *
     * 私钥条目按**库口令**解出（绝大多数客户端证书库 store 口令 = key 口令；不同的话
     * 这里会失败并给出可读原因，而不是让握手静默失去客户端证书）。
     */
    private fun convertToTemporaryJks(source: KeyStore, password: String): File {
        val target = KeyStore.getInstance("JKS").apply { load(null, null) }
        val protection = KeyStore.PasswordProtection(password.toCharArray())
        try {
            source.aliases().toList().forEach { alias ->
                when {
                    source.isKeyEntry(alias) -> {
                        val entry = source.getEntry(alias, protection) as KeyStore.PrivateKeyEntry
                        target.setKeyEntry(alias, entry.privateKey, password.toCharArray(), entry.certificateChain)
                    }
                    source.isCertificateEntry(alias) ->
                        source.getCertificate(alias)?.let { target.setCertificateEntry(alias, it) }
                }
            }
        } catch (e: Exception) {
            throw ServiceException(
                "ibm_mq_err_key_export_failed",
                args = listOf(e.message ?: e::class.simpleName ?: "unknown"),
                fallbackMessage = "Failed to export the private key (the key password usually matches the keystore password): %1\$s", cause = e,
            )
        }
        val file = Files.createTempFile("mqclient-ibmmq-keystore-", ".jks").toFile()
        try {
            runCatching {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
            file.outputStream().use { target.store(it, password.toCharArray()) }
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(file.toPath()) }
            throw ServiceException(
                "ibm_mq_err_temp_keystore_write_failed",
                args = listOf(e.message ?: e::class.simpleName ?: "unknown"),
                fallbackMessage = "Failed to write the temporary keystore: %1\$s", cause = e,
            )
        }
        return file
    }
}

/**
 * 该配置会不会真正走 TLS 通道。
 *
 * BINDING 是同机 JNI（没有 TLS），SSL 关闭时更是无从谈起 —— 两者都不该碰全局
 * JSSE 属性（碰了也只是给别的连接制造干扰）。
 */
internal val IbmMQConfig.usesTls: Boolean
    get() = mode == ConnectionMode.CLIENT && sslEnabled
