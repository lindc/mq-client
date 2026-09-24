package com.mqclient.ibmmq.pcf

import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import com.ibm.mq.pcf.PCFException
import com.ibm.mq.pcf.PCFMessage
import com.ibm.mq.pcf.PCFMessageAgent
import com.ibm.mq.pcf.PCFParameter
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.ibmMqLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 单类对象查询的超时（60 秒；慢 QMGR / 大通配符结果也要在它之内给说法）。 */
private const val SCAN_TIMEOUT_MS = 60_000L

/** MQRC_NOT_AUTHORIZED：无权限的类查不了 —— 跳过，不影响其他类。 */
private const val MQRC_NOT_AUTHORIZED = 2035

/**
 * 一轮全对象扫描的结果：11 类对象各自的快照列表。
 * 失败的类型是空列表（[PcfObjectScanner.scanAll] 内部已兜住并记日志）。
 */
data class ScanResult(
    val queueManager: MqObjectSnapshot?,
    val queues: List<MqObjectSnapshot>,
    val channels: List<MqObjectSnapshot>,
    val topics: List<MqObjectSnapshot>,
    val subscriptions: List<MqObjectSnapshot>,
    val listeners: List<MqObjectSnapshot>,
    val services: List<MqObjectSnapshot>,
    val processes: List<MqObjectSnapshot>,
    val namelists: List<MqObjectSnapshot>,
    val authInfos: List<MqObjectSnapshot>,
    val commInfos: List<MqObjectSnapshot>,
)

/**
 * PCF 全对象扫描器：对 11 类 MQ 对象发 `MQCMD_INQUIRE_*`（通配符 "*"），把
 * 属性响应与状态响应合并成 [MqObjectSnapshot]。
 *
 * 连接姿势与 [com.mqclient.ibmmq.command.MqscPcfSender] 同一模式：[PCFMessageAgent]
 * 包在既有 [MQQueueManager] 上复用 TCP 通道，用完即断（manager 归
 * IbmMQConnectionManager 管，这里绝不碰）；每次查询新建 agent —— 它不保证线程
 * 安全，按类型并发的 async 各持一个 agent 互不干扰。
 *
 * 失败语义：单类查询方法**抛异常**（心跳链路需要靠异常上报连接失败）；[scanAll]
 * 内部逐类兜住（无权限 2035 / 超时 / 其他错误都跳过该类记日志），不会让一个类拖垮
 * 整轮扫描。`*With(agent)` / `scanAllWith(manager)` 变体直接在给定连接上执行，
 * 供真机验证与测试使用。
 */
class PcfObjectScanner {

    /* --------------------------- 对外：单类查询（抛异常） --------------------------- */

    /** 队列管理器自身属性（单条响应；理论上恒有且只有一条）。 */
    suspend fun scanQueueManager(connectionId: String): MqObjectSnapshot? =
        withAgent(connectionId) { scanQueueManagerWith(it) }

    internal suspend fun scanQueueManagerWith(agent: PCFMessageAgent): MqObjectSnapshot? {
        // 注意：INQUIRE_Q_MGR 的 QMgrName **不允许通配符**（带 "*" 回 3097
        // MQRCCF_Q_MGR_NAME_ERROR）—— 省略参数即返回本连接的队列管理器。
        val response = inquire(agent, MQConstants.MQCMD_INQUIRE_Q_MGR) {
            // 无参数
        }.firstOrNull() ?: return null
        return response.toSnapshot(MqObjectType.QUEUE_MANAGER, MQConstants.MQCA_Q_MGR_NAME)
    }

    /**
     * 所有队列：INQUIRE_Q（静态属性）+ INQUIRE_Q_STATUS（深度 / 句柄数）合并。
     * 只有静态属性、没有状态条目的队列（如远程队列）照常收录，动态字段为 null。
     */
    suspend fun scanQueues(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanQueuesWith(it) }

