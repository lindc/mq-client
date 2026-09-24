package com.mqclient.ibmmq

import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import com.ibm.mq.pcf.PCFMessage
import com.ibm.mq.pcf.PCFMessageAgent
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.cache.CacheManager
import com.mqclient.core.event.EventNames
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.ibmmq.event.IbmMQEvents
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.pcf.PcfObjectScanner
import java.io.FileInputStream
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** OpenJDK 用标准 `TLS_*` 套件名；此属性必须为 false，否则 IBM 映射把名字查不到（2397）。 */
internal const val USE_IBM_CIPHER_MAPPINGS = "com.ibm.mq.cfg.useIBMCipherMappings"

/**
 * 组装 MQ 连接属性：CLIENT 网络参数 + 认证 + SSL（cipher / peerName）。
 * [IbmMQConnectionManager.connect] 与 [IbmMQService.testConnection] 共用同一份组装，
 * 保证「测试」和「正式连接」的行为永远一致。
 */
internal fun mqProperties(config: IbmMQConfig): java.util.Hashtable<String, Any> {
    val properties = java.util.Hashtable<String, Any>()
    if (config.mode == ConnectionMode.CLIENT) {
        properties[MQConstants.HOST_NAME_PROPERTY] = config.host.trim()
        properties[MQConstants.PORT_PROPERTY] = config.port
        properties[MQConstants.CHANNEL_PROPERTY] = config.channel.trim()
    }
    if (config.username.isNotBlank()) properties[MQConstants.USER_ID_PROPERTY] = config.username
    if (config.password.isNotBlank()) properties[MQConstants.PASSWORD_PROPERTY] = config.password
    properties[MQConstants.CCSID_PROPERTY] = config.ccsid
    // SSL 只对 CLIENT 模式有意义（BINDING 是同机 JNI，没有 TLS 通道）。
    if (config.mode == ConnectionMode.CLIENT && config.sslEnabled) {
        config.cipherSuite.takeIf { it.isNotBlank() }
            ?.let { properties[MQConstants.SSL_CIPHER_SUITE_PROPERTY] = it }
        config.peerName.takeIf { it.isNotBlank() }
            ?.let { properties[MQConstants.SSL_PEER_NAME_PROPERTY] = it }
    }
    return properties
}

/** 队列管理器的运行状态（`MQCMD_INQUIRE_Q_MGR_STATUS` 的 MQIASY_STATUS 值的符号化）。 */
enum class QmgrStatus { STARTING, RUNNING, QUIESCING, STANDBY }

/**
 * 队列管理器自身的属性快照（PCF `MQCMD_INQUIRE_Q_MGR` + `MQCMD_INQUIRE_Q_MGR_STATUS`）。
 *
 * 刻意只有 QMGR 单条命令能拿到的属性；队列 / 通道 / 监听器的**数量**不在这里 ——
 * 那要靠逐类对象的列表查询拼出来，开销与权限要求都高，等对象列表链路接真 PCF 时
 * 顺带补，不单独为属性框发查询。
 */
data class QmgrProperties(
    val name: String,
    val version: String,
    /** 平台（MQPL_* 的符号化名称，如 `Windows` / `z/OS`）。 */
    val platform: String,
    /** 命令级别（如 940 = v9.4），界面原样展示。 */
    val commandLevel: Int,
    /** 运行状态；Q_MGR_STATUS 查询失败（权限不足等）时为 null，界面显示 —。 */
    val status: QmgrStatus?,
    /** 启动时间（QMGR 本地时区的原始字符串）。 */
    val startTime: String?,
    val ccsid: Int,
    /** CCSID 的可读名称（如 `UTF-8`）；未知编码显示编码号本身。 */
    val ccsidName: String,
    val maxMsgLength: Int,
    val deadLetterQueue: String,
)

