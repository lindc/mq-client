package com.mqclient.rocketmq

import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * RocketMQ Remoting 客户端（AdminExt / Producer / PullConsumer）共用的 **TLS start
 * 窗口**：`NettyClientConfig.useTLS` 快照与 `NettyRemotingClient` 的 `SslContext`
 * 都在各客户端 `start()` 内部（NettyRemotingClient 此时才创建）定格，而证书走
 * `TlsSystemConfig` 静态字段 —— 进程级全局，必须罩住 start() 才能注入。
 *
 * 生命周期与用法（Producer / Consumer 每次操作临时创建，用完 close）：
 * ```
 * RocketMqTlsStart(config).use { tls ->
 *     val producer = DefaultMQProducer(group, tls.rpcHook).apply {
 *         setNamesrvAddr(config.namesrvAddr); setUseTLS(config.tlsEnabled)
 *     }
 *     tls.start { producer.start() }   // TLS 开启时窗口罩 start()，关闭时直通
 *     try { ... } finally { producer.shutdown() }
 * }
 * ```
 *
 * 证书准备（JKS/P12 → 临时 PEM + 预校验）在**构造时**完成：证书加载错误在 start
 * 前就转成可读文案，杜绝 RocketMQ 对 IOException 只记日志、静默降级明文的行为；
 * 构造失败时对象不会建成、close 不会被调，临时 PEM 在构造内就地清理。临时 PEM
 * 在 [close] 删除（含未加密私钥，用完必须删，不管 start 过没有）。
 *
 * 原 [RocketMqAdminClient] 的窗口逻辑原样搬入（行为不变），消息层的临时
 * Producer / PullConsumer 共用同一份实现与 [TLS_BUILD_LOCK] 互斥窗口。
 *
 * 互斥用的是 [Mutex] 而不是 `synchronized`：等待全局 TLS 窗口的是**可取消的挂起点**。
 * 一条连接在等待期间被断开（用户关对话框 / 切集群）时，它必须能直接退出，而不是把
 * 自己的线程钉在 monitor 的入口队列上（monitor 不可中断，且会串行化 IO 线程）。
 */
internal class RocketMqTlsStart(private val config: RocketMqConfig) : AutoCloseable {

    /** ACL RPCHook（AdminExt / Producer / PullConsumer 构造参数统一取这里）。 */
    val rpcHook: org.apache.rocketmq.remoting.RPCHook? =
        if (config.accessKey.isNotBlank() && config.secretKey.isNotBlank()) {
            org.apache.rocketmq.acl.common.AclClientRPCHook(
                org.apache.rocketmq.acl.common.SessionCredentials(config.accessKey.trim(), config.secretKey.trim()),
            )
        } else {
            null
        }

    /** TLS 注入产生的临时 PEM（构造失败时在 [prepare] 就地清理，正常路径 [close] 删）。 */
    private val tempPemFiles = mutableListOf<Path>()

    /** start() 窗口待用的 TLS 参数；null = TLS 关闭，start 无需窗口（[prepare] 不产路径）。 */
    private val paths: TlsPaths? = prepare()

    /**
     * 在 [TLS_BUILD_LOCK] 互斥窗口内「写证书字段 → start → 还原」。窗口内是纯阻塞
     * 调用（start 无线程切换），持有时间极短；start 完成后客户端实例已持有自己的
     * 快照与 sslContext，还原全局互不影响。
     *
     * 为什么窗口必须互斥：`TlsSystemConfig` 的证书字段是**进程级静态字段**，而
     * `NettyRemotingClient` 在构造时（客户端 `start()` 内部）才把它们读进 `SslContext`。
     * 不互斥时，两条不同证书的连接并发 start 会互相覆盖字段 —— 后 apply 的证书会把
     * 前者的握手一起带走（A 的连接用上了 B 的 CA）。
     *
     * TLS 关闭时 [paths] 为 null，根本不碰全局字段，直接执行（不进锁）。
     */
    suspend fun <T> start(block: () -> T): T {
        val pending = paths ?: return block()
        return TLS_BUILD_LOCK.withLock {
            val snapshot = TlsSnapshot.capture()
            try {
                applyTls(pending)
                block()
            } finally {
                snapshot.restore()
            }
        }
    }

    override fun close() {
        tempPemFiles.forEach(::deleteQuietly)
        tempPemFiles.clear()
    }

    /* ------------------------------ 内部零件（自 RocketMqAdminClient 原样迁入） ------------------------------ */

