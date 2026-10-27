package com.mqclient.ibmmq

import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.cache.CacheManager
import com.mqclient.core.concurrent.KeyedJobRegistry
import com.mqclient.core.concurrent.runCatchingCancellable
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
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    /**
     * PCF 全对象扫描器（每次查询自建 agent，可并发）。
     *
     * 并发约定：本类的**所有** MQI 入口（扫描 / 单对象查询 / 消息收发 / MQSC 由
     * [com.mqclient.ibmmq.command.MqscPcfSender] 负责）都持连接自己的
     * [IbmMQConnectionContext.mqiLock] —— 共享的 `MQQueueManager` 上一条通道内的
     * MQI 请求/应答是有状态串行的，两路并发会互相踩应答。
     *
     * 锁在 service 入口持有一整轮，而**不**是在 [PcfObjectScanner] 的 `withAgent` 里逐次取：
     * `scanAll` 内部 11 类查询仍要并发（每类一个独立 agent），逐次取锁会把慢扫退化成串行。
     */
    private val objectScanner = PcfObjectScanner()

    /**
     * 手动扫描（[startScan]）的协程：`connectionId → 协程`，同一连接只留一条。
     *
     * 为什么按连接登记、而不是共用一个槽位：手动扫描是「刷新这条连接」的动作，不同连接
     * 之间没有任何关系 —— 单槽位时，在 A 上点刷新会把 B 正在跑的刷新取消掉（B 的列表停在
     * 上一次结果，且窗口期内不再收到完成事件）。
     *
     * 断开连接时必须经 [cancelScan] 收掉：扫描协程可能正握着该连接的 `mqiLock`，不取消
     * 的话重连后的第一次刷新要排在它后面等（表现是「点刷新没反应」），而且它手里的 MQ
     * 句柄已经由 [IbmMQConnectionManager] 断掉了。
     */
    private val scanJobs = KeyedJobRegistry()

    /**
     * 需要显式释放的句柄（真实连接 / 会话 / 消费者 / 通道），key 通常用 connectionId。
     *
     * 仓库里还没有真 MQ 依赖，所以现在它是空的；接真库时在建立连接后调
     * [registerResource] 登记，[close] 负责逐个关掉 —— 「谁负责关」永远只有一个答案。
     */
    private val resources = ConcurrentHashMap<String, AutoCloseable>()

    /** 触发一次扫描；同一连接重复调用会取消上一次未完成的扫描。 */
    fun startScan(connectionId: String) {
        ibmMqLog.debug("manual scan requested: connection=$connectionId")
        scanJobs.launch(context.coroutineScope, connectionId) {
            // 显式连接模型：没连上就没有 cache 可写，也没有 MQ 句柄可查。
            val ctx = connectedContext(connectionId)
            if (ctx == null) {
                ibmMqLog.debug("manual scan skipped (not connected): connection=$connectionId")
                return@launch
            }
            ctx.mqiLock.withLock {
                // 等锁期间连接可能已被断开：不要拿废句柄去扫。
                if (ctx.mqConnection == null) {
                    ibmMqLog.debug("manual scan skipped (handle released): connection=$connectionId")
                    return@withLock
                }
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
                    ibmMqLog.debug("manual scan cancelled: connection=$connectionId")
                    throw cancellation
                } catch (error: Throwable) {
                    ibmMqLog.warn(
                        "manual scan failed: connection=$connectionId " +
                            "error=${error.message ?: error::class.simpleName ?: "unknown"}",
                    )
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
    }

    /**
     * 取消某条连接上正在进行的手动扫描（断开 / 删除连接时由
     * [IbmMQConnectionManager] 调用）。只 cancel 不 join，理由见 [KeyedJobRegistry.cancel]。
     */
    fun cancelScan(connectionId: String) {
        scanJobs.cancel(connectionId)
    }

    /** 取消全部手动扫描（模块关闭）。 */
    fun cancelAllScans() {
        scanJobs.cancelAll()
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
        // 保持旧语义：连接未登记 = 查询失败（抛），而不是「状态未知」（null）——
        // null 在轮询里意味着「缓存还没扫到该通道，继续等」，会让一次真实失败
        // 一直轮询到超时、且丢掉原因。
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: throw IllegalStateException("连接 $connectionId 未建立，无法查询通道状态")
        return ctx.mqiLock.withLock { pollChannelStatusLocked(connectionId, name) }
    }

    /** [pollChannelStatus] 的锁内实现：调用方必须已持有该连接的 `mqiLock`。 */
    private suspend fun pollChannelStatusLocked(connectionId: String, name: String): String? {
        val statuses = objectScanner.queryChannelStatus(connectionId, name)
        val cache = cacheOf(connectionId) ?: return null
        val key = objectsKey(MqObjectType.CHANNEL)
        val resolved = statuses.firstOrNull()?.let { channelStatusName(it.second) } ?: "STOPPED"
        val statusValue = statuses.firstOrNull()?.second
        // 原子读-改-写：并发的慢扫 / 心跳在这两步之间可能已经整表替换过，先读后写会把
        // 它们的结果整段覆盖（新扫到的通道凭空消失）。updateIfPresent 同时表达了
        // 「表里还没有这一条」= 不改缓存、也不算写过 —— 与原来的 return null 语义一致。
        val applied = cache.updateIfPresentAs<List<MqObjectSnapshot>>(key, OBJECTS_FULL_TTL_MS) { rows ->
            val idx = rows.indexOfFirst { it.name == name }
            if (idx < 0) {
                rows // 原样返回 = 不动缓存（也不会留下空壳条目）
            } else {
                rows.toMutableList().also { updated ->
                    updated[idx] = rows[idx].copy(
                        channelStatus = resolved,
                        attributes = rows[idx].attributes + ("MQIACH_CHANNEL_STATUS" to (statusValue ?: -1)),
                    )
                }
            }
        }
        if (!applied) {
            ibmMqLog.debug("channel status poll unresolved (not in cache yet): channel=$name")
            return null // 列表尚未扫描到该通道，无法确认状态，交给轮询下一轮
        }
        ibmMqLog.debug("channel status polled: channel=$name status=$resolved")
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
        // 同 pollChannelStatus：连接未登记要抛出，不能退化成「状态未知」。
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: throw IllegalStateException("连接 $connectionId 未建立，无法查询监听器状态")
        return ctx.mqiLock.withLock { pollListenerStatusLocked(connectionId, name) }
    }

    /** [pollListenerStatus] 的锁内实现：调用方必须已持有该连接的 `mqiLock`。 */
    private suspend fun pollListenerStatusLocked(connectionId: String, name: String): String? {
        val statuses = objectScanner.queryListenerStatus(connectionId, name)
        val cache = cacheOf(connectionId) ?: return null
        val key = objectsKey(MqObjectType.LISTENER)
        val resolved = statuses.firstOrNull()?.let { listenerStatusName(it.second) } ?: "NOT_RUNNING"
        val statusValue = statuses.firstOrNull()?.second
        // 原子读-改-写，理由同 [pollChannelStatus]：先读后写会覆盖并发扫描的结果。
        val applied = cache.updateIfPresentAs<List<MqObjectSnapshot>>(key, OBJECTS_FULL_TTL_MS) { rows ->
            val idx = rows.indexOfFirst { it.name == name }
            if (idx < 0) {
                rows // 原样返回 = 不动缓存
            } else {
                rows.toMutableList().also { updated ->
                    updated[idx] = rows[idx].copy(
                        listenerStatus = resolved,
                        attributes = rows[idx].attributes + ("MQIACH_LISTENER_STATUS" to (statusValue ?: -1)),
                    )
                }
            }
        }
        if (!applied) {
            ibmMqLog.debug("listener status poll unresolved (not in cache yet): listener=$name")
            return null // 列表尚未扫描到该监听器，无法确认状态，交给轮询下一轮
        }
        ibmMqLog.debug("listener status polled: listener=$name status=$resolved")
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
    suspend fun getChannelStatus(connectionId: String, name: String): ChannelStatusDetail? {
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: throw IllegalStateException("连接 $connectionId 未建立，无法查询通道状态")
        return ctx.mqiLock.withLock { objectScanner.queryChannelStatusDetail(connectionId, name) }
    }

    /**
     * 打开队列消息浏览会话（消息处理对话框用）：在该连接的 [MQQueueManager] 上以
     * `MQOO_BROWSE` 打开队列，光标跨调用保持，浏览不消费消息。连接未建立直接抛
     * [IllegalStateException]，由 UI 转「加载失败」。
     *
     * 打开动作本身就持 `mqiLock`（与 PCF 扫描 / 发送串行），并把同一把锁交给会话，
     * 后续每页 [MessageBrowseSession.browseNext] 继续用它。
     */
    suspend fun openBrowseSession(connectionId: String, queueName: String): MessageBrowseSession {
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: run {
                ibmMqLog.warn("browse session aborted (not connected): connection=$connectionId queue=$queueName")
                throw IllegalStateException("连接 $connectionId 未建立，无法浏览队列消息")
            }
        return ctx.mqiLock.withLock {
            val manager = ctx.mqConnection
                ?: run {
                    ibmMqLog.warn("browse session aborted (handle released): connection=$connectionId queue=$queueName")
                    throw IllegalStateException("连接 $connectionId 未建立，无法浏览队列消息")
                }
            MessageBrowseSession.open(manager, queueName, ctx.mqiLock)
        }
    }

    /** 单队列实时 CURDEPTH（PCF INQUIRE_Q 只点名 MQIA_CURRENT_Q_DEPTH，不读缓存）。 */
    suspend fun getQueueDepth(connectionId: String, queueName: String): Int {
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: throw IllegalStateException("连接 $connectionId 未建立，无法查询队列深度")
        return ctx.mqiLock.withLock { objectScanner.queryQueueDepth(connectionId, queueName) }
    }

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
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: throw IllegalStateException("连接 $connectionId 未建立，无法查询集群关系")
        return ctx.mqiLock.withLock { getClusterRelationsLocked(connectionId) }
    }

    /** [getClusterRelations] 的锁内实现：调用方必须已持有该连接的 `mqiLock`。 */
    private suspend fun getClusterRelationsLocked(connectionId: String): ClusterRelations {
        val relations = objectScanner.queryClusterRelations(connectionId)
        // 可取消版：scanChannelStatuses 是挂起调用。切走集群 Tab 时的取消不能被当成
        // 「状态查不到」吞掉 —— 那样函数会继续跑完并返回「状态全是 -」的结果。
        val statusByName = runCatchingCancellable { objectScanner.scanChannelStatuses(connectionId) }
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
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: run {
                ibmMqLog.warn("msg download aborted (not connected): connection=$connectionId queue=$queueName")
                return MessageOperationResult.failure(null, "连接 $connectionId 未建立")
            }
        return ctx.mqiLock.withLock {
            // 等锁期间连接可能已被断开：锁内再读一次句柄，不拿废句柄去 browse。
            val manager = ctx.mqConnection
                ?: return@withLock MessageOperationResult.failure(null, "连接 $connectionId 未建立")
            downloadMessageBody(manager, queueName, messageIdHex)
        }
    }

    /**
     * 按 MessageId 取消息详情（详情对话框）：独立 browse 句柄扫到匹配条目，
     * 读完整 MQMD + 消息体组装 [MessageDetail]。纯浏览不消费；连接未建立返回 failure。
     */
    suspend fun getMessageDetail(connectionId: String, queueName: String, messageIdHex: String): MessageOperationResult {
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: run {
                ibmMqLog.warn("msg detail aborted (not connected): connection=$connectionId queue=$queueName")
                return MessageOperationResult.failure(null, "连接 $connectionId 未建立")
            }
        return ctx.mqiLock.withLock {
            val manager = ctx.mqConnection
                ?: return@withLock MessageOperationResult.failure(null, "连接 $connectionId 未建立")
            getMessageDetail(manager, queueName, messageIdHex)
        }
    }

    /**
     * 按 MessageId 删除（消费）一条消息（浏览 Tab「删除」）：browse 定位 +
     * MQGMO_MSG_UNDER_CURSOR 破坏性 get，只消费该条；删除后由调用方刷新列表。
     */
    suspend fun deleteMessage(connectionId: String, queueName: String, messageIdHex: String): MessageOperationResult {
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: run {
                ibmMqLog.warn("msg delete aborted (not connected): connection=$connectionId queue=$queueName")
                return MessageOperationResult.failure(null, "连接 $connectionId 未建立")
            }
        return ctx.mqiLock.withLock {
            val manager = ctx.mqConnection
                ?: return@withLock MessageOperationResult.failure(null, "连接 $connectionId 未建立")
            deleteMessageById(manager, queueName, messageIdHex)
        }
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
        correlationIdBytes: ByteArray? = null,
        characterSet: Int? = null,
        format: String? = null,
        replyToQMgrName: String? = null,
    ): SendResult {
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: run {
                ibmMqLog.warn("send aborted (not connected): connection=$connectionId queue=$queueName")
                return SendResult.failure(null, "连接 $connectionId 未建立，无法发送消息")
            }
        return ctx.mqiLock.withLock {
            val manager = ctx.mqConnection
                ?: return@withLock SendResult.failure(null, "连接 $connectionId 未建立，无法发送消息")
            sendMessageToQueue(
                manager = manager,
                queueName = queueName,
                content = content,
                correlationId = correlationId,
                replyToQueue = replyToQueue,
                persistence = persistence,
                priority = priority,
                expiryTenths = expiryTenths,
                correlationIdBytes = correlationIdBytes,
                characterSet = characterSet,
                format = format,
                replyToQMgrName = replyToQMgrName,
            )
        }
    }

    /**
     * 定时任务入口（`ibm-mq.<connectionId>.scan.messages`，间隔来自连接配置）：扫一次
     * 消息数量 + 通道运行状态。**状态守卫**：非 CONNECTED（未连 / 连接中 / 已失败）
     * 直接跳过 —— 连接失败后任务不注销、靠状态守卫空转，用户重连后自动恢复。
     *
     * 与 [startScan] 共用同一条连接的 `mqiLock`：定时扫描和用户手动扫描不会同时压到同一条连接上，
     * 也不会与消息浏览 / 发送交错。
     * 业务错误在这里就转成 `*.failed` 事件了 —— 能落到调度器 `JOB_FAILED` 的是「意料之外」
     * 的异常（代码 bug 之类），两者别混为一谈。
     */
    suspend fun scanMessageCounts(connectionId: String) {
        val ctx = connectedContext(connectionId) ?: return
        ctx.mqiLock.withLock {
            // 等锁期间连接可能已被断开（mqConnection 置 null）：不要拿废句柄去扫。
            if (ctx.mqConnection == null) return@withLock
            scanMessageCountsLocked(connectionId)
        }
    }

    /** [scanMessageCounts] 的锁内实现：调用方必须已持有该连接的 `mqiLock`。 */
    private suspend fun scanMessageCountsLocked(connectionId: String) {
        val ctx = connectedContext(connectionId) ?: return
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
            ibmMqLog.warn("heartbeat scan failed: connection=$connectionId error=$message")
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
                ibmMqLog.warn("connection marked FAILED after heartbeat scan error: connection=$connectionId")
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
    suspend fun scanObjects(connectionId: String) {
        val ctx = connectedContext(connectionId) ?: return
        ctx.mqiLock.withLock {
            // 等锁期间连接可能已被断开（mqConnection 置 null）：不要拿废句柄去扫。
            if (ctx.mqConnection == null) return@withLock
            scanObjectsLocked(connectionId)
        }
    }

    /** [scanObjects] 的锁内实现：调用方必须已持有该连接的 `mqiLock`。 */
    private suspend fun scanObjectsLocked(connectionId: String) {
        try {
            val result = objectScanner.scanAll(connectionId)

            val cache = cacheOf(connectionId)
            // 只写**本轮真的扫到了**的类型：预算耗尽 / 查询失败的类型一律跳过。
            // 把空列表写进 `objects.<type>` 等于告诉 UI「这个 QM 上没有对象」，
            // 导航树会被整片清空 —— 而真相是「这次没读到」。保留上一轮的结果更诚实，
            // 加上 partial=true 让界面能标「部分刷新」。
            fun putIfScanned(type: MqObjectType, value: Any?) {
                if (type in result.failedTypes) return
                cache?.put(objectsKey(type), value, ttlMs = OBJECTS_FULL_TTL_MS)
            }
            putIfScanned(MqObjectType.QUEUE_MANAGER, result.queueManager)
            putIfScanned(MqObjectType.QUEUE, result.queues)
            putIfScanned(MqObjectType.CHANNEL, result.channels)
            putIfScanned(MqObjectType.TOPIC, result.topics)
            putIfScanned(MqObjectType.SUBSCRIPTION, result.subscriptions)
            putIfScanned(MqObjectType.LISTENER, result.listeners)
            putIfScanned(MqObjectType.SERVICE, result.services)
            putIfScanned(MqObjectType.PROCESS, result.processes)
            putIfScanned(MqObjectType.NAMELIST, result.namelists)
            putIfScanned(MqObjectType.AUTH_INFO, result.authInfos)
            putIfScanned(MqObjectType.COMM_INFO, result.commInfos)

            // 把「哪几类没取到」交给模块存起来：UI 的提示条要能在页面切来切去后
            // 仍然知道「这份数据是残的」，而事件是瞬时的。
            IbmMQModule.reportScanFailures(connectionId, result.failedTypes)

            context.emit(
                name = IbmMQEvents.OBJECTS_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    // 部分结果：UI 据此显示「部分刷新（超时）」而不是当完整刷新。
                    "partial" to result.partial,
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
            // 导航树二级菜单的数量徽章：按类型覆盖（**跳过失败的类型**）—— 否则一次
            // 超时会把徽章清零（树里全是 0，点进去却是上一轮的数据）。口径 = 列表页
            // **默认视图**（隐藏系统对象；队列页还隐藏 AMQ.* 临时队列），
            // 保证「徽章数字 = 点进去看到的行数」。
            IbmMQModule.mergeObjectCounts(
                connectionId,
                buildMap {
                    fun count(type: MqObjectType, n: Int) {
                        if (type !in result.failedTypes) put(type, n)
                    }
                    count(MqObjectType.QUEUE, result.queues.count { !isSystemOrTempQueue(it.name) })
                    count(MqObjectType.TOPIC, result.topics.count { !isSystemObject(it.name) })
                    count(MqObjectType.SUBSCRIPTION, result.subscriptions.count { !isSystemObject(it.name) })
                    count(MqObjectType.CHANNEL, result.channels.count { !isSystemObject(it.name) })
                    count(MqObjectType.LISTENER, result.listeners.count { !isSystemObject(it.name) })
                    count(MqObjectType.SERVICE, result.services.count { !isSystemObject(it.name) })
                    count(MqObjectType.PROCESS, result.processes.count { !isSystemObject(it.name) })
                    count(MqObjectType.NAMELIST, result.namelists.count { !isSystemObject(it.name) })
                    count(MqObjectType.AUTH_INFO, result.authInfos.count { !isSystemObject(it.name) })
                    count(MqObjectType.COMM_INFO, result.commInfos.count { !isSystemObject(it.name) })
                },
            )
            ibmMqLog.info(
                "objects scanned: connection=$connectionId queues=${result.queues.size} " +
                    "channels=${result.channels.size} topics=${result.topics.size} " +
                    "subscriptions=${result.subscriptions.size} listeners=${result.listeners.size} " +
                    "services=${result.services.size} processes=${result.processes.size} " +
                    "namelists=${result.namelists.size} authInfos=${result.authInfos.size} " +
                    "commInfos=${result.commInfos.size} " +
                    "partial=${result.partial} failed=${result.failedTypes}",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val message = error.message ?: error::class.simpleName ?: "unknown"
            ibmMqLog.error("objects scan failed: connection=$connectionId error=$message")
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
                    ibmMqLog.warn("connection marked FAILED after objects scan error: connection=$connectionId")
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
        // 原子读-改-写：心跳与慢扫 / 手动刷新、通道轮询是并发的，「先读整表 → 改 → 写回」
        // 会让后写的那次抹掉先写的结果（新队列凭空消失，直到下轮慢扫）。
        // 真·add-or-update：以既有列表为基础，只更新 / 追加，**绝不删除**没有状态行的对象
        // （INQUIRE_Q_STATUS 覆盖不到别名 / 远程队列等，全量替换会把它们误删；已删除对象
        // 由慢扫全量替换负责）。同名计数多行（理论上不会，但兜底）只取一次。
        //
        // transform 会被 CAS 重试，所以它只做纯计算：外部可见的副作用（日志 / 事件）
        // 一律留在外面。
        cache.updateAs<List<MqObjectSnapshot>>(key, OBJECTS_FULL_TTL_MS) { current ->
            val updated = (current ?: emptyList()).toMutableList()
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
            updated.toList()
        }
    }

    /**
     * 心跳的通道状态同步（add-or-update）：更新 objects.channels 的 channelStatus /
     * attributes[MQIACH_CHANNEL_STATUS]；本次 status 新出现的通道以最小快照追加。
     * 只增改不删（与 [syncQueueDepths] 同理），已删除通道的清理由慢扫全量替换负责。
     */
    private fun syncChannelStatuses(connectionId: String, statuses: List<Pair<String, Int>>) {
        val cache = cacheOf(connectionId) ?: return
        val key = objectsKey(MqObjectType.CHANNEL)
        // INQUIRE_CHANNEL_STATUS 一个通道**定义**会返回多行（如 SVRCONN 上多个活动连接），
        // 同名去重只取一次，否则列表里出现重复 name → ObjectCardData.id 重复 → LazyGrid
        // 抛 IllegalArgumentException("Key ... was already used")。
        // 同时是真正的 add-or-update：保留既有通道（停掉的通道没有状态行也不该被删），
        // 只更新已有项的状态、追加本次新出现的通道，绝不整体替换掉慢扫扫到的列表。
        // 原子读-改-写：理由同 [syncQueueDepths]（并发扫描会互相覆盖）。
        cache.updateAs<List<MqObjectSnapshot>>(key, OBJECTS_FULL_TTL_MS) { current ->
            val updated = (current ?: emptyList()).toMutableList()
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
            updated.toList()
        }
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
        ibmMqLog.debug("service closing: resources=${resources.size}")
        cancelAllScans()
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
            val start = System.currentTimeMillis()
            val manager = try {
                MQQueueManager(config.queueManager.trim(), properties)
            } catch (error: Throwable) {
                ibmMqLog.warn(
                    "test connection failed: qmgr=${config.queueManager} " +
                        "error=${error.message ?: error::class.simpleName ?: "unknown"} " +
                        "duration=${System.currentTimeMillis() - start}ms",
                )
                throw error
            }
            try {
                // 连上即视为可达；不做更深的访问（列表 / 收发归正式连接的扫描链路）。
                ibmMqLog.info("test connection ok: qmgr=${config.queueManager} duration=${System.currentTimeMillis() - start}ms")
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
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: throw IllegalStateException("连接 $connectionId 未建立，无法查询队列管理器属性")
        return ctx.mqiLock.withLock { objectScanner.scanQueueManager(connectionId) }
    }
}

/* ------------------------------ PCF 取值与符号化助手 ------------------------------ */

