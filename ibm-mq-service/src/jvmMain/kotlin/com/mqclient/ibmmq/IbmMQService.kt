package com.mqclient.ibmmq

import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.cache.CacheManager
import com.mqclient.core.event.EventNames
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.ibmmq.event.IbmMQEvents
import com.mqclient.ibmmq.messaging.MessageBrowseSession
import com.mqclient.ibmmq.messaging.MessageOperationResult
import com.mqclient.ibmmq.messaging.SendResult
import com.mqclient.ibmmq.messaging.deleteMessageById
import com.mqclient.ibmmq.messaging.downloadMessageBody
import com.mqclient.ibmmq.messaging.getMessageDetail
import com.mqclient.ibmmq.messaging.sendMessageToQueue
import com.mqclient.ibmmq.pcf.ChannelStatusDetail
import com.mqclient.ibmmq.pcf.ClusterRelations
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.pcf.PcfObjectScanner
import com.mqclient.ibmmq.pcf.channelStatusName
import com.mqclient.ibmmq.pcf.isSystemObject
import com.mqclient.ibmmq.pcf.isSystemOrTempQueue
import com.mqclient.ibmmq.pcf.listenerStatusName
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

/**
 * IBM MQ 模块的业务逻辑：干活 + 发事件。
 *
 * 业务代码只认 [ServiceContext]（总线 + 模块 scope），不认全局单例，也不关心谁在
 * 监听它发出的事件。
 *
 * 扫描链路（真 PCF，[PcfObjectScanner]）：
 *  - 心跳快扫（默认 10s）：INQUIRE_Q_STATUS / INQUIRE_CHANNEL_STATUS 两条，拿全部
 *    本地队列的当前深度与通道的运行状态，add-or-update 进 `objects.queues` /
 *    `objects.channels`（新对象以最小快照追加，全属性等慢扫补全）；
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
                    // 真 PCF：与心跳同一条快扫链路（队列深度 + 通道状态）。
                    val counts = objectScanner.scanQueueStatuses(connectionId)
                    syncQueueDepths(connectionId, counts)
                    val channelStatuses = objectScanner.scanChannelStatuses(connectionId)
                    syncChannelStatuses(connectionId, channelStatuses)
                    context.emit(
                        name = IbmMQEvents.SCAN_COMPLETED,
                        payload = mapOf(
                            "connectionId" to connectionId,
                            "queueCount" to counts.size,
                            "totalMessages" to counts.sumOf { it.second },
                            "channelCount" to channelStatuses.size,
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
     * 轮询单个通道的真实状态（通道启停命令返回成功≠状态已变，IBM MQ 的 STOP/START 异步）：
     *  - INQUIRE_CHANNEL_STATUS 只查该通道，写回缓存（add-or-update，保留 channelType），
     *    并广播 [IbmMQEvents.MESSAGES_SCANNED] 让列表状态机刷新；
     *  - 返回可读状态名（RUNNING / STOPPED …）；通道已停（无状态行）返回 "STOPPED"；
     *  - 通道还不在缓存里（列表尚未扫描）返回 null，由调用方继续轮询；
     *  - 查询本身失败（连接断开 / 权限不足 / 超时）直接抛异常，不静默，由调用方决定是否计入失败。
     * 该方法只动单条通道缓存，不会删除其它通道，也不触发整轮全量扫描。
     */
    suspend fun pollChannelStatus(connectionId: String, name: String): String? {
        val statuses = objectScanner.queryChannelStatus(connectionId, name)
        val cache = cacheOf(connectionId) ?: return null
        val key = objectsKey(MqObjectType.CHANNEL)
        val existing = cache.getAs<List<MqObjectSnapshot>>(key) ?: emptyList()
        val idx = existing.indexOfFirst { it.name == name }
        if (idx < 0) return null // 列表尚未扫描到该通道，无法确认状态，交给轮询下一轮
        val resolved = statuses.firstOrNull()?.let { channelStatusName(it.second) } ?: "STOPPED"
        val statusValue = statuses.firstOrNull()?.second
        val updated = existing.toMutableList().apply {
            this[idx] = existing[idx].copy(
                channelStatus = resolved,
                attributes = existing[idx].attributes + ("MQIACH_CHANNEL_STATUS" to (statusValue ?: -1)),
            )
        }
        cache.put(key, updated, ttlMs = OBJECTS_FULL_TTL_MS)
        context.emit(
            name = IbmMQEvents.MESSAGES_SCANNED,
            payload = mapOf(
                "connectionId" to connectionId,
                "queueCount" to 0,
                "totalMessages" to 0,
            ),
        )
        return resolved
    }

    /**
     * 轮询单个监听器的真实状态（监听器启停命令返回成功≠状态已变，IBM MQ 的 START/STOP 异步）：
     *  - INQUIRE_LISTENER_STATUS 只查该监听器，写回缓存（add-or-update，保留 port 等属性），
     *    并广播 [IbmMQEvents.MESSAGES_SCANNED] 让列表状态机刷新；
     *  - 返回可读状态名（RUNNING / NOT_RUNNING …）；监听器已停（无状态行）返回 "NOT_RUNNING"；
     *  - 监听器还不在缓存里（列表尚未扫描）返回 null，由调用方继续轮询；
     *  - 查询本身失败（连接断开 / 权限不足 / 超时）直接抛异常，不静默，由调用方决定是否计入失败。
     * 该方法只动单条监听器缓存，不会删除其它监听器，也不触发整轮全量扫描。
     */
    suspend fun pollListenerStatus(connectionId: String, name: String): String? {
        val statuses = objectScanner.queryListenerStatus(connectionId, name)
        val cache = cacheOf(connectionId) ?: return null
        val key = objectsKey(MqObjectType.LISTENER)
        val existing = cache.getAs<List<MqObjectSnapshot>>(key) ?: emptyList()
        val idx = existing.indexOfFirst { it.name == name }
        if (idx < 0) return null // 列表尚未扫描到该监听器，无法确认状态，交给轮询下一轮
        val resolved = statuses.firstOrNull()?.let { listenerStatusName(it.second) } ?: "NOT_RUNNING"
        val statusValue = statuses.firstOrNull()?.second
        val updated = existing.toMutableList().apply {
            this[idx] = existing[idx].copy(
                listenerStatus = resolved,
                attributes = existing[idx].attributes + ("MQIACH_LISTENER_STATUS" to (statusValue ?: -1)),
            )
        }
        cache.put(key, updated, ttlMs = OBJECTS_FULL_TTL_MS)
        context.emit(
            name = IbmMQEvents.MESSAGES_SCANNED,
            payload = mapOf(
                "connectionId" to connectionId,
                "queueCount" to 0,
                "totalMessages" to 0,
            ),
        )
        return resolved
    }

    /**
     * 单通道运行状态明细（属性对话框「运行状态」分组用）：实时查询
     * INQUIRE_CHANNEL_STATUS 全量应答（序列号 / in-doubt / 消息数 / 字节 /
     * 最后消息时间），**不写缓存、不发事件** —— 与 [pollChannelStatus] 的
     * 「写缓存 + 列表刷新」语义区分：这里只服务单对象的即时展示。
     *
     * 通道已完全停止返回 null（UI 显示「通道未运行」）；连接未建立 / 查询失败
     * 抛异常，由 UI 转「加载失败」。
     */
    suspend fun getChannelStatus(connectionId: String, name: String): ChannelStatusDetail? =
        objectScanner.queryChannelStatusDetail(connectionId, name)

    /**
     * 打开队列消息浏览会话（消息处理对话框用）：在该连接的 [MQQueueManager] 上以
     * `MQOO_BROWSE` 打开队列，光标跨调用保持，浏览不消费消息。连接未建立直接抛
     * [IllegalStateException]，由 UI 转「加载失败」。
     */
    suspend fun openBrowseSession(connectionId: String, queueName: String): MessageBrowseSession {
        val manager = IbmMQModule.connectionOrNull(connectionId)?.mqConnection
            ?: throw IllegalStateException("连接 $connectionId 未建立，无法浏览队列消息")
        return MessageBrowseSession.open(manager, queueName)
    }

    /** 单队列实时 CURDEPTH（PCF INQUIRE_Q 只点名 MQIA_CURRENT_Q_DEPTH，不读缓存）。 */
    suspend fun getQueueDepth(connectionId: String, queueName: String): Int =
        objectScanner.queryQueueDepth(connectionId, queueName)

    /**
     * 集群关系（集群关系 Tab）：本地 QMgr 的集群角色（完整仓库 / 部分仓库 /
     * 不属于集群）+ 成员实时列表（DISPLAY CLUSQMGR，不经慢扫缓存）。
     * 查询失败原样抛（MQException 由 UI 经 browseErrorText 转 MQRC 可读文案）。
     *
     * 成员的通道状态：CLUSQMGR 响应在本 QM 版本上不带运行状态（实测），补一条
     * INQUIRE_CHANNEL_STATUS（全量）按通道名合并；成员通道名缺失时经慢扫缓存的
     * 通道 RQMNAME 反查（集群发送通道指向该成员即为其通道）。状态查询失败不拖垮
     * 主体（状态列显示 -）。
     */
    suspend fun getClusterRelations(connectionId: String): ClusterRelations {
        val relations = objectScanner.queryClusterRelations(connectionId)
        val statusByName = runCatching { objectScanner.scanChannelStatuses(connectionId) }
            .onFailure { ibmMqLog.warn("cluster channel status query failed: ${it.message}") }
            .getOrDefault(emptyList())
            .toMap()
        val channelByRqm = cachedObjects(connectionId, MqObjectType.CHANNEL)
            .mapNotNull { channel ->
                channel.attributes["MQCA_REMOTE_Q_MGR_NAME"]?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }?.let { rqm -> rqm to channel.name }
            }
            .toMap()
        val members = relations.members.map { member ->
            val channelName = member.channelName.ifBlank { channelByRqm[member.qmgrName].orEmpty() }
            val status = channelName.takeIf { it.isNotEmpty() }
                ?.let { statusByName[it] }?.let { channelStatusName(it) }
                ?: member.status
            member.copy(channelName = channelName, status = status)
        }
        return relations.copy(members = members)
    }

    /**
     * 按 MessageId 下载消息体（浏览 Tab「下载」）：独立 browse 句柄扫到匹配条目取
     * 完整字节，纯浏览不消费、不影响 UI 会话光标。连接未建立返回 failure。
     */
    suspend fun downloadMessageBody(connectionId: String, queueName: String, messageIdHex: String): MessageOperationResult {
        val manager = IbmMQModule.connectionOrNull(connectionId)?.mqConnection
            ?: return MessageOperationResult.failure(null, "连接 $connectionId 未建立")
        return downloadMessageBody(manager, queueName, messageIdHex)
    }

    /**
     * 按 MessageId 取消息详情（详情对话框）：独立 browse 句柄扫到匹配条目，
     * 读完整 MQMD + 消息体组装 [MessageDetail]。纯浏览不消费；连接未建立返回 failure。
     */
    suspend fun getMessageDetail(connectionId: String, queueName: String, messageIdHex: String): MessageOperationResult {
        val manager = IbmMQModule.connectionOrNull(connectionId)?.mqConnection
            ?: return MessageOperationResult.failure(null, "连接 $connectionId 未建立")
        return getMessageDetail(manager, queueName, messageIdHex)
    }

    /**
     * 按 MessageId 删除（消费）一条消息（浏览 Tab「删除」）：browse 定位 +
     * MQGMO_MSG_UNDER_CURSOR 破坏性 get，只消费该条；删除后由调用方刷新列表。
     */
    suspend fun deleteMessage(connectionId: String, queueName: String, messageIdHex: String): MessageOperationResult {
        val manager = IbmMQModule.connectionOrNull(connectionId)?.mqConnection
            ?: return MessageOperationResult.failure(null, "连接 $connectionId 未建立")
        return deleteMessageById(manager, queueName, messageIdHex)
    }

    /**
     * 发送一条消息（消息处理对话框「发送消息」Tab）：在该连接的 [MQQueueManager]
     * 上以 `MQOO_OUTPUT` put。连接未建立直接返回 failure（不抛，调用方按结果更新
     * 任务状态）；expiryTenths 是 MQMD 的十分之一秒单位，[MQConstants.MQEI_UNLIMITED]
     * 表示不过期（秒 → 十分之一秒的换算在 UI 层做）。
     */
    suspend fun sendMessage(
        connectionId: String,
        queueName: String,
        content: ByteArray,
        correlationId: String? = null,
        replyToQueue: String? = null,
        persistence: Int = MQConstants.MQPER_PERSISTENT,
        priority: Int = 4,
        expiryTenths: Int = MQConstants.MQEI_UNLIMITED,
    ): SendResult {
        val manager = IbmMQModule.connectionOrNull(connectionId)?.mqConnection
            ?: return SendResult.failure(null, "连接 $connectionId 未建立，无法发送消息")
        return sendMessageToQueue(
            manager = manager,
            queueName = queueName,
            content = content,
            correlationId = correlationId,
            replyToQueue = replyToQueue,
            persistence = persistence,
            priority = priority,
            expiryTenths = expiryTenths,
        )
    }

    /**
     * 定时任务入口（`ibm-mq.<connectionId>.scan.messages`，间隔来自连接配置）：扫一次
     * 消息数量 + 通道运行状态。**状态守卫**：非 CONNECTED（未连 / 连接中 / 已失败）
     * 直接跳过 —— 连接失败后任务不注销、靠状态守卫空转，用户重连后自动恢复。
     *
     * 与 [startScan] 共用一把 [scanLock]：定时扫描和用户手动扫描不会同时压到同一条连接上。
     * 业务错误在这里就转成 `*.failed` 事件了 —— 能落到调度器 `JOB_FAILED` 的是「意料之外」
     * 的异常（代码 bug 之类），两者别混为一谈。
     */
    suspend fun scanMessageCounts(connectionId: String) = scanLock.withLock {
        val ctx = connectedContext(connectionId) ?: return@withLock
        try {
            // 真 PCF：INQUIRE_Q_STATUS / INQUIRE_CHANNEL_STATUS 各一条，拿全部
            // 本地队列的当前深度与通道的运行状态（心跳要轻，不查全量属性）。
            // 队列 / 通道列表本身的静态属性由慢扫（scanObjects）维护。
            val counts = objectScanner.scanQueueStatuses(connectionId)
            syncQueueDepths(connectionId, counts)
            val channelStatuses = objectScanner.scanChannelStatuses(connectionId)
            syncChannelStatuses(connectionId, channelStatuses)
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
            // 守卫 Connected：与 connect() 并发时（旧扫描刚失败、新连接刚建立），
            // 不能把刚建好的连接踩成 Failed。
            if (ctx.status == ConnectionStatus.Connected) {
                ctx.lastError = message
                ctx.status = ConnectionStatus.Failed
                ConnectionStore.reportStatus(connectionId, ConnectionStatus.Failed, context.moduleId)
            }
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
            // 导航树二级菜单的数量徽章：与缓存同语义（全量替换该连接的计数）。
            // 口径 = 列表页**默认视图**（隐藏系统对象；队列页还隐藏 AMQ.* 临时队列），
            // 保证「徽章数字 = 点进去看到的行数」。
            IbmMQModule.updateObjectCounts(
                connectionId,
                mapOf(
                    MqObjectType.QUEUE to result.queues.count { !isSystemOrTempQueue(it.name) },
                    MqObjectType.TOPIC to result.topics.count { !isSystemObject(it.name) },
                    MqObjectType.SUBSCRIPTION to result.subscriptions.count { !isSystemObject(it.name) },
                    MqObjectType.CHANNEL to result.channels.count { !isSystemObject(it.name) },
                    MqObjectType.LISTENER to result.listeners.count { !isSystemObject(it.name) },
                    MqObjectType.SERVICE to result.services.count { !isSystemObject(it.name) },
                    MqObjectType.PROCESS to result.processes.count { !isSystemObject(it.name) },
                    MqObjectType.NAMELIST to result.namelists.count { !isSystemObject(it.name) },
                    MqObjectType.AUTH_INFO to result.authInfos.count { !isSystemObject(it.name) },
                    MqObjectType.COMM_INFO to result.commInfos.count { !isSystemObject(it.name) },
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
            // 同样守卫 Connected，避免踩掉并发 connect() 刚建立的新连接。
            IbmMQModule.connectionOrNull(connectionId)?.let { ctx ->
                if (ctx.status == ConnectionStatus.Connected) {
                    ctx.lastError = message
                    ctx.status = ConnectionStatus.Failed
                    ConnectionStore.reportStatus(connectionId, ConnectionStatus.Failed, context.moduleId)
                }
            }
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
        // 真·add-or-update：以既有列表为基础，只更新 / 追加，**绝不删除**没有状态行的对象
        // （INQUIRE_Q_STATUS 覆盖不到别名 / 远程队列等，全量替换会把它们误删；已删除对象
        // 由慢扫全量替换负责）。同名计数多行（理论上不会，但兜底）只取一次。
        val updated = existing.toMutableList()
        val seen = mutableSetOf<String>()
        for ((name, depth) in counts) {
            if (!seen.add(name)) continue
            val idx = updated.indexOfFirst { it.name == name }
            if (idx >= 0) {
                val prev = updated[idx]
                updated[idx] = prev.copy(
                    currentDepth = depth,
                    messageCount = depth,
                    attributes = prev.attributes + ("MQIA_CURRENT_Q_DEPTH" to depth),
                )
            } else {
                updated += MqObjectSnapshot(
                    name = name,
                    type = MqObjectType.QUEUE,
                    currentDepth = depth,
                    messageCount = depth,
                    attributes = mapOf("MQIA_CURRENT_Q_DEPTH" to depth),
                )
            }
        }
        cache.put(key, updated, ttlMs = OBJECTS_FULL_TTL_MS)
    }

    /**
     * 心跳的通道状态同步（add-or-update）：更新 objects.channels 的 channelStatus /
     * attributes[MQIACH_CHANNEL_STATUS]；本次 status 新出现的通道以最小快照追加。
     * 只增改不删（与 [syncQueueDepths] 同理），已删除通道的清理由慢扫全量替换负责。
     */
    private fun syncChannelStatuses(connectionId: String, statuses: List<Pair<String, Int>>) {
        val cache = cacheOf(connectionId) ?: return
        val key = objectsKey(MqObjectType.CHANNEL)
        val existing = cache.getAs<List<MqObjectSnapshot>>(key) ?: emptyList()
        // INQUIRE_CHANNEL_STATUS 一个通道**定义**会返回多行（如 SVRCONN 上多个活动连接），
        // 同名去重只取一次，否则列表里出现重复 name → ObjectCardData.id 重复 → LazyGrid
        // 抛 IllegalArgumentException("Key ... was already used")。
        // 同时是真正的 add-or-update：保留既有通道（停掉的通道没有状态行也不该被删），
        // 只更新已有项的状态、追加本次新出现的通道，绝不整体替换掉慢扫扫到的列表。
        val updated = existing.toMutableList()
        val seen = mutableSetOf<String>()
        for ((name, statusValue) in statuses) {
            if (!seen.add(name)) continue
            val statusName = channelStatusName(statusValue)
            val idx = updated.indexOfFirst { it.name == name }
            if (idx >= 0) {
                val prev = updated[idx]
                updated[idx] = prev.copy(
                    channelStatus = statusName,
                    attributes = prev.attributes + ("MQIACH_CHANNEL_STATUS" to statusValue),
                )
            } else {
                updated += MqObjectSnapshot(
                    name = name,
                    type = MqObjectType.CHANNEL,
                    channelStatus = statusName,
                    attributes = mapOf("MQIACH_CHANNEL_STATUS" to statusValue),
                )
            }
        }
        cache.put(key, updated, ttlMs = OBJECTS_FULL_TTL_MS)
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
    /** 串行化 SSL 测试连接：JSSE 属性是 JVM 全局的，并发测试会互相覆盖（见下）。 */
    private val sslTestMutex = Mutex()

    /**
     * JSSE 系统属性快照：进入 SSL 测试前保存旧值，finally 里恢复——否则测试连接的
     * keystore / 密码会滞留全局属性（后续任何 TLS 代码都能读到），两个不同 SSL 连接
     * 先后测试还会互相覆盖。属性列表与 [sslSystemProperties] 一一对应。
     */
    private val sslSystemProperties = listOf(
        "javax.net.ssl.trustStore",
        "javax.net.ssl.trustStorePassword",
        "javax.net.ssl.trustStoreType",
        "javax.net.ssl.keyStore",
        "javax.net.ssl.keyStorePassword",
        "javax.net.ssl.keyStoreType",
    )

    suspend fun testConnection(config: IbmMQConfig): Unit = withContext(Dispatchers.IO) {
        // SSL 属性（keystore / truststore / cipher 映射）见下方 sslEnabled 分支；
        // 连接属性统一走 [mqProperties]，与正式连接共用同一份组装。
        val properties = mqProperties(config)

        // SSL 只对 CLIENT 模式有意义（BINDING 是同机 JNI，没有 TLS 通道）。
        if (config.mode == ConnectionMode.CLIENT && config.sslEnabled) {
            // 系统属性是 JVM 全局的：串行 + finally 恢复，测试完不留痕。
            sslTestMutex.withLock {
                val previous = sslSystemProperties.associateWith { System.getProperty(it) }
                try {
                    applySslSystemProperties(config)
                    doTestConnection(config, properties)
                } finally {
                    previous.forEach { (key, value) ->
                        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
                    }
                }
            }
        } else {
            doTestConnection(config, properties)
        }
    }

    /** 按 [IbmMQConfig] 写入 JSSE 系统属性（原 testConnection 的 SSL 属性段，逻辑不变）。 */
    private fun applySslSystemProperties(config: IbmMQConfig) {
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

    private suspend fun doTestConnection(
        config: IbmMQConfig,
        properties: java.util.Hashtable<String, Any>,
    ) = withContext(Dispatchers.IO) {
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
     * 查询一条**已连接**连接的队列管理器**全属性快照**（PCF：`MQCMD_INQUIRE_Q_MGR`
     * 全量应答，运行状态由 [PcfObjectScanner] 经 `MQCMD_INQUIRE_Q_MGR_STATUS` 并入
     * 同一快照；后者失败不拖垮整体）。
     *
     * 属性面板（ConnectionPropertiesUi）经它拿数据，分组渲染见 ibm-mq-ui 的
     * QmgrPropertyGroups —— 与队列属性对话框同一套「快照 + 分组表」模式。
     *
     * 未连接时抛 IllegalStateException —— 调用方（属性面板）只会在连接建立后进来；
     * 查询走 [PCFMessageAgent]，它复用 [MQQueueManager] 的既有连接，不另建 TCP。
     */
    suspend fun queryQmgrSnapshot(connectionId: String): MqObjectSnapshot? {
        if (IbmMQModule.connectionOrNull(connectionId) == null) {
            throw IllegalStateException("连接 $connectionId 未建立，无法查询队列管理器属性")
        }
        return objectScanner.scanQueueManager(connectionId)
    }
}

/* ------------------------------ PCF 取值与符号化助手 ------------------------------ */

