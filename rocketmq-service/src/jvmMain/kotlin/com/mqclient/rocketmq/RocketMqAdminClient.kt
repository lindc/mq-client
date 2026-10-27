package com.mqclient.rocketmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.rocketmq.client.exception.MQClientException
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats
import org.apache.rocketmq.common.TopicConfig
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo
import org.apache.rocketmq.remoting.protocol.subscription.SubscriptionGroupConfig
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
 * TLS 注入与 ACL RPCHook 统一走 [RocketMqTlsStart]（AdminExt / Producer /
 * PullConsumer 共用同一份「构造时备证书 + start 窗口注入 + close 删临时 PEM」
 * 实现，详见其 KDoc）：开关在构造时用 `setUseTLS` 显式定死，证书字段的写入罩在
 * start() 的互斥窗口内。
 *
 * 线程纪律：DefaultMQAdminExt 的调用是阻塞 IO，全部经 [ioCall] 跑在
 * Dispatchers.IO。DefaultMQAdminExt 自身线程安全（Remoting 层有连接复用），
 * 无需额外串行化。
 */
class RocketMqAdminClient(private val config: RocketMqConfig) : AutoCloseable {

    /** TLS 证书准备 / start 窗口 / ACL RPCHook 的统一持有者。 */
    private val tls = RocketMqTlsStart(config)

    private val admin: DefaultMQAdminExt =
        DefaultMQAdminExt(tls.rpcHook, config.connectionTimeout * 1_000L).apply {
            setNamesrvAddr(config.namesrvAddr.trim())
            // 开关显式传入：ClientConfig.useTLS 在构造时从 TlsSystemConfig.tlsEnable /
            // tls.enable property 固化初始值，MQClientInstance.start() 里又原样拷贝进
            // NettyClientConfig —— 任何「start 时再写全局」都来不及，必须在构造时就定死。
            setUseTLS(config.tlsEnabled)
        }

    /**
     * 是否**尝试过** `admin.start()`。
     *
     * 不能只在 start 成功后才置位：`DefaultMQAdminExt.start()` 先建 Netty 客户端
     * （含定时任务线程池、NameServer 通道），再去拉路由 —— 拉路由 / 握手失败时
     * start 抛异常，那些线程却已经起来了。以「成功」为守卫就会跳过 `shutdown()`，
     * 线程留到进程退出（[RocketMqConnectionManager.createVerifiedAdmin] 的失败路径
     * 会走到这里，泄漏是稳定的）。
     *
     * `@Volatile`：置位在 start 的协程线程，读在 close()，可能跨线程。
     */
    @Volatile
    private var startAttempted = false

    /** start + examineBrokerClusterInfo：NameServer 连通性 / ACL 一次验完。 */
    suspend fun startAndVerify() {
        // 先置位再 start：start 抛异常时 close() 仍必须走 shutdown()。
        startAttempted = true
        ioCall { tls.start { admin.start() } }
        ioCall { admin.examineBrokerClusterInfo() }
    }

    /** 集群信息：broker 地址表（brokerName → brokerId → addr）+ clusterAddrTable。 */
    suspend fun fetchClusterInfo(): ClusterInfo = ioCall { admin.examineBrokerClusterInfo() }

    /** 全量 topic 列表（含系统 topic：%RETRY% 组、%DLQ%、TBW102 等）。 */
    suspend fun fetchTopicList(): Set<String> = ioCall { admin.fetchAllTopicList().topicList }

