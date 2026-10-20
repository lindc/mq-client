package com.mqclient.rocketmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.rocketmq.acl.common.AclClientRPCHook
import org.apache.rocketmq.acl.common.SessionCredentials
import org.apache.rocketmq.client.exception.MQClientException
import org.apache.rocketmq.remoting.netty.TlsSystemConfig
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats
import org.apache.rocketmq.common.TopicConfig
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo
import org.apache.rocketmq.remoting.protocol.body.ConsumerConnection
import org.apache.rocketmq.remoting.exception.RemotingConnectException
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt
import java.nio.file.Path
import java.util.UUID

/**
 * RocketMQ 管理 API 客户端（Remoting，`DefaultMQAdminExt` 封装）：连接验证与
 * 扫描的唯一 IO 出口。
 *
 * 生命周期：构造**不启动**；[startAndVerify] = start + examineBrokerClusterInfo
 * 验证（NameServer / ACL 一次验完）；[close] = shutdown。错误统一经 [mapError]
 * 转成人类可读文案（"无法连接 NameServer" / "NameServer 连接超时" / "ACL 认证
 * 失败"…），UI 直出。
 *
 * TLS 注入（`config.tlsEnabled` 时）：`useTLS` 快照与 `SslContext` 都在
 * DefaultMQAdminExt **构造时**定格到实例 —— 先把 JKS/P12 证书库经 [TlsPemExport]
 * 转成临时 PEM（预校验在这里完成：证书加载错误在构造前就转成可读文案，杜绝
 * RocketMQ 对 IOException 只记日志、静默降级明文的行为），再在
 * [TLS_BUILD_LOCK] 互斥窗口内「写全局 TLS 状态 → 构造 → 还原」，此后该实例与
 * 全局无关。临时 PEM 在 [close] 删除。
 *
 * 线程纪律：DefaultMQAdminExt 的调用是阻塞 IO，全部经 [ioCall] 跑在
 * Dispatchers.IO。DefaultMQAdminExt 自身线程安全（Remoting 层有连接复用），
 * 无需额外串行化。
 */
class RocketMqAdminClient(private val config: RocketMqConfig) : AutoCloseable {

    private val rpcHook: org.apache.rocketmq.remoting.RPCHook? =
        if (config.accessKey.isNotBlank() && config.secretKey.isNotBlank()) {
            AclClientRPCHook(SessionCredentials(config.accessKey.trim(), config.secretKey.trim()))
        } else {
            null
        }

    /** TLS 注入产生的临时 PEM（构造失败时在 [buildAdmin] 就地清理，正常路径 [close] 删）。 */
    private val tempPemFiles = mutableListOf<Path>()

    private val admin: DefaultMQAdminExt = buildAdmin()

    private var started = false

    /** start + examineBrokerClusterInfo：NameServer 连通性 / ACL 一次验完。 */
    suspend fun startAndVerify() {
        ioCall { admin.start() }
        started = true
        ioCall { admin.examineBrokerClusterInfo() }
    }

    /** 集群信息：broker 地址表（brokerName → brokerId → addr）+ clusterAddrTable。 */
    suspend fun fetchClusterInfo(): ClusterInfo = ioCall { admin.examineBrokerClusterInfo() }

    /** 全量 topic 列表（含系统 topic：%RETRY% 组、%DLQ%、TBW102 等）。 */
    suspend fun fetchTopicList(): Set<String> = ioCall { admin.fetchAllTopicList().topicList }

    /** 消费组列表：从 %RETRY%<group> 系统主题推导（fetchAllTopicList 一次调用覆盖）。 */
    suspend fun fetchConsumerGroupList(): List<String> =
        ioCall {
            admin.fetchAllTopicList().topicList
                .filter { it.startsWith(RETRY_TOPIC_PREFIX) }
                .map { it.removePrefix(RETRY_TOPIC_PREFIX) }
                .distinct()
        }

    /** 消费组消费统计：每个 MessageQueue 的 broker / consumer offset（diff = Lag）。 */
    suspend fun fetchConsumerProgress(group: String): ConsumeStats = ioCall { admin.examineConsumeStats(group) }

    /** 消费组在线成员（best-effort）：组不在线 / 不存在返回 null（MQClientException）。 */
    suspend fun fetchConsumerConnection(group: String): ConsumerConnection? =
        ioCall {
            try {
                admin.examineConsumerConnectionInfo(group)
            } catch (e: MQClientException) {
                null // CONSUMER_NOT_ONLINE 等预期失败：成员数按 null 展示。
            }
        }

    /** Topic 路由（阶段 2 拓扑用；阶段 1 仅预留）。 */
    suspend fun fetchTopicRoute(topic: String) = ioCall { admin.examineTopicRouteInfo(topic) }