/**
 * IBM MQ 模块的业务逻辑：干活 + 发事件。
 *
 * 业务代码只认 [ServiceContext]（总线 + 模块 scope），不认全局单例，也不关心谁在
 * 监听它发出的事件。
 *
 * 扫描链路（真 PCF，[PcfObjectScanner]）：
 *  - 心跳快扫（默认 10s）：INQUIRE_Q_STATUS 一条拿全部本地队列的当前深度，
 *    add-or-update 进 `objects.queues`（新队列以最小快照追加，全属性等慢扫补全）；
 *  - 对象慢扫（默认 60min）：[PcfObjectScanner.scanAll] 扫齐 11 类对象的完整
 *    属性，写入每连接专属的 [CacheManager]（`objects.<type>` 键，TTL 90 分钟）。
 *
 * 缓存：每条连接的快照存在它专属的 CacheManager 里（入口见 [IbmMQModule.connectionOrNull]）：
 * 扫描链路负责刷新（write-through），cachedObjects / cachedQueueManager
 * 是只读入口 —— 读不碰 MQ，刷新不阻塞读。
 */
class IbmMQService(private val context: ServiceContext) {

    private companion object {
        /**
         * 全对象扫描（MqObjectSnapshot）的 TTL：90 分钟，比 60 分钟的慢扫间隔长
         * 一截 —— 同样只是「失联后数据别永远烂在缓存里」的兜底。
         */
        const val OBJECTS_FULL_TTL_MS = 90 * 60 * 1_000L

        /** 全对象扫描的缓存 key 前缀：`objects.<type>`。 */
        const val OBJECTS_KEY_PREFIX = "objects."

        /** [MqObjectType] → 缓存 key 后缀（如 queues / channels / authInfos）。 */
        fun objectsKey(type: MqObjectType): String = OBJECTS_KEY_PREFIX + when (type) {
            MqObjectType.QUEUE_MANAGER -> "qmgr"
            MqObjectType.QUEUE -> "queues"
            MqObjectType.CHANNEL -> "channels"
            MqObjectType.TOPIC -> "topics"
            MqObjectType.SUBSCRIPTION -> "subscriptions"
            MqObjectType.LISTENER -> "listeners"
            MqObjectType.SERVICE -> "services"
            MqObjectType.PROCESS -> "processes"
            MqObjectType.NAMELIST -> "namelists"
            MqObjectType.AUTH_INFO -> "authInfos"
            MqObjectType.COMM_INFO -> "commInfos"
        }
    }

    /** 同一模块内多次扫描串行执行，避免重叠扫描互相覆盖结果。 */
    private val scanLock = Mutex()

    /** PCF 全对象扫描器（每次查询自建 agent，可并发）。 */
    private val objectScanner = PcfObjectScanner()

    /**
     * 当前手动扫描的协程。
     *
     * 用 [AtomicReference] 而不是裸 `var`：[startScan] 从界面线程调、[close] 从退出线程
     * 调，「读旧值 → 写新值」这种 check-then-act 没有同步时会丢更新（旧协程漏取消，
     * 继续在后台扫）。
     */
    private val scanJob = AtomicReference<Job?>(null)

    /**
     * 需要显式释放的句柄（真实连接 / 会话 / 消费者 / 通道），key 通常用 connectionId。
     *
     * 仓库里还没有真 MQ 依赖，所以现在它是空的；接真库时在建立连接后调
     * [registerResource] 登记，[close] 负责逐个关掉 —— 「谁负责关」永远只有一个答案。
     */
    private val resources = ConcurrentHashMap<String, AutoCloseable>()