    internal suspend fun scanQueuesWith(agent: PCFMessageAgent): List<MqObjectSnapshot> {
        val attributes = inquire(agent, MQConstants.MQCMD_INQUIRE_Q) {
            addParameter(MQConstants.MQCA_Q_NAME, "*")
            addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_ALL)
        }
        val statuses = inquire(agent, MQConstants.MQCMD_INQUIRE_Q_STATUS) {
            addParameter(MQConstants.MQCA_Q_NAME, "*")
        }.associateBy { it.stringValue(MQConstants.MQCA_Q_NAME) }

        return attributes.mapNotNull { response ->
            val status = statuses[response.stringValue(MQConstants.MQCA_Q_NAME)]
            response.toQueueSnapshot(status)
        }
    }

    /**
     * 队列状态快扫（心跳用）：只发 INQUIRE_Q_STATUS 一条，取名字 + 当前深度。
     * 不做属性合并 —— 心跳要的是轻。
     */
    suspend fun scanQueueStatuses(connectionId: String): List<Pair<String, Int>> =
        withAgent(connectionId) { scanQueueStatusesWith(it) }

    internal suspend fun scanQueueStatusesWith(agent: PCFMessageAgent): List<Pair<String, Int>> {
        val responses = inquire(agent, MQConstants.MQCMD_INQUIRE_Q_STATUS) {
            addParameter(MQConstants.MQCA_Q_NAME, "*")
        }
        return responses.mapNotNull { response ->
            val name = response.stringValue(MQConstants.MQCA_Q_NAME) ?: return@mapNotNull null
            val depth = response.intValue(MQConstants.MQIA_CURRENT_Q_DEPTH) ?: return@mapNotNull null
            name to depth
        }
    }

    /**
     * 通道状态快扫（心跳用）：只发 INQUIRE_CHANNEL_STATUS 一条，取名字 + 运行状态
     * 原始值（MQIACH_CHANNEL_STATUS，MQCHS_*；可读名用 [channelStatusName] 转）。
     */
    suspend fun scanChannelStatuses(connectionId: String): List<Pair<String, Int>> =
        withAgent(connectionId) { scanChannelStatusesWith(it) }

    internal suspend fun scanChannelStatusesWith(agent: PCFMessageAgent): List<Pair<String, Int>> {
        val responses = inquire(agent, MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS) {
            addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*")
        }
        return responses.mapNotNull { response ->
            val name = response.stringValue(MQConstants.MQCACH_CHANNEL_NAME) ?: return@mapNotNull null
            val status = response.intValue(MQConstants.MQIACH_CHANNEL_STATUS) ?: return@mapNotNull null
            name to status
        }
    }

    /** 所有通道：INQUIRE_CHANNEL + INQUIRE_CHANNEL_STATUS（运行状态）合并。 */
    suspend fun scanChannels(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanChannelsWith(it) }

    internal suspend fun scanChannelsWith(agent: PCFMessageAgent): List<MqObjectSnapshot> {
        val attributes = inquire(agent, MQConstants.MQCMD_INQUIRE_CHANNEL) {
            addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*")
        }
        val statuses = inquire(agent, MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS) {
            addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*")
        }.associateBy { it.stringValue(MQConstants.MQCACH_CHANNEL_NAME) }

        return attributes.mapNotNull { response ->
            val status = statuses[response.stringValue(MQConstants.MQCACH_CHANNEL_NAME)]
            response.toSnapshot(MqObjectType.CHANNEL, MQConstants.MQCACH_CHANNEL_NAME, status).copy(
                channelStatus = status?.let { channelStatusName(it.intValue(MQConstants.MQIACH_CHANNEL_STATUS)) },
                channelType = channelTypeName(response.intValue(MQConstants.MQIACH_CHANNEL_TYPE)),
            )
        }
    }

    /** 所有主题定义。 */
    suspend fun scanTopics(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanTopicsWith(it) }

    internal suspend fun scanTopicsWith(agent: PCFMessageAgent): List<MqObjectSnapshot> {
        val responses = inquire(agent, MQConstants.MQCMD_INQUIRE_TOPIC) {
            addParameter(MQConstants.MQCA_TOPIC_NAME, "*")
        }
        return responses.mapNotNull { it.toSnapshotOrNull(MqObjectType.TOPIC, MQConstants.MQCA_TOPIC_NAME) }
    }

    /** 所有订阅（有名字的；系统内部匿名订阅不在 INQUIRE_SUBSCRIPTION 通配范围内）。 */
    suspend fun scanSubscriptions(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanSubscriptionsWith(it) }

    internal suspend fun scanSubscriptionsWith(agent: PCFMessageAgent): List<MqObjectSnapshot> {
        val responses = inquire(agent, MQConstants.MQCMD_INQUIRE_SUBSCRIPTION) {
            addParameter(MQConstants.MQCACF_SUB_NAME, "*")
        }
        return responses.mapNotNull { it.toSnapshotOrNull(MqObjectType.SUBSCRIPTION, MQConstants.MQCACF_SUB_NAME) }
    }

    /** 所有监听器：INQUIRE_LISTENER + INQUIRE_LISTENER_STATUS（运行状态 / 端口）合并。 */
    suspend fun scanListeners(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanListenersWith(it) }

    internal suspend fun scanListenersWith(agent: PCFMessageAgent): List<MqObjectSnapshot> {
        val attributes = inquire(agent, MQConstants.MQCMD_INQUIRE_LISTENER) {
            addParameter(MQConstants.MQCACH_LISTENER_NAME, "*")
        }
        val statuses = inquire(agent, MQConstants.MQCMD_INQUIRE_LISTENER_STATUS) {
            addParameter(MQConstants.MQCACH_LISTENER_NAME, "*")
        }.associateBy { it.stringValue(MQConstants.MQCACH_LISTENER_NAME) }

        return attributes.mapNotNull { response ->
            val status = statuses[response.stringValue(MQConstants.MQCACH_LISTENER_NAME)]
            response.toSnapshot(MqObjectType.LISTENER, MQConstants.MQCACH_LISTENER_NAME, status).copy(
                listenerStatus = status?.let { listenerStatusName(it.intValue(MQConstants.MQIACH_LISTENER_STATUS)) },
                port = status?.intValue(MQConstants.MQIACH_PORT)
                    ?: response.intValue(MQConstants.MQIACH_PORT),
            )
        }
    }

    /** 所有服务定义。 */
    suspend fun scanServices(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanServicesWith(it) }

    internal suspend fun scanServicesWith(agent: PCFMessageAgent): List<MqObjectSnapshot> {
        val responses = inquire(agent, MQConstants.MQCMD_INQUIRE_SERVICE) {
            addParameter(MQConstants.MQCA_SERVICE_NAME, "*")
        }
        return responses.mapNotNull { it.toSnapshotOrNull(MqObjectType.SERVICE, MQConstants.MQCA_SERVICE_NAME) }
    }

    /** 所有进程定义。 */
    suspend fun scanProcesses(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanProcessesWith(it) }

    internal suspend fun scanProcessesWith(agent: PCFMessageAgent): List<MqObjectSnapshot> {
        val responses = inquire(agent, MQConstants.MQCMD_INQUIRE_PROCESS) {
            addParameter(MQConstants.MQCA_PROCESS_NAME, "*")
        }
        return responses.mapNotNull { it.toSnapshotOrNull(MqObjectType.PROCESS, MQConstants.MQCA_PROCESS_NAME) }
    }

    /** 所有名称列表。 */
    suspend fun scanNamelists(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanNamelistsWith(it) }

    internal suspend fun scanNamelistsWith(agent: PCFMessageAgent): List<MqObjectSnapshot> {
        val responses = inquire(agent, MQConstants.MQCMD_INQUIRE_NAMELIST) {
            addParameter(MQConstants.MQCA_NAMELIST_NAME, "*")
        }
        return responses.mapNotNull { it.toSnapshotOrNull(MqObjectType.NAMELIST, MQConstants.MQCA_NAMELIST_NAME) }
    }

    /** 所有认证信息对象。 */
    suspend fun scanAuthInfos(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanAuthInfosWith(it) }

    internal suspend fun scanAuthInfosWith(agent: PCFMessageAgent): List<MqObjectSnapshot> {
        val responses = inquire(agent, MQConstants.MQCMD_INQUIRE_AUTH_INFO) {
            addParameter(MQConstants.MQCA_AUTH_INFO_NAME, "*")
        }
        return responses.mapNotNull { it.toSnapshotOrNull(MqObjectType.AUTH_INFO, MQConstants.MQCA_AUTH_INFO_NAME) }
    }

    /** 所有通信信息对象。 */
    suspend fun scanCommInfos(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanCommInfosWith(it) }

    internal suspend fun scanCommInfosWith(agent: PCFMessageAgent): List<MqObjectSnapshot> {
        val responses = inquire(agent, MQConstants.MQCMD_INQUIRE_COMM_INFO) {
            addParameter(MQConstants.MQCA_COMM_INFO_NAME, "*")
        }
        return responses.mapNotNull { it.toSnapshotOrNull(MqObjectType.COMM_INFO, MQConstants.MQCA_COMM_INFO_NAME) }
    }

    /* --------------------------- 对外：全量扫描（逐类兜底） --------------------------- */

    /**
     * 一轮扫齐 11 类对象：各类**并发**查询（各自独立 agent），单类失败（无权限 /
     * 超时 / 其他）只记日志、该类返回空结果，不拖垮整轮；取消（应用退出）原样上抛。
     */
    suspend fun scanAll(connectionId: String): ScanResult = coroutineScope {
        val queueManagerDeferred = async { safelyNullable("qmgr") { scanQueueManager(connectionId) } }
        val queuesDeferred = async { safelyList("queues") { scanQueues(connectionId) } }
        val channelsDeferred = async { safelyList("channels") { scanChannels(connectionId) } }
        val topicsDeferred = async { safelyList("topics") { scanTopics(connectionId) } }
        val subscriptionsDeferred = async { safelyList("subscriptions") { scanSubscriptions(connectionId) } }
        val listenersDeferred = async { safelyList("listeners") { scanListeners(connectionId) } }
        val servicesDeferred = async { safelyList("services") { scanServices(connectionId) } }
        val processesDeferred = async { safelyList("processes") { scanProcesses(connectionId) } }
        val namelistsDeferred = async { safelyList("namelists") { scanNamelists(connectionId) } }
        val authInfosDeferred = async { safelyList("authInfos") { scanAuthInfos(connectionId) } }
        val commInfosDeferred = async { safelyList("commInfos") { scanCommInfos(connectionId) } }

        ScanResult(
            queueManager = queueManagerDeferred.await(),
            queues = queuesDeferred.await(),
            channels = channelsDeferred.await(),
            topics = topicsDeferred.await(),
            subscriptions = subscriptionsDeferred.await(),
            listeners = listenersDeferred.await(),
            services = servicesDeferred.await(),
            processes = processesDeferred.await(),
            namelists = namelistsDeferred.await(),
            authInfos = authInfosDeferred.await(),
            commInfos = commInfosDeferred.await(),
        )
    }

    /**
     * [scanAll] 的「给定 manager」串行变体（真机验证 / 工具用）：一条连接开一个
     * agent 逐类查询，逐类兜底语义与 [scanAll] 相同。
     */
    internal suspend fun scanAllWith(manager: MQQueueManager): ScanResult = withContext(Dispatchers.IO) {
        val agent = PCFMessageAgent(manager)
        try {
            ScanResult(
                queueManager = safelyNullable("qmgr") { scanQueueManagerWith(agent) },
                queues = safelyList("queues") { scanQueuesWith(agent) },
                channels = safelyList("channels") { scanChannelsWith(agent) },
                topics = safelyList("topics") { scanTopicsWith(agent) },
                subscriptions = safelyList("subscriptions") { scanSubscriptionsWith(agent) },
                listeners = safelyList("listeners") { scanListenersWith(agent) },
                services = safelyList("services") { scanServicesWith(agent) },
                processes = safelyList("processes") { scanProcessesWith(agent) },
                namelists = safelyList("namelists") { scanNamelistsWith(agent) },
                authInfos = safelyList("authInfos") { scanAuthInfosWith(agent) },
                commInfos = safelyList("commInfos") { scanCommInfosWith(agent) },
            )
        } finally {
            runCatching { agent.disconnect() }
        }
    }

    /* ------------------------------ 内部：兜底 / 连接 / 查询 ------------------------------ */

    /** 列表类查询的兜底：任何失败 → 空列表（取消除外）。 */
    private suspend fun <T> safelyList(name: String, block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PCFException) {
        logScanFailure(name, e)
        emptyList()
    } catch (e: Exception) {
        ibmMqLog.error("pcf scan [$name] failed: ${e.message}")
        emptyList()
    }

    /** 单对象查询（QMGR）的兜底：任何失败 → null。 */
    private suspend fun safelyNullable(name: String, block: suspend () -> MqObjectSnapshot?): MqObjectSnapshot? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PCFException) {
        logScanFailure(name, e)
        null
    } catch (e: Exception) {
        ibmMqLog.error("pcf scan [$name] failed: ${e.message}")
        null
    }

    /** 扫描失败分级记日志：2035 无权限是预期内的 warn，其余按 error 全量留痕。 */
    private fun logScanFailure(name: String, e: PCFException) {
        if (e.reasonCode == MQRC_NOT_AUTHORIZED) {
            ibmMqLog.warn("pcf scan [$name]: not authorized (2035), skipped")
        } else {
            ibmMqLog.error("pcf scan [$name] failed: reasonCode=${e.reasonCode} message=${e.message}")
        }
    }

    /**
     * 取该连接的 MQQueueManager，在其上开 PCFMessageAgent 执行 [block]，
     * 整体套 60s 超时（超时转成普通异常 —— 心跳链路靠异常上报失败，不能让它
     * 以「取消」的形状逃出去）。agent 用完即断。
     */
    private suspend fun <T> withAgent(connectionId: String, block: suspend (PCFMessageAgent) -> T): T {
        val manager: MQQueueManager = IbmMQModule.connectionOrNull(connectionId)?.mqConnection
            ?: throw IllegalStateException("连接 $connectionId 未建立，无法执行 PCF 查询")
        return try {
            withTimeout(SCAN_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val agent = PCFMessageAgent(manager)
                    try {
                        block(agent)
                    } finally {
                        runCatching { agent.disconnect() }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("PCF 查询超时（${SCAN_TIMEOUT_MS / 1000}s）", e)
        }
    }

    /** 发一条 INQUIRE 请求，返回响应消息列表（完成码非 OK 时 send 抛 PCFException）。 */
    private fun inquire(
        agent: PCFMessageAgent,
        command: Int,
        parameters: PCFMessage.() -> Unit,
    ): Array<out PCFMessage> {
        val request = PCFMessage(command).apply(parameters)
        ibmMqLog.debug("pcf inquire: cmd=$command")
        return agent.send(request)
    }
}

/* ------------------------------ 纯转换逻辑（internal 供单测） ------------------------------ */

/** 全量属性提取：参数 ID 经 [MqAttributeNameMapper] 转可读名，byte[] 解码成串。 */
internal fun extractAttributes(response: PCFMessage): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>()
    val parameters = response.getParameters()
    while (parameters.hasMoreElements()) {
        val param = parameters.nextElement() as? PCFParameter ?: continue
        result[MqAttributeNameMapper.getName(param.parameter)] = readableValue(param.value)
    }
    return result
}