    /**
     * 消费组列表：**broker 订阅组配置为主源**（getAllSubscriptionGroup，覆盖所有
     * 经订阅注册过的组 —— %RETRY% topic 只在组触发过重试时才存在，纯 %RETRY% 推导
     * 会漏组），∪ %RETRY%<group> 主题推导兜底（broker 配置被清但 retry topic 还在
     * 的过渡态）。逐 broker 容错：单个 broker 失败只少该节的数据（ACL / 掉线是
     * 预期内现象），全部失败时退化为 %RETRY% 推导。
     */
    suspend fun fetchConsumerGroupList(): List<String> =
        ioCall {
            val brokerAddrs = admin.examineBrokerClusterInfo().brokerAddrTable.values
                .flatMap { it.brokerAddrs.values }
                .toSet()
            val fromBrokers = brokerAddrs.flatMap { addr ->
                runCatching { admin.getAllSubscriptionGroup(addr, config.connectionTimeout * 1_000L) }
                    .map { it.subscriptionGroupTable.keys }
                    .onFailure { rocketMqLog.warn("subscription group list failed: broker=" + addr + " error=" + it.message) }
                    .getOrDefault(emptySet())
            }
            val fromRetryTopics = admin.fetchAllTopicList().topicList
                .filter { it.startsWith(RETRY_TOPIC_PREFIX) }
                .map { it.removePrefix(RETRY_TOPIC_PREFIX) }
            (fromBrokers + fromRetryTopics).distinct()
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

    /**
     * 订阅组配置查询：**不依赖 %RETRY% 路由**（直接读 broker 本地配置），组从未
     * 消费也能拿到 —— 属性对话框局部降级的基础数据源。
     */
    suspend fun fetchGroupConfig(groupName: String): SubscriptionGroupConfig {
        val addr = ioCall { admin.examineBrokerClusterInfo() }.brokerAddrTable.values
            .flatMap { it.brokerAddrs.values }
            .firstOrNull()
            ?: throw IllegalStateException("集群中没有可用 Broker，无法查询订阅组配置")
        return ioCall { admin.examineSubscriptionGroupConfig(addr, groupName) }
    }

    /**
     * 创建消费组：`createAndUpdateSubscriptionGroupConfig` 写入全部 broker 地址
     * （update 语义幂等，已存在等同改配置）。开启 ACL 时走既有 RPCHook。
     */
    suspend fun createSubscriptionGroup(groupName: String, orderly: Boolean) {
        val cluster = ioCall { admin.examineBrokerClusterInfo() }
        val brokerAddrs = cluster.brokerAddrTable.values
            .flatMap { it.brokerAddrs.values }
            .toSet()
        ioCall {
            brokerAddrs.forEach { addr ->
                val config = org.apache.rocketmq.remoting.protocol.subscription.SubscriptionGroupConfig()
                config.groupName = groupName
                config.isConsumeEnable = true
                config.isConsumeMessageOrderly = orderly
                admin.createAndUpdateSubscriptionGroupConfig(addr, config)
            }
        }
    }

    /**
     * 删除消费组：`deleteSubscriptionGroup` 写入全部 broker 地址（移除订阅组配置；
     * 组的 %RETRY%/%DLQ% topic 与位点数据不受影响——那属于 topic 生命周期）。
     */
    suspend fun deleteSubscriptionGroup(groupName: String) {
        val cluster = ioCall { admin.examineBrokerClusterInfo() }
        val brokerAddrs = cluster.brokerAddrTable.values
            .flatMap { it.brokerAddrs.values }
            .toSet()
        ioCall {
            brokerAddrs.forEach { addr ->
                admin.deleteSubscriptionGroup(addr, groupName)
            }
        }
    }

    /**
     * 创建 / 更新主题：`createAndUpdateTopicConfig` 写入全部 broker 地址（路由经
     * broker 心跳注册到 NameServer，有秒级延迟）。perm 位：2 读 / 4 写 / 6 读写。
     */
    suspend fun createTopic(topicName: String, readQueues: Int, writeQueues: Int, perm: Int) {
        val cluster = ioCall { admin.examineBrokerClusterInfo() }
        val brokerAddrs = cluster.brokerAddrTable.values
            .flatMap { it.brokerAddrs.values }
            .toSet()
        ioCall {
            brokerAddrs.forEach { addr ->
                val config = org.apache.rocketmq.common.TopicConfig()
                config.topicName = topicName
                config.readQueueNums = readQueues
                config.writeQueueNums = writeQueues
                config.perm = perm
                admin.createAndUpdateTopicConfig(addr, config)
            }
        }
    }

    override fun close() {
        if (startAttempted) {
            // 对「start 抛异常」的实例调 shutdown() 是安全的：内部按句柄判空。
            runCatching { admin.shutdown() }
            startAttempted = false
        }
        // 临时 PEM 含未加密私钥，用完必须删（不管 start 过没有）。
        tls.close()
    }

    /** 所有管理调用的统一出口：IO 线程 + 错误映射。
     *
     * `suspend` 是因为 TLS start 窗口要挂在 [Mutex] 上等锁（可取消）—— `tls.start`
     * 本身是挂起调用，非挂起的 lambdas 依然可以传进来。
     */
    private suspend fun <T> ioCall(block: suspend () -> T): T =
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

    companion object {
        /** %RETRY% 系统主题前缀：消费组列表由它推导（<prefix><group>）。 */
        const val RETRY_TOPIC_PREFIX = "%RETRY%"
    }
}