    /**
     * Topic 配置（attributes 含 message.type 等 5.x 扩展属性）：经 Master 地址现查
     * （broker 未显式设置时返回的 attributes 为空 —— 调用方回落默认值）。
     */
    suspend fun fetchTopicConfig(topic: String): TopicConfig? {
        val cluster = ioCall { admin.examineBrokerClusterInfo() }
        val addr = cluster.brokerAddrTable.values
            .flatMap { it.brokerAddrs.values }
            .firstOrNull()
            ?: throw IllegalStateException("集群中没有可用 Broker，无法查询 Topic 配置")
        return ioCall { admin.examineTopicConfig(addr, topic) }
    }

    /**
     * 删除主题：先清全部 broker 上的路由与元数据，再清 NameServer（与 mqadmin
     * deleteTopic 行为一致，两步缺一不可 —— 只清 NameServer 会导致路由残留）。
     */
    suspend fun deleteTopic(topic: String) {
        val cluster = ioCall { admin.examineBrokerClusterInfo() }
        val brokerAddrs = cluster.brokerAddrTable.values
            .flatMap { it.brokerAddrs.values }
            .toSet()
        val nameSrvAddrs = config.namesrvAddr.split(";", ",", "；", "，")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        ioCall {
            admin.deleteTopicInBroker(brokerAddrs, topic)
            admin.deleteTopicInNameServer(nameSrvAddrs, topic)
        }
    }

    override fun close() {
        if (started) {
            runCatching { admin.shutdown() }
            started = false
        }
        // 临时 PEM 含未加密私钥，用完必须删（不管 start 过没有）。
        tempPemFiles.forEach(::deleteQuietly)
        tempPemFiles.clear()
    }