    /** 触发一次扫描；重复调用会取消上一次未完成的扫描。 */
    fun startScan(connectionId: String) {
        val started = context.coroutineScope.launch {
            scanLock.withLock {
                // 显式连接模型：没连上就没有 cache 可写，也没有 MQ 句柄可查。
                if (connectedContext(connectionId) == null) return@launch
                // 事件名来自 IbmMQEvents，payload 里的 moduleId / source 由
                // ServiceContext.emit 自动补上。
                context.emit(IbmMQEvents.SCAN_STARTED, mapOf("connectionId" to connectionId))
                try {
                    // 真 PCF：与心跳同一条 INQUIRE_Q_STATUS 快扫链路。
                    val counts = objectScanner.scanQueueStatuses(connectionId)
                    syncQueueDepths(connectionId, counts)
                    context.emit(
                        name = IbmMQEvents.SCAN_COMPLETED,
                        payload = mapOf(
                            "connectionId" to connectionId,
                            "queueCount" to counts.size,
                            "totalMessages" to counts.sumOf { it.second },
                        ),
                    )
                } catch (cancellation: CancellationException) {
                    // 扫描被取消（模块关闭 / 新扫描顶掉）不算失败，也不发事件。
                    throw cancellation
                } catch (error: Throwable) {
                    context.emit(
                        name = IbmMQEvents.SCAN_FAILED,
                        payload = mapOf(
                            "connectionId" to connectionId,
                            "message" to (error.message ?: error::class.simpleName ?: "unknown"),
                        ),
                    )
                }
            }
        }
        // 先登记新协程、再取消旧的：getAndSet 是原子的，两个线程同时调 startScan 也
        // 只有一方能拿到「上一个」并取消它，不会留下两条并行扫描。
        scanJob.getAndSet(started)?.cancel()
    }

