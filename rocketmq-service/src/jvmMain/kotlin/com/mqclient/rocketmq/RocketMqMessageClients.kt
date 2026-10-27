package com.mqclient.rocketmq

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer
import org.apache.rocketmq.client.producer.DefaultMQProducer

/**
 * 一条 RocketMQ 连接的**共享消息客户端**：一个 PullConsumer + 一个 Producer，懒建、
 * 复用、直到连接断开或程序退出。
 *
 * ## 为什么不再每次操作都新建
 *
 * 旧实现每读一页 `DefaultMQPullConsumer(...)`、每发一条 `DefaultMQProducer(...)`，
 * 用完 shutdown。两者都很重：
 *
 *  - 构造 → `start()` 会向 NameServer 拉路由、向 broker 注册**消费者组 / 生产者组**
 *    并起心跳线程、Netty 客户端（含连接池）；
 *  - 旧实现组名带 `UUID.randomUUID()`，**每次都是新组** —— broker 侧的组表会稳定地
 *    每条读取/发送多出一行，客户端侧也多一轮注册与心跳。反复几十次之后，NameServer
 *    / broker 的组元数据与客户端线程数一起膨胀，这正是「逐次变慢」的来源。
 *
 * 现在每组一条连接固定一个组名（见 [PULL_GROUP] / [PRODUCE_GROUP]），只建一次、
 * 一直复用。`disconnect` / `connection.deleted` / 模块关闭时统一
 * [shutdown]（见 [RocketMqConnectionManager.closeQuietly]）。
 *
 * ## 线程语义
 *
 * 两个客户端都**不保证线程安全**（Remoting 层内部有并发保护，但 `pull` /
 * `fetchSubscribeMessageQueues` 这类带客户端状态的操作并发调用的行为没有承诺）。
 * 所以统一用 [access] 串行化 —— 与 RabbitMQ 的 channel 同一处理方式。
 *
 * ## TLS
 *
 * RocketMQ 的证书走 `TlsSystemConfig` **进程级静态字段**，而客户端只在 `start()`
 * 内部读取它们（详见 [RocketMqTlsStart]）。所以 [RocketMqTlsStart] 必须**与客户端
 * 同生共死**：start 窗口用它注入证书，临时 PEM 文件也要等到 close 才能删。
 * 因此本类持有 `tls` 的整个生命周期，而不是 `use { }` 包一次 start 就丢。
 */
class RocketMqMessageClients(private val config: RocketMqConfig) {

    /** 与客户端同生共死的 TLS 持有者（含临时 PEM，close 时删除）。 */
    private val tls = RocketMqTlsStart(config)

    /**
     * 串行化客户端访问。**调用方整段持有**（从 fetchSubscribeMessageQueues 到 pull
     * 结束），只锁创建等于没锁。
     */
    val access = Mutex()

    @Volatile
    private var pullConsumer: DefaultMQPullConsumer? = null

    @Volatile
    private var producer: DefaultMQProducer? = null

    /** 创建 / 取用两个客户端的串行点（首次创建只发生一次）。 */
    private val createLock = Mutex()

    /** 共享 PullConsumer；首次调用时创建并 start。 */
    suspend fun pullConsumer(): DefaultMQPullConsumer {
        pullConsumer?.let { return it }
        return createLock.withLock {
            pullConsumer?.let { return@withLock it }
            val created = withContext(Dispatchers.IO) {
                DefaultMQPullConsumer(PULL_GROUP, tls.rpcHook).apply {
                    setNamesrvAddr(config.namesrvAddr.trim())
                    // 开关显式传入：ClientConfig.useTLS 在构造时固化，start 时再写全局来不及。
                    setUseTLS(config.tlsEnabled)
                }
            }
            // start 必须在 TLS 窗口内（证书是全局静态字段，见 RocketMqTlsStart）。
            withContext(Dispatchers.IO) { tls.start { created.start() } }
            pullConsumer = created
            rocketMqLog.debug("shared pull consumer started: namesrv=${config.namesrvAddr}")
            created
        }
    }

    /** 共享 Producer；首次调用时创建并 start。 */
    suspend fun producer(): DefaultMQProducer {
        producer?.let { return it }
        return createLock.withLock {
            producer?.let { return@withLock it }
            val created = withContext(Dispatchers.IO) {
                DefaultMQProducer(PRODUCE_GROUP, tls.rpcHook).apply {
                    setNamesrvAddr(config.namesrvAddr.trim())
                    setUseTLS(config.tlsEnabled)
                }
            }
            withContext(Dispatchers.IO) { tls.start { created.start() } }
            producer = created
            rocketMqLog.debug("shared producer started: namesrv=${config.namesrvAddr}")
            created
        }
    }

    /**
     * 关闭全部句柄。**不等待 [access]** —— 若此刻有协程卡在 `pull` 里，等锁就等于
     * 把断开路径一起拖死。
     *
     * `shutdown()` 会让后续调用立刻抛「客户端已关闭」，进行中的 Remoting 请求在
     * 与 broker 的连接被拆掉后返回；这比无限期等一个卡住的 pull 更好 —— 断开是
     * 用户明确要求的动作，它必须立刻生效。
     */
    suspend fun shutdown() = withContext(Dispatchers.IO) {
        val consumer = pullConsumer.also { pullConsumer = null }
        val prod = producer.also { producer = null }
        consumer?.let {
            runCatching { it.shutdown() }.onFailure { e -> rocketMqLog.warn("pull consumer shutdown failed: $e") }
        }
        prod?.let {
            runCatching { it.shutdown() }.onFailure { e -> rocketMqLog.warn("producer shutdown failed: $e") }
        }
        // 临时 PEM 必须删（含未加密私钥），放在最后：上面 shutdown 可能还要读它们。
        runCatching { tls.close() }
        rocketMqLog.debug("shared message clients shut down")
    }

    companion object {
        /**
         * 浏览消费者组：**固定名**。
         *
         * 旧实现 `CID_mqclient_browse_<uuid>` 每次新建一个组，broker 侧组表会无限
         * 增长。assign/pull 模式不提交消费位点，共用同名组不会串进度
         * （`DefaultMQPullConsumer` 不复用 `SubscriptionGroupConfig` 的消费偏移语义）。
         */
        const val PULL_GROUP = "CID_mqclient_browse"

        /** 生产者组：固定名（同上，旧实现每条发送一个新组）。 */
        const val PRODUCE_GROUP = "PID_mqclient"
    }
}