    /** 所有管理调用的统一出口：IO 线程 + 错误映射。 */
    private suspend fun <T> ioCall(block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw mapError(e)
            }
        }

    /** RocketMQ 异常 → 人类可读文案（UI 直出）。 */
    private fun mapError(e: Throwable): Exception {
        val text = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return if (e is RemotingConnectException) {
            IllegalStateException(
                "无法连接 NameServer " + config.namesrvAddr + "：检查地址与端口（默认 9876）", e,
            )
        } else if (e is RemotingTimeoutException) {
            IllegalStateException(
                "NameServer 连接超时（" + config.connectionTimeout + "s）：" + config.namesrvAddr, e,
            )
        } else if (
            text.contains("ACL", ignoreCase = true) ||
            text.contains("NoPermission", ignoreCase = true) ||
            text.contains("no permission", ignoreCase = true)
        ) {
            IllegalStateException("ACL 认证失败：检查 AccessKey / SecretKey", e)
        } else if (
            text.contains("ssl", ignoreCase = true) ||
            text.contains("certificate", ignoreCase = true) ||
            text.contains("证书")
        ) {
            IllegalStateException(
                "TLS/SSL 握手失败：检查证书文件、Truststore 配置与集群 TLS 模式（broker 需开启 tls.enable）", e,
            )
        } else {
            IllegalStateException(e.message ?: e::class.simpleName ?: "unknown", e)
        }
    }

    /* ------------------------------ TLS 注入 ------------------------------ */

    /** 不带 TLS 的普通构造（`config.tlsEnabled = false` 的唯一路径，不碰全局状态）。 */
    private fun plainAdmin(): DefaultMQAdminExt =
        DefaultMQAdminExt(rpcHook, config.connectionTimeout * 1_000L).apply {
            setNamesrvAddr(config.namesrvAddr.trim())
        }

    /**
     * 构造 AdminExt：TLS 关闭走 [plainAdmin]；开启时先把证书库转临时 PEM（预校验
     * 一并完成，加载错误在这里直接抛可读文案），再进互斥窗口构造。
     */
    private fun buildAdmin(): DefaultMQAdminExt {
        if (!config.tlsEnabled) return plainAdmin()

        try {
            // 预校验 + 转换：Truststore（校验服务器时）与 Keystore（双向认证时）。
            val trustPem =
                if (config.tlsVerifyServer) {
                    writeTempPem("rmq-trust-", trustCertsToPem(config.trustStorePath.trim(), config.trustStorePassword))
                        .also { tempPemFiles.add(it) }
                } else {
                    null
                }
            val keyEntry =
                config.keyStorePath.trim().takeIf(String::isNotEmpty)
                    ?.let { exportKeyEntryToPem(it, config.keyStorePassword) }
            val certPem = keyEntry?.let { writeTempPem("rmq-cert-", it.certChainPem) }?.also { tempPemFiles.add(it) }
            val keyPem = keyEntry?.let { writeTempPem("rmq-key-", it.privateKeyPem) }?.also { tempPemFiles.add(it) }

            synchronized(TLS_BUILD_LOCK) {
                val snapshot = TlsSnapshot.capture()
                try {
                    applyTls(trustPem, certPem, keyPem)
                    return plainAdmin()
                } finally {
                    snapshot.restore()
                }
            }
        } catch (error: Throwable) {
            // 构造失败时对象不会建成，close 不会被调用 —— 临时 PEM 在这里就地清理。
            tempPemFiles.forEach(::deleteQuietly)
            tempPemFiles.clear()
            throw error
        }
    }

    /**
     * 在构造窗口内写全局 TLS 状态（离开窗口前由快照还原）：
     *  - 开关 `tls.enable` 走 System property —— `NettyClientConfig.useTLS` 构造时直接读它；
     *  - 证书走 `TlsSystemConfig` 静态字段 —— 客户端类加载后改 System property 无效；
     *  - `tlsTestModeEnable` 必须关（默认 true = 信任所有证书，Truststore 形同虚设）；
     *  - `tlsConfigFile` 指向不存在路径，屏蔽机器全局 `/etc/rocketmq/tls.properties`
     *    在 `buildSslContext` 里反过来覆盖我们的字段。
     */
    private fun applyTls(trustPem: Path?, certPem: Path?, keyPem: Path?) {
        System.setProperty(TlsSystemConfig.TLS_ENABLE, "true")
        TlsSystemConfig.tlsTestModeEnable = false
        TlsSystemConfig.tlsClientAuthServer = config.tlsVerifyServer
        TlsSystemConfig.tlsClientTrustCertPath = trustPem?.toString()
        TlsSystemConfig.tlsClientCertPath = certPem?.toString()
        TlsSystemConfig.tlsClientKeyPath = keyPem?.toString()
        TlsSystemConfig.tlsClientKeyPassword = null
        TlsSystemConfig.tlsConfigFile = BLOCKED_TLS_CONFIG_FILE
    }

    /** `TlsSystemConfig` 全局状态的快照：进构造窗口前 [capture]，离开时 [restore]。 */
    private class TlsSnapshot(
        private val tlsEnableProp: String?,
        private val testMode: Boolean,
        private val authServer: Boolean,
        private val trustCertPath: String?,
        private val certPath: String?,
        private val keyPath: String?,
        private val keyPassword: String?,
        private val configFile: String,
    ) {
        fun restore() {
            if (tlsEnableProp == null) {
                System.clearProperty(TlsSystemConfig.TLS_ENABLE)
            } else {
                System.setProperty(TlsSystemConfig.TLS_ENABLE, tlsEnableProp)
            }
            TlsSystemConfig.tlsTestModeEnable = testMode
            TlsSystemConfig.tlsClientAuthServer = authServer
            TlsSystemConfig.tlsClientTrustCertPath = trustCertPath
            TlsSystemConfig.tlsClientCertPath = certPath
            TlsSystemConfig.tlsClientKeyPath = keyPath
            TlsSystemConfig.tlsClientKeyPassword = keyPassword
            TlsSystemConfig.tlsConfigFile = configFile
        }

        companion object {
            fun capture(): TlsSnapshot = TlsSnapshot(
                tlsEnableProp = System.getProperty(TlsSystemConfig.TLS_ENABLE),
                testMode = TlsSystemConfig.tlsTestModeEnable,
                authServer = TlsSystemConfig.tlsClientAuthServer,
                trustCertPath = TlsSystemConfig.tlsClientTrustCertPath,
                certPath = TlsSystemConfig.tlsClientCertPath,
                keyPath = TlsSystemConfig.tlsClientKeyPath,
                keyPassword = TlsSystemConfig.tlsClientKeyPassword,
                configFile = TlsSystemConfig.tlsConfigFile,
            )
        }
    }

    companion object {
        /** %RETRY% 系统主题前缀：消费组列表由它推导（<prefix><group>）。 */
        const val RETRY_TOPIC_PREFIX = "%RETRY%"

        /**
         * TLS 全局环境构造锁：`tls.enable` property 与 `TlsSystemConfig` 静态字段是
         * 进程级全局，「写 → 构造 → 还原」窗口必须互斥，否则两条不同证书的连接并发
         * 构造会互相踩。窗口内不做 IO，持有时间极短。
         */
        private val TLS_BUILD_LOCK = Any()

        /** 构造窗口内用于屏蔽机器全局 tls.properties 的路径（随机后缀，保证不存在）。 */
        private val BLOCKED_TLS_CONFIG_FILE: String =
            System.getProperty("java.io.tmpdir") + "/mqclient-no-tls-config-" + UUID.randomUUID() + ".properties"
    }
}