    /**
     * 定时任务入口（`ibm-mq.<connectionId>.scan.messages`，间隔来自连接配置）：扫一次
     * 消息数量。**状态守卫**：非 CONNECTED（未连 / 连接中 / 已失败）直接跳过 ——
     * 连接失败后任务不注销、靠状态守卫空转，用户重连后自动恢复。
     *
     * 与 [startScan] 共用一把 [scanLock]：定时扫描和用户手动扫描不会同时压到同一条连接上。
     * 业务错误在这里就转成 `*.failed` 事件了 —— 能落到调度器 `JOB_FAILED` 的是「意料之外」
     * 的异常（代码 bug 之类），两者别混为一谈。
     */
    suspend fun scanMessageCounts(connectionId: String) = scanLock.withLock {
        val ctx = connectedContext(connectionId) ?: return@withLock
        try {
            // 真 PCF：INQUIRE_Q_STATUS 一条拿全部本地队列的当前深度（心跳要轻，
            // 不查全量属性）。队列列表本身的属性由慢扫（scanObjects）维护。
            val counts = objectScanner.scanQueueStatuses(connectionId)
            syncQueueDepths(connectionId, counts)
            context.emit(
                name = IbmMQEvents.MESSAGES_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "queueCount" to counts.size,
                    "totalMessages" to counts.sumOf { it.second },
                ),
            )
            // 心跳跑通了即这条连接是活的，按「连接状态上报」约定报给核心（唯一入口，
            // 见 ConnectionStore.reportStatus）。状态没变时核心直接忽略，重复上报
            // 不会推着界面重组。
            ConnectionStore.reportStatus(connectionId, ConnectionStatus.Connected, context.moduleId)
        } catch (cancellation: CancellationException) {
            // 模块关闭 / 任务被取消：正常退出，不是失败，也不发事件、不改状态。
            throw cancellation
        } catch (error: Throwable) {
            val message = error.message ?: error::class.simpleName ?: "unknown"
            context.emit(
                name = IbmMQEvents.MESSAGES_SCAN_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to message),
            )
            // 扫描失败即这条连接大概率已断：上下文与核心状态一起转 FAILED（UI 显示
            // 「连接失败」），定时任务下个轮次由状态守卫跳过，等用户重连。
            ctx.lastError = message
            ctx.status = ConnectionStatus.Failed
            ConnectionStore.reportStatus(connectionId, ConnectionStatus.Failed, context.moduleId)
        }
    }

    /**
     * 定时任务入口（`ibm-mq.<connectionId>.scan.objects`，间隔来自连接配置）：扫一次
     * **全部 11 类对象**的完整属性。状态守卫同 [scanMessageCounts]。
     *
     * 一次 scanAll 内部按类型并发查（每类独立 agent、独立 60s 超时），单类失败
     * （无权限 / 超时）只跳过该类 —— 队列管理器上不是每个用户都有全部对象的查看权。
     * 结果写进该连接的缓存（`objects.<type>`，TTL 90 分钟，全量替换 —— 顺带清掉
     * QMGR 上已删除的对象）。完成后发 OBJECTS_SCANNED，订阅方（UI）读缓存刷新
     * —— 全程不碰 UI 线程。
     */
    suspend fun scanObjects(connectionId: String) = scanLock.withLock {
        connectedContext(connectionId) ?: return@withLock
        try {
            val result = objectScanner.scanAll(connectionId)

            val cache = cacheOf(connectionId)
            cache?.put(objectsKey(MqObjectType.QUEUE_MANAGER), result.queueManager, ttlMs = OBJECTS_FULL_TTL_MS)
            cache?.put(objectsKey(MqObjectType.QUEUE), result.queues, ttlMs = OBJECTS_FULL_TTL_MS)
            cache?.put(objectsKey(MqObjectType.CHANNEL), result.channels, ttlMs = OBJECTS_FULL_TTL_MS)
            cache?.put(objectsKey(MqObjectType.TOPIC), result.topics, ttlMs = OBJECTS_FULL_TTL_MS)
            cache?.put(objectsKey(MqObjectType.SUBSCRIPTION), result.subscriptions, ttlMs = OBJECTS_FULL_TTL_MS)
            cache?.put(objectsKey(MqObjectType.LISTENER), result.listeners, ttlMs = OBJECTS_FULL_TTL_MS)
            cache?.put(objectsKey(MqObjectType.SERVICE), result.services, ttlMs = OBJECTS_FULL_TTL_MS)
            cache?.put(objectsKey(MqObjectType.PROCESS), result.processes, ttlMs = OBJECTS_FULL_TTL_MS)
            cache?.put(objectsKey(MqObjectType.NAMELIST), result.namelists, ttlMs = OBJECTS_FULL_TTL_MS)
            cache?.put(objectsKey(MqObjectType.AUTH_INFO), result.authInfos, ttlMs = OBJECTS_FULL_TTL_MS)
            cache?.put(objectsKey(MqObjectType.COMM_INFO), result.commInfos, ttlMs = OBJECTS_FULL_TTL_MS)

            context.emit(
                name = IbmMQEvents.OBJECTS_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "queueCount" to result.queues.size,
                    "channelCount" to result.channels.size,
                    "topicCount" to result.topics.size,
                    "subscriptionCount" to result.subscriptions.size,
                    "listenerCount" to result.listeners.size,
                    "serviceCount" to result.services.size,
                    "processCount" to result.processes.size,
                    "namelistCount" to result.namelists.size,
                    "authInfoCount" to result.authInfos.size,
                    "commInfoCount" to result.commInfos.size,
                ),
            )
            ibmMqLog.info(
                "objects scanned: connection=$connectionId queues=${result.queues.size} " +
                    "channels=${result.channels.size} topics=${result.topics.size} " +
                    "subscriptions=${result.subscriptions.size} listeners=${result.listeners.size} " +
                    "services=${result.services.size} processes=${result.processes.size} " +
                    "namelists=${result.namelists.size} authInfos=${result.authInfos.size} " +
                    "commInfos=${result.commInfos.size}",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val message = error.message ?: error::class.simpleName ?: "unknown"
            context.emit(
                name = IbmMQEvents.OBJECTS_SCAN_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to message),
            )
            // 与消息扫描同一条连接：失败一并转 FAILED（守卫让任务空转，等重连）。
            IbmMQModule.connectionOrNull(connectionId)?.let { ctx ->
                ctx.lastError = message
                ctx.status = ConnectionStatus.Failed
            }
            ConnectionStore.reportStatus(connectionId, ConnectionStatus.Failed, context.moduleId)
        }
    }

    /**
     * 读当前缓存的**全属性对象快照**（慢扫产物）：只查缓存、不碰 MQ、不挂起。
     * 键值结构见 [objectsKey]；没有缓存 / 该类型本轮扫描失败时返回空列表
     * （QMGR 单对象在 `objects.qmgr`，用 [cachedQueueManager] 读）。
     */
    fun cachedObjects(connectionId: String, type: MqObjectType): List<MqObjectSnapshot> =
        cacheOf(connectionId)?.getAs(objectsKey(type)) ?: emptyList()

    /** 队列管理器自身的全属性快照（慢扫产物；本轮失败 / 未扫过为 null）。 */
    fun cachedQueueManager(connectionId: String): MqObjectSnapshot? =
        cacheOf(connectionId)?.getAs(objectsKey(MqObjectType.QUEUE_MANAGER))

    /**
     * 心跳 / 手动扫描的深度同步（add-or-update）：`objects.queues` 里已有的队列
     * 更新 curDepth / messageCount / attributes；本次 status 新出现的队列（慢扫
     * 间隔内新建的）以**最小快照**追加 —— 10 秒内可见，全属性等下轮慢扫补全。
     *
     * 刻意**只增改不删**：INQUIRE_Q_STATUS 与 INQUIRE_Q 的覆盖面有差异（别名 /
     * 远程队列等），心跳全量替换会把慢扫扫到的这类对象误删；已删除对象的清理
     * 由慢扫的全量替换负责。快照还没被慢扫创建时（首次连接）以最小快照全量建立。
     */
    private fun syncQueueDepths(connectionId: String, counts: List<Pair<String, Int>>) {
        val cache = cacheOf(connectionId) ?: return
        val key = objectsKey(MqObjectType.QUEUE)
        val existing = cache.getAs<List<MqObjectSnapshot>>(key) ?: emptyList()
        val byName = existing.associateBy { it.name }
        val merged = counts.map { (name, depth) ->
            byName[name]?.copy(
                currentDepth = depth,
                messageCount = depth,
                attributes = byName.getValue(name).attributes + ("MQIA_CURRENT_Q_DEPTH" to depth),
            ) ?: MqObjectSnapshot(
                name = name,
                type = MqObjectType.QUEUE,
                currentDepth = depth,
                messageCount = depth,
                attributes = mapOf("MQIA_CURRENT_Q_DEPTH" to depth),
            )
        }
        cache.put(key, merged, ttlMs = OBJECTS_FULL_TTL_MS)
    }

    /** 连接处于 CONNECTED 时的上下文；否则 null —— 显式连接模型的扫描守卫。 */
    private fun connectedContext(connectionId: String): IbmMQConnectionContext? =
        IbmMQModule.connectionOrNull(connectionId)
            ?.takeIf { it.status == ConnectionStatus.Connected }

    /**
     * 取该连接的缓存实例；连接不存在 / 未连接（cache 只在连接成功后创建）时为 null。
     */
    private fun cacheOf(connectionId: String): CacheManager? =
        IbmMQModule.connectionOrNull(connectionId)?.cache

    /**
     * 登记一个要在 [close] 时释放的句柄（真实连接 / 消费者 / 通道）。
     *
     * 同一个 key 重复登记会先把旧句柄关掉 —— 重连场景下不能让上一个连接漏在那里。
     */
    fun registerResource(key: String, resource: AutoCloseable) {
        resources.put(key, resource)?.let { previous ->
            try {
                previous.close()
            } catch (error: Throwable) {
                reportResourceFailure(key, error)
            }
        }
    }

    /**
     * 模块销毁：停掉扫描协程，再逐个释放登记过的资源（订阅的取消由 [IbmMQModule] 负责）。
     *
     * 每个句柄单独 try/catch：一个关不掉不该让其余资源跟着漏掉（这正是 try/finally 要
     * 保证的语义）。失败上报到错误通道，而不是只打一行控制台日志 —— 打包后没人看得到。
     */
    fun close() {
        scanJob.getAndSet(null)?.cancel()
        resources.keys.toList().forEach { key ->
            val handle = resources.remove(key) ?: return@forEach
            try {
                handle.close()
            } catch (error: Throwable) {
                reportResourceFailure(key, error)
            }
        }
    }

    private fun reportResourceFailure(key: String, error: Throwable) {
        context.eventBus.emit(
            name = EventNames.ERROR_OCCURRED,
            payload = mapOf(
                "message" to "释放资源 '$key' 失败：${error.message ?: error::class.simpleName ?: "unknown"}",
                "moduleId" to context.moduleId,
                "error" to error::class.simpleName,
            ),
            source = context.moduleId,
        )
    }

    /**
     * 用给定配置发起一次真实连接，验证可达性；**临时连接，用完即关**，不登记资源、
     * 不写缓存、不上报状态 —— 正式数据只属于 [ConnectionStore] 里的连接。
     *
     * 失败以异常抛出（含 MQ 原因码的消息），成功静默返回；「测试连接」按钮和
     * 「保存前测试」都走这里。超时由调用方 `withTimeout` 控制 —— 注意 MQ 的 connect
     * 是阻塞 IO，协程取消不能中断它：超时后本次调用立即返回失败，但底层 IO 线程
     * 会滞留到 TCP 超时为止。本地桌面工具场景下可接受，不做额外处理。
     *
     * SSL（OpenJDK 下的两个硬坑，见 [configureSslContext] 与 [USE_IBM_CIPHER_MAPPINGS]）：
     * cipher 映射必须关掉（IBM 风格 `SSL_*` 名在 OpenJDK 上不存在，报 2397）；
     * 证书走**显式构建的 SSLContext**（JSSE 系统属性在默认上下文被缓存后设置无效，
     * 会报 2393）。基准测试见 `jvmTest` 的 `IbmMQSslDiagTest`。
     */
    suspend fun testConnection(config: IbmMQConfig): Unit = withContext(Dispatchers.IO) {
        // SSL 属性（keystore / truststore / cipher 映射）见下方 sslEnabled 分支；
        // 连接属性统一走 [mqProperties]，与正式连接共用同一份组装。
        val properties = mqProperties(config)

        // SSL 只对 CLIENT 模式有意义（BINDING 是同机 JNI，没有 TLS 通道）。
        if (config.mode == ConnectionMode.CLIENT && config.sslEnabled) {
            System.setProperty(USE_IBM_CIPHER_MAPPINGS, "false")
            // MQ 基础 Java 客户端**自己从 JSSE 系统属性构建 SSLContext**（不用 JVM
            // 默认上下文 —— setDefault 对它无效），所以属性必须设全，包括 Type。
            // 基准测试 combo3（仅系统属性、属性先于首次 SSL 使用）验证这是生效路径。
            config.trustStorePath.takeIf { it.isNotBlank() }
                ?.let { System.setProperty("javax.net.ssl.trustStore", it) }
            config.trustStorePassword.takeIf { it.isNotBlank() }
                ?.let { System.setProperty("javax.net.ssl.trustStorePassword", it) }
            config.trustStorePath.takeIf { it.isNotBlank() }
                ?.let { System.setProperty("javax.net.ssl.trustStoreType", "PKCS12") }
            config.keyStorePath.takeIf { it.isNotBlank() }
                ?.let { System.setProperty("javax.net.ssl.keyStore", it) }
            config.keyStorePassword.takeIf { it.isNotBlank() }
                ?.let { System.setProperty("javax.net.ssl.keyStorePassword", it) }
            config.keyStorePath.takeIf { it.isNotBlank() }
                ?.let { System.setProperty("javax.net.ssl.keyStoreType", "PKCS12") }
            configureSslContext(config)
        }
        ibmMqLog.info(
            "test connection: host=${config.host}:${config.port}, channel=${config.channel}, " +
                "cipher=${config.cipherSuite.ifBlank { "(auto)" }}, ssl=${config.sslEnabled}, " +
                "mappings=${System.getProperty(USE_IBM_CIPHER_MAPPINGS)}",
        )

        // 构造即发起连接；失败在这里直接抛 MQException。
        val manager = MQQueueManager(config.queueManager.trim(), properties)
        try {
            // 连上即视为可达；不做更深的访问（列表 / 收发归正式连接的扫描链路）。
        } finally {
            // 用完即关：测试连接绝不留在 [resources] 里 —— 它不属于任何 connectionId。
            manager.disconnect()
        }
    }

    /**
     * 用配置里的 p12 显式构建 SSLContext 并替换 JVM 默认上下文（双保险）。
     *
     * 注意：**主路径是上面的 JSSE 系统属性** —— MQ 基础 Java 客户端自己从
     * `javax.net.ssl.*` 属性构建 SSL 上下文，`SSLContext.setDefault` 对它无效。
     * 这里显式构建是给「其它走 JVM 默认上下文的 TLS 代码」兜底，也顺带提前
     * 验证两个 p12 能否加载（口令错 / 文件坏在这里就炸，错误更直白）。
     *
     * 密钥库 / 信任库都按 PKCS12 读；某一边留空就退回 JVM 默认对应侧。
     */
    private fun configureSslContext(config: IbmMQConfig) {
        val keyManagers = config.keyStorePath.takeIf { it.isNotBlank() }?.let { path ->
            val store = KeyStore.getInstance("PKCS12").apply {
                FileInputStream(path).use { load(it, config.keyStorePassword.toCharArray()) }
            }
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store, config.keyStorePassword.toCharArray())
            }.keyManagers
        }
        val trustManagers = config.trustStorePath.takeIf { it.isNotBlank() }?.let { path ->
            val store = KeyStore.getInstance("PKCS12").apply {
                FileInputStream(path).use { load(it, config.trustStorePassword.toCharArray()) }
            }
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(store)
            }.trustManagers
        }
        if (keyManagers == null && trustManagers == null) return
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers, trustManagers, null)
        SSLContext.setDefault(context)
    }

    /**
     * 查询一条**已连接**连接的队列管理器属性（PCF：`MQCMD_INQUIRE_Q_MGR` 拿配置
     * 属性，`MQCMD_INQUIRE_Q_MGR_STATUS` 拿运行状态；后者失败（部分平台 / 权限
     * 受限）不拖垮整体，status / startTime 为 null）。
     *
     * 未连接时抛 IllegalStateException —— 调用方（属性面板）只会在连接建立后进来；
     * 查询走 [PCFMessageAgent]，它复用 [MQQueueManager] 的既有连接，不另建 TCP。
     */
    suspend fun queryQmgrProperties(connectionId: String): QmgrProperties = withContext(Dispatchers.IO) {
        val manager = IbmMQModule.connectionOrNull(connectionId)?.mqConnection
            ?: throw IllegalStateException("连接 $connectionId 未建立，无法查询队列管理器属性")
        val agent = PCFMessageAgent(manager)
        try {
            val qmgr = agent.send(PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR)).firstOrNull()
                ?: throw IllegalStateException("MQCMD_INQUIRE_Q_MGR 未返回任何数据")
            val statusResponse = runCatching {
                agent.send(PCFMessage(MQConstants.MQCMD_INQUIRE_Q_MGR_STATUS)).firstOrNull()
            }.getOrNull()

            val ccsid = qmgr.int(MQConstants.MQIA_CODED_CHAR_SET_ID) ?: 0
            QmgrProperties(
                name = qmgr.str(MQConstants.MQCA_Q_MGR_NAME).orEmpty().trim(),
                version = qmgr.str(MQConstants.MQCA_VERSION).orEmpty(),
                platform = platformName(qmgr.int(MQConstants.MQIA_PLATFORM)),
                commandLevel = qmgr.int(MQConstants.MQIA_COMMAND_LEVEL) ?: 0,
                status = statusResponse?.int(MQConstants.MQIACF_Q_MGR_STATUS)?.let(::qmgrStatus),
                startTime = statusResponse?.str(MQConstants.MQCACF_Q_MGR_START_TIME),
                ccsid = ccsid,
                ccsidName = ccsidName(ccsid),
                maxMsgLength = qmgr.int(MQConstants.MQIA_MAX_MSG_LENGTH) ?: 0,
                deadLetterQueue = qmgr.str(MQConstants.MQCA_DEAD_LETTER_Q_NAME).orEmpty().trim(),
            )
        } finally {
            runCatching { agent.disconnect() }
        }
    }
}