    /**
     * 预校验 + 转换：Truststore（校验服务器时）与 Keystore（双向认证时）。
     * PEM 证书（如集群分发的 ca.crt）直接透传原文件路径，不复制、不进临时目录；
     * JKS/P12 才走 KeyStore 加载 + 临时 PEM 导出。
     */
    private fun prepare(): TlsPaths? {
        if (!config.tlsEnabled) return null
        try {
            val trustPem =
                if (config.tlsVerifyServer) {
                    pemCertOrNull(config.trustStorePath.trim())
                        ?: writeTempPem(
                            "rmq-trust-",
                            trustCertsToPem(config.trustStorePath.trim(), config.trustStorePassword),
                        ).also { tempPemFiles.add(it) }
                } else {
                    null
                }
            val keyEntry =
                config.keyStorePath.trim().takeIf(String::isNotEmpty)
                    ?.let { keyPath ->
                        if (isPemFile(keyPath)) {
                            throw IllegalStateException(
                                "Keystore 仅支持 JKS/P12 证书库（需含私钥）；PEM 证书请直接用作 Truststore：$keyPath",
                            )
                        }
                        exportKeyEntryToPem(keyPath, config.keyStorePassword)
                    }
            val certPem = keyEntry?.let { writeTempPem("rmq-cert-", it.certChainPem) }?.also { tempPemFiles.add(it) }
            val keyPem = keyEntry?.let { writeTempPem("rmq-key-", it.privateKeyPem) }?.also { tempPemFiles.add(it) }
            return TlsPaths(trustPem, certPem, keyPem)
        } catch (error: Throwable) {
            // 构造失败时对象不会建成，close 不会被调用 —— 临时 PEM 在这里就地清理。
            tempPemFiles.forEach(::deleteQuietly)
            tempPemFiles.clear()
            throw error
        }
    }

    /**
     * 在 start() 窗口内写证书相关的全局状态（离开窗口前由快照还原）。TLS **开关**
     * 不走这里 —— 由调用方在构造时用 `setUseTLS` 显式定死（`ClientConfig.useTLS`
     * 从构造起固化，start 时写全局来不及）；本函数只负责证书字段：
     *  - 证书走 `TlsSystemConfig` 静态字段 —— 客户端类加载后改 System property 无效，
     *    且它们在 start() 内部 `buildSslContext` 时才被读取；
     *  - `tlsTestModeEnable` 必须关（默认 true = 信任所有证书，Truststore 形同虚设）；
     *  - `tlsConfigFile` 指向不存在路径，屏蔽机器全局 `/etc/rocketmq/tls.properties`
     *    在 `buildSslContext` 里反过来覆盖我们的字段。
     */
    private fun applyTls(pending: TlsPaths) {
        org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsTestModeEnable = false
        org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientAuthServer = config.tlsVerifyServer
        org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientTrustCertPath = pending.trustPem?.toString()
        org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientCertPath = pending.certPem?.toString()
        org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientKeyPath = pending.keyPem?.toString()
        org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientKeyPassword = null
        org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsConfigFile = BLOCKED_TLS_CONFIG_FILE
    }

    /** start() 窗口的 TLS 参数：临时 PEM 路径（[prepare] 暂存、[start] 消费）。 */
    private class TlsPaths(val trustPem: Path?, val certPem: Path?, val keyPem: Path?)

    /** `TlsSystemConfig` 证书字段的全局快照：进 start() 窗口前 [capture]，离开时 [restore]。 */
    private class TlsSnapshot(
        private val testMode: Boolean,
        private val authServer: Boolean,
        private val trustCertPath: String?,
        private val certPath: String?,
        private val keyPath: String?,
        private val keyPassword: String?,
        private val configFile: String,
    ) {
        fun restore() {
            org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsTestModeEnable = testMode
            org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientAuthServer = authServer
            org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientTrustCertPath = trustCertPath
            org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientCertPath = certPath
            org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientKeyPath = keyPath
            org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientKeyPassword = keyPassword
            org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsConfigFile = configFile
        }

        companion object {
            fun capture(): TlsSnapshot = TlsSnapshot(
                testMode = org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsTestModeEnable,
                authServer = org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientAuthServer,
                trustCertPath = org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientTrustCertPath,
                certPath = org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientCertPath,
                keyPath = org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientKeyPath,
                keyPassword = org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsClientKeyPassword,
                configFile = org.apache.rocketmq.remoting.netty.TlsSystemConfig.tlsConfigFile,
            )
        }
    }

    companion object {
        /**
         * TLS 全局环境窗口锁：`tls.enable` property 与 `TlsSystemConfig` 静态字段是
         * 进程级全局，「写 → start → 还原」窗口必须互斥，否则两条不同证书的连接并发
         * start 会互相踩（后 apply 的覆盖前者的证书字段）。
         *
         * 所有 [RocketMqTlsStart] 实例（AdminExt / Producer / PullConsumer 各一份）
         * 共用这一把 —— 它是 `companion` 的，不是实例字段。
         */
        private val TLS_BUILD_LOCK = Mutex()

        /** start() 窗口内用于屏蔽机器全局 tls.properties 的路径（随机后缀，保证不存在）。 */
        private val BLOCKED_TLS_CONFIG_FILE: String =
            System.getProperty("java.io.tmpdir") + "/mqclient-no-tls-config-" + UUID.randomUUID() + ".properties"
    }
}
