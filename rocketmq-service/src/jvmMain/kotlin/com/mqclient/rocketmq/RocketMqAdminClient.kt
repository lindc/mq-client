package com.mqclient.rocketmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.rocketmq.acl.common.AclClientRPCHook
import org.apache.rocketmq.acl.common.SessionCredentials
import org.apache.rocketmq.client.exception.MQClientException
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo
import org.apache.rocketmq.remoting.protocol.body.ConsumerConnection
import org.apache.rocketmq.remoting.exception.RemotingConnectException
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt

/**
 * RocketMQ 管理 API 客户端（Remoting，`DefaultMQAdminExt` 封装）：连接验证与
 * 扫描的唯一 IO 出口。
 *
 * 生命周期：构造**不启动**；[startAndVerify] = start + examineBrokerClusterInfo
 * 验证（NameServer / ACL 一次验完）；[close] = shutdown。错误统一经 [mapError]
 * 转成人类可读文案（"无法连接 NameServer" / "NameServer 连接超时" / "ACL 认证
 * 失败"…），UI 直出。
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

    private val admin = DefaultMQAdminExt(rpcHook, config.connectionTimeout * 1_000L).apply {
        setNamesrvAddr(config.namesrvAddr.trim())
    }

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

    override fun close() {
        if (!started) return
        runCatching { admin.shutdown() }
        started = false
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
        } else {
            IllegalStateException(e.message ?: e::class.simpleName ?: "unknown", e)
        }
    }

    companion object {
        /** %RETRY% 系统主题前缀：消费组列表由它推导（<prefix><group>）。 */
        const val RETRY_TOPIC_PREFIX = "%RETRY%"
    }
}
