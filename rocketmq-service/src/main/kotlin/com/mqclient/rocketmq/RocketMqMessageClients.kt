package com.mqclient.rocketmq

import com.mqclient.core.ServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
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
 * ## 句柄隔离规范（底层连接共享，业务句柄用完即毁）
 *
 * 本类持有的是**重量级连接级句柄**：[producer] 与 [pullConsumer] 懒建一次、随连接
 * 存活，只在断连 / 模块关闭时 [shutdown]，绝不单次查询后 shutdown。
 * 真正的消息拉取由 [tempPullConsumer] 为每次浏览新建**独立 PullConsumer**，用完在
 * `finally` 中 shutdown（见 [RocketMqMessageService.readMessages] 的并行分片）——
 * 单次 pull 异常只毁这一条临时实例，不会污染共享客户端、更不会连坐底层连接。
 * 共享句柄的每次访问都整段持 [access] 锁，保证严格单线程隔离。
 *
 * ## TLS
 *
 * RocketMQ 的证书走 `TlsSystemConfig` **进程级静态字段**，而客户端只在 `start()`
 * 内部读取它们（详见 [RocketMqTlsStart]）。所以 [RocketMqTlsStart] 必须**与客户端
 * 同生共死**：start 窗口用它注入证书，临时 PEM 文件也要等到 close 才能删。
 * 因此本类持有 `tls` 的整个生命周期，而不是 `use { }` 包一次 start 就丢。
 */
class RocketMqMessageClients(private val config: RocketMqConfig) {

    init {
        // 与 RocketMqAdminClient 同一道兜底（幂等）：Producer / PullConsumer 同样会
        // 牵出 shaded logback，落点必须在它们构造之前就定好。
        RocketMqLogging.install()
    }

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

    /**
     * 已经 [shutdown] 过。
     *
     * 必须在**创建路径**上检查，不能只把字段置 null：`clients()` 拿到本对象与
     * [shutdown] 并发时，创建方可能在字段清空之后新建一个客户端并赋给已报废的持有者 ——
     * 槽位不再引用它，谁也不会调 `shutdown()`，那是个永久 Netty 客户端 + 心跳线程（TLS
     * 连接下还会拿已被 [RocketMqTlsStart.close] 删掉的 PEM 做握手）。
     */
    private val closed = AtomicBoolean(false)

    /**
     * 仍活着的临时 PullConsumer（浏览并行分片用）。
     *
     * 记在连接级对象上而不是只靠调用方 finally：分片创建时任一 `async` 失败，
     * `awaitAll()` 会直接抛，拿到一半的实例就再也没人关。登记后 [shutdown] 能兜底。
     */
    private val tempConsumers: MutableSet<DefaultMQPullConsumer> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun closedException() = ServiceException(
        "rocketmq_err_clients_closed",
        fallbackMessage = "RocketMQ message clients are already shut down; reconnect the connection first",
    )

    private fun ensureOpen() {
        if (closed.get()) throw closedException()
    }