/* ------------------------------ PCF 取值与符号化助手 ------------------------------ */

/** PCF 字符串参数：正常是 String，个别实现返回 ByteArray，都兜住。 */
private fun PCFMessage.str(selector: Int): String? = when (val value = getParameterValue(selector)) {
    null -> null
    is String -> value
    is ByteArray -> value.decodeToString().trimEnd('\u0000')
    else -> value.toString()
}

/** PCF 整型参数；缺失 / 不可解析返回 null（调用方自行决定默认值）。 */
private fun PCFMessage.int(selector: Int): Int? = when (val value = getParameterValue(selector)) {
    null -> null
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}

/** MQPL_* 平台常量到可读名称的映射；未知值原样显示编码号。 */
private fun platformName(platform: Int?): String = when (platform) {
    MQConstants.MQPL_MVS -> "z/OS"
    MQConstants.MQPL_UNIX -> "UNIX"
    MQConstants.MQPL_OS400 -> "IBM i"
    MQConstants.MQPL_WINDOWS_NT -> "Windows"
    else -> platform?.toString() ?: ""
}

/**
 * QMGR 运行状态值（PCF 参考手册 `MQIACF_Q_MGR_STATUS` 的取值，cmqxfc.h 里的
 * MQIASY_STATUS_*；allclient 的常量类没收录这些符号，只能按文档数值本地定义）。
 */
private const val QMGR_STATUS_STARTING = 1
private const val QMGR_STATUS_RUNNING = 2
private const val QMGR_STATUS_QUIESCING = 3
private const val QMGR_STATUS_STANDBY = 5

/** MQIASY_STATUS_* 到 [QmgrStatus] 的映射；未知值返回 null（界面显示 —）。 */
private fun qmgrStatus(status: Int): QmgrStatus? = when (status) {
    QMGR_STATUS_STARTING -> QmgrStatus.STARTING
    QMGR_STATUS_RUNNING -> QmgrStatus.RUNNING
    QMGR_STATUS_QUIESCING -> QmgrStatus.QUIESCING
    QMGR_STATUS_STANDBY -> QmgrStatus.STANDBY
    else -> null
}

/** 常见 CCSID 的可读名称；这里的名字是编码事实（charset 名），不参与翻译。 */
private fun ccsidName(ccsid: Int): String = when (ccsid) {
    1208 -> "UTF-8"
    1200 -> "UCS-2"
    1381 -> "GBK"
    5488 -> "GB18030"
    819 -> "Windows-1252"
    37 -> "IBM037 (EBCDIC)"
    500 -> "IBM500 (EBCDIC)"
    else -> "CCSID $ccsid"
}