/** PCF 值转可读形态：byte[]（字符串类参数的底层形态）解码去 NUL，数组拼串。 */
internal fun readableValue(value: Any?): Any? = when (value) {
    null -> null
    is ByteArray -> value.decodeToString().trimEnd('\u0000')
    is Array<*> -> value.joinToString(", ") { readableValue(it)?.toString().orEmpty() }
    else -> value
}

/** 取字符串参数（兼容 String / ByteArray 两种底层形态）。 */
internal fun PCFMessage.stringValue(selector: Int): String? = when (val value = getParameterValue(selector)) {
    null -> null
    is String -> value.trimEnd('\u0000')
    is ByteArray -> value.decodeToString().trimEnd('\u0000')
    else -> value.toString()
}

/** 取整型参数（非 Int 一律 null，调用方用 null 语义兜底）。 */
internal fun PCFMessage.intValue(selector: Int): Int? = getParameterValue(selector) as? Int

/** 通用快照：全量属性 + 名字 + 描述；状态响应的参数覆盖同名属性键。 */
internal fun PCFMessage.toSnapshot(
    type: MqObjectType,
    nameSelector: Int,
    status: PCFMessage? = null,
): MqObjectSnapshot {
    val attributes = extractAttributes(this) + (status?.let { extractAttributes(it) } ?: emptyMap())
    return MqObjectSnapshot(
        name = stringValue(nameSelector) ?: "",
        type = type,
        attributes = attributes,
        description = stringValue(MQConstants.MQCACH_DESC)
            ?: stringValue(MQConstants.MQCA_Q_DESC),
    )
}