    /**
     * 创建并 start 一个客户端，**启动失败时就地关掉它**。
     *
     * `DefaultMQProducer.start()` / `DefaultMQPullConsumer.start()` 会先建 Netty 客户端、
     * 调度执行器与心跳，再去做可能失败的握手 / 路由请求 —— 中途抛异常时实例已经是
     * 「半启动」的，不 shutdown 就是线程与 broker 侧组注册的泄漏。
     *
     * 调用方必须已持 [createLock]。
     */
    private suspend fun <T> startGuarded(
        label: String,
        create: () -> T,
        start: (T) -> Unit,
        shutdown: (T) -> Unit,
    ): T {
        ensureOpen()
        val created = withContext(Dispatchers.IO) { create() }
        try {
            // start 必须在 TLS 窗口内（证书是全局静态字段，见 RocketMqTlsStart）。
            withContext(Dispatchers.IO) { tls.start { start(created) } }
        } catch (error: Throwable) {
            // 半启动的句柄不能留：关它本身也不受调用方取消影响。
            withContext(NonCancellable + Dispatchers.IO) { runCatching { shutdown(created) } }
            throw error
        }
        // 创建期间连接被断开：这个新句柄从未登记，必须就地关掉。
        if (closed.get()) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { shutdown(created) } }
            throw closedException()
        }
        rocketMqLog.debug("$label started: namesrv=${config.namesrvAddr}")
        return created
    }

    /** 共享 PullConsumer；首次调用时创建并 start。 */
    suspend fun pullConsumer(): DefaultMQPullConsumer {
        ensureOpen()
        pullConsumer?.let { return it }
        return createLock.withLock {
            pullConsumer?.let { return@withLock it }
            val created = startGuarded(
                label = "shared pull consumer",
                create = {
                    DefaultMQPullConsumer(PULL_GROUP, tls.rpcHook).apply {
                        setNamesrvAddr(config.namesrvAddr.trim())
                        // 开关显式传入：ClientConfig.useTLS 在构造时固化，start 时再写全局来不及。
                        setUseTLS(config.tlsEnabled)
                    }
                },
                start = { it.start() },
                shutdown = { it.shutdown() },
            )
            pullConsumer = created
            created
        }
    }

    /**
     * 一次性临时 PullConsumer（消息浏览「全部 Queue」并行分片用）：与共享实例同 group
     * 同配置，但**不登记到连接**——调用方用完调 [shutdownTemp]；忘记调或被取消时，
     * [shutdown] 会兜底收掉。pull 全程手动 begin / 不提交位点，实例间无共享状态，
     * 可安全并发。
     */
    suspend fun tempPullConsumer(): DefaultMQPullConsumer = createLock.withLock {
        val created = startGuarded(
            label = "temp pull consumer",
            create = {
                DefaultMQPullConsumer(PULL_GROUP, tls.rpcHook).apply {
                    setNamesrvAddr(config.namesrvAddr.trim())
                    setUseTLS(config.tlsEnabled)
                }
            },
            start = { it.start() },
            shutdown = { it.shutdown() },
        )
        tempConsumers += created
        created
    }

    /** 关掉一个临时 PullConsumer（调用方 finally）；重复调用安全。 */
    suspend fun shutdownTemp(consumer: DefaultMQPullConsumer) {
        tempConsumers.remove(consumer)
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { consumer.shutdown() }
                .onFailure { e -> rocketMqLog.warn("temp pull consumer shutdown failed: $e") }
        }
    }

    /** 共享 Producer；首次调用时创建并 start。 */
    suspend fun producer(): DefaultMQProducer {
        ensureOpen()
        producer?.let { return it }
        return createLock.withLock {
            producer?.let { return@withLock it }
            val created = startGuarded(
                label = "shared producer",
                create = {
                    DefaultMQProducer(PRODUCE_GROUP, tls.rpcHook).apply {
                        setNamesrvAddr(config.namesrvAddr.trim())
                        setUseTLS(config.tlsEnabled)
                    }
                },
                start = { it.start() },
                shutdown = { it.shutdown() },
            )
            producer = created
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
    suspend fun shutdown() = withContext(NonCancellable + Dispatchers.IO) {
        // 取 [createLock]：与正在进行的创建串行。创建方在 start 之后会重新检查
        // [closed]，要么自己关掉新句柄并抛异常，要么把它登记到字段上让本轮关掉。
        createLock.withLock {
            closed.set(true)
            val consumer = pullConsumer.also { pullConsumer = null }
            val prod = producer.also { producer = null }
            val temps = tempConsumers.toList().also { tempConsumers.clear() }
            consumer?.let {
                runCatching { it.shutdown() }.onFailure { e -> rocketMqLog.warn("pull consumer shutdown failed: $e") }
            }
            prod?.let {
                runCatching { it.shutdown() }.onFailure { e -> rocketMqLog.warn("producer shutdown failed: $e") }
            }
            // 分片临时实例的兜底：调用方漏关 / 创建一半失败留下的都在这里。
            temps.forEach { temp ->
                runCatching { temp.shutdown() }
                    .onFailure { e -> rocketMqLog.warn("temp pull consumer shutdown failed: $e") }
            }
            // 临时 PEM 必须删（含未加密私钥），放在最后：上面 shutdown 可能还要读它们。
            runCatching { tls.close() }
        }
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
