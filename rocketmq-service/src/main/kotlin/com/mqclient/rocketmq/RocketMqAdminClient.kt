package com.mqclient.rocketmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.ServiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.apache.rocketmq.client.exception.MQClientException
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats
import org.apache.rocketmq.common.TopicConfig
import org.apache.rocketmq.client.exception.MQBrokerException
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo
import org.apache.rocketmq.remoting.protocol.body.ConsumeStatsList
import org.apache.rocketmq.remoting.protocol.subscription.SubscriptionGroupConfig
import org.apache.rocketmq.remoting.protocol.body.ConsumerConnection
import org.apache.rocketmq.remoting.exception.RemotingConnectException
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt
import java.util.concurrent.atomic.AtomicInteger

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

    init {
        // 服务层兜底：即便有调用方绕过 main() 直接 new 客户端（诊断工具 / 未来的 CLI），
        // 也要在 DefaultMQAdminExt 首次触到日志类之前把落点设好。幂等，代价是一次
        // volatile 读。属性必须早于任何 RocketMQ 类加载，所以放在类体最前面。
        RocketMqLogging.install()
    }

    /** TLS 证书准备 / start 窗口 / ACL RPCHook 的统一持有者。 */
    private val tls = RocketMqTlsStart(config)

    private val ADMIN_OP_TIMEOUT_MS = 5_000L

    private val admin: DefaultMQAdminExt =
        DefaultMQAdminExt(tls.rpcHook, ADMIN_OP_TIMEOUT_MS).apply {
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

    /** 在途管理调用数：诊断「请求堆积 / 僵尸调用没被取消释放」。 */
    private val inFlight = AtomicInteger(0)

    /**
     * AdminExt 生命周期快照（排查 4）。`DefaultMQAdminExt` 没有公开的 `isStarted()`，
     * 这里用本类自己维护的 [startAttempted]（只有 [startAndVerify] / [close] 两处写入）
     * + 实例身份 + 在途数，确认每次查询用的是同一个、已 start 且从未被 shutdown 的实例。
     */
    fun lifecycleState(): String =
        "admin=#${System.identityHashCode(admin)} started=$startAttempted inFlight=${inFlight.get()}"

    /** start + examineBrokerClusterInfo：NameServer 连通性 / ACL 一次验完。 */
    suspend fun startAndVerify() {
        // 先置位再 start：start 抛异常时 close() 仍必须走 shutdown()。
        startAttempted = true
        withContext(Dispatchers.IO) { tls.start { admin.start() } }
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

    /**
     * 消费组消费统计：每个 MessageQueue 的 broker / consumer offset（diff = Lag）。
     *
     * **注意它依赖 `%RETRY%<group>` 的路由**：`examineConsumeStats(group)` 是
     * `examineConsumeStats(null, group, null)` 的简写，内部先按
     * `[%RETRY%group]` 查 NameServer 路由，查不到直接 CODE 17。组从未消费过、
     * 重试队列被删、或 POP 模式都会命中。绕不过去的路径见
     * [fetchConsumeStatsInBroker]。
     */
    suspend fun fetchConsumerProgress(group: String): ConsumeStats = ioCall { admin.examineConsumeStats(group) }

    /**
     * **broker 级**的消费统计：直接读该 broker 的 ConsumerOffsetManager，返回它上面
     * **所有组**的已提交位点 —— 完全不经过 NameServer 路由查询，因此不会被 CODE 17 挡住。
     *
     * 代价是报文大（一台 broker 的所有组），所以只能当兜底用：组级查询成功时绝不调用。
     * 返回结构是 `List<Map<groupId, List<ConsumeStats>>>`，按组取用即可。
     */
    suspend fun fetchConsumeStatsInBroker(brokerAddr: String, timeoutMs: Long): ConsumeStatsList? =
        ioCall { admin.fetchConsumeStatsInBroker(brokerAddr, false, timeoutMs) }

    /**
     * 消费组在线成员（best-effort）：组不在线 / 不存在返回 null。
     *
     * **两种异常形态都要接住**：同一件事（组没有在线消费者）在不同路径上会以
     * `MQClientException` 或 `MQBrokerException` 回来，实测后者更常见：
     *
     * ```
     * MQBrokerException: CODE: 206 DESC: the consumer group[CG_PROGRESS_DEMO] not online
     *                   BROKER: www.chillbear.top:11207
     * ```
     *
     * 只 catch 前者会让这条**完全正常**的状态漏进 [ioCall] 的 mapError，被包成
     * IllegalStateException 后由调用方记成 WARN + 完整栈 —— 用户日志里就出现一条
     * 看着像故障的 20 行堆栈，而它只是「这个组没在跑消费者」。
     *
     * 只吞 206 / 17 这两种「组不在线 / 组没有重试队列」；权限、超时等**照常抛出**
     * —— 那些才是需要留痕的问题。
     */
    suspend fun fetchConsumerConnection(group: String): ConsumerConnection? =
        ioCall {
            try {
                admin.examineConsumerConnectionInfo(group)
            } catch (e: MQClientException) {
                null // CONSUMER_NOT_ONLINE 等预期失败：成员数按 null 展示。
            } catch (e: MQBrokerException) {
                if (e.responseCode == RC_NO_CONSUME_STATS || e.responseCode == RC_TOPIC_NO_ROUTE) {
                    null // 组不在线 / 重试队列无路由：同样是预期状态，不是故障。
                } else {
                    throw e
                }
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
            ?: throw ServiceException(
                "rocketmq_err_no_broker_topic_config",
                fallbackMessage = "No available broker in the cluster to query the topic config",
            )
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
     *
     * **返回 null = 该组在 broker 上确实不存在**（订阅组配置没有这条记录）。
     * 这一点必须与「查询失败」分开：broker 的 `examineSubscriptionGroupConfig` 对
     * 不存在的组返回 null（不是抛异常），而 `ioCall` 的泛型上界非空，会把它变成一句
     * `ioCall(...) must not be null` 的 Kotlin 内部断言 —— 那句话既不是中文也没说
     * 清楚发生了什么，最后原样显示在详情页上。见 [fetchGroupConfigOrNull] 的注释。
     *
     * 真正的查询失败（连接 / ACL / 超时）仍然抛异常，由调用方区分。
     */
    suspend fun fetchGroupConfigOrNull(groupName: String): SubscriptionGroupConfig? {
        val addr = ioCall { admin.examineBrokerClusterInfo() }.brokerAddrTable.values
            .flatMap { it.brokerAddrs.values }
            .firstOrNull()
            ?: throw ServiceException(
                "rocketmq_err_no_broker_group_config",
                fallbackMessage = "No available broker in the cluster to query the subscription group config",
            )
        // 不走 ioCall：它要求非空返回；这里 null 是**有意义的业务结果**（组不存在）。
        // 同样用 runInterruptible：对话框关闭/超时取消时，必须能真正打断底层阻塞调用。
        return runInterruptible(Dispatchers.IO) {
            try {
                admin.examineSubscriptionGroupConfig(addr, groupName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw mapError(e)
            }
        }
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

    /** 所有管理调用的统一出口：IO 线程 + 错误映射 + 在途计数。
     *
     * 这里**没有互斥锁**：`DefaultMQAdminExt` 自身线程安全，进度查询路径不经过任何
     * Mutex（全局 `TLS_BUILD_LOCK` 只在 `start()` 窗口用，且 `withLock` 的 finally 会释放）。
     */
    private suspend fun <T> ioCall(block: () -> T): T {
        // runInterruptible（而非 withContext）：RocketMQ 的 examineXxx 是阻塞 Java 调用，
        // 协程取消时 withContext 只标记取消、底层线程会继续跑到底（connectionTimeout 才返回），
        // 于是关闭/切换对话框后这些「僵尸」阻塞调用仍占着 Dispatchers.IO 线程，快速连开连关
        // 就会把 IO 池与共享 AdminExt 占满，下一次查询排队超时——正是「第二次/快速切换必卡死」的根因。
        // runInterruptible 在取消时会对底层线程 Thread.interrupt()，Netty 读会抛 InterruptedException
        // 提前返回，阻塞调用真正被中断，不再泄漏线程。
        val n = inFlight.incrementAndGet()
        rocketMqLog.debug("rmq call begin (#$n): ${lifecycleState()}")
        try {
            return runInterruptible(Dispatchers.IO) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    throw mapError(e)
                }
            }
        } finally {
            inFlight.decrementAndGet()
            rocketMqLog.debug("rmq call end (#$n): ${lifecycleState()}")
        }
    }
    /** RocketMQ 异常 → 人类可读文案（UI 直出）。 */
    private fun mapError(e: Throwable): Exception {
        val text = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return if (e is RemotingConnectException) {
            ServiceException(
                "rocketmq_err_nameserver_connect",
                args = listOf(config.namesrvAddr),
                fallbackMessage = "Cannot connect to NameServer %1\$s: check the address and port (default 9876)", cause = e,
            )
        } else if (e is RemotingTimeoutException) {
            ServiceException(
                "rocketmq_err_nameserver_timeout",
                args = listOf(config.connectionTimeout, config.namesrvAddr),
                fallbackMessage = "NameServer connection timed out (%1\$ss): %2\$s", cause = e,
            )
        } else if (
            text.contains("ACL", ignoreCase = true) ||
            text.contains("NoPermission", ignoreCase = true) ||
            text.contains("no permission", ignoreCase = true)
        ) {
            ServiceException(
                "rocketmq_err_acl_failed",
                fallbackMessage = "ACL authentication failed: check the AccessKey / SecretKey", cause = e,
            )
        } else if (
            text.contains("ssl", ignoreCase = true) ||
            text.contains("certificate", ignoreCase = true) ||
            text.contains("证书")
        ) {
            ServiceException(
                "rocketmq_err_tls_handshake",
                fallbackMessage = "TLS/SSL handshake failed: check the certificate files, Truststore config and cluster TLS mode (the broker needs tls.enable)", cause = e,
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