/** 同上，但名字缺失（极罕见）直接丢弃该条。 */
internal fun PCFMessage.toSnapshotOrNull(type: MqObjectType, nameSelector: Int): MqObjectSnapshot? {
    val name = stringValue(nameSelector) ?: return null
    return toSnapshot(type, nameSelector).copy(name = name)
}

/** 队列专用快照：静态属性（maxDepth 等）+ 状态响应（currentDepth / 句柄数）。 */
internal fun PCFMessage.toQueueSnapshot(status: PCFMessage?): MqObjectSnapshot {
    val base = toSnapshot(MqObjectType.QUEUE, MQConstants.MQCA_Q_NAME, status)
    val currentDepth = status?.intValue(MQConstants.MQIA_CURRENT_Q_DEPTH)
    val openInput = status?.intValue(MQConstants.MQIA_OPEN_INPUT_COUNT) ?: 0
    val openOutput = status?.intValue(MQConstants.MQIA_OPEN_OUTPUT_COUNT) ?: 0
    return base.copy(
        currentDepth = currentDepth,
        maxDepth = intValue(MQConstants.MQIA_MAX_Q_DEPTH),
        messageCount = currentDepth,
        consumerCount = if (status != null) openInput + openOutput else null,
    )
}

/**
 * 状态 / 类型值 → 可读名：全部走 [MqAttributeNameMapper.lookupName] 按前缀族
 * 反查（MQCHS / MQCHT / MQLSR），不维护手写映射表 —— 客户端升级新增状态值
 * 也不用改这里。查不到给数字本身，不猜。
 */
internal fun channelStatusName(value: Int?): String? =
    value?.let { MqAttributeNameMapper.lookupName(it, "MQCHS") }?.substringAfter("MQCHS_") ?: value?.let { "STATE_$it" }

internal fun channelTypeName(value: Int?): String? =
    value?.let { MqAttributeNameMapper.lookupName(it, "MQCHT") }?.substringAfter("MQCHT_") ?: value?.let { "TYPE_$it" }

internal fun listenerStatusName(value: Int?): String? =
    value?.let { MqAttributeNameMapper.lookupName(it, "MQLSR") }?.substringAfter("MQLSR_") ?: value?.let { "STATE_$it" }
