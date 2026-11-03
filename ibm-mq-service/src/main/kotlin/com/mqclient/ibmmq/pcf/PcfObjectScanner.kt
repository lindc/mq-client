package com.mqclient.ibmmq.pcf

import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import com.ibm.mq.pcf.PCFException
import com.ibm.mq.pcf.PCFMessage
import com.ibm.mq.pcf.PCFMessageAgent
import com.ibm.mq.pcf.PCFParameter
import com.mqclient.core.ServiceException
import com.mqclient.core.concurrent.BlockingCallTimeoutException
import com.mqclient.core.concurrent.BlockingCalls
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.ibmMqLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 一轮扫描的**协作式**预算（60 秒）：超过它就不再发起新的 PCF 请求。
 *
 * 为什么不直接沿用「硬超时」：`PCFMessageAgent.send` 与 `disconnect` 同步在**同一个
 * 对象**上，从别的线程 `disconnect()` 只会排在那条阻塞的 `send` 后面 —— 它不是取消
 * 手段。`Thread.interrupt()` 对 reply queue 的 socket 等待也不起作用。所以「超时后
 * 把调用方拽走」的老做法只能放弃等待，扫描本身仍在后台跑到自然结束，而这条「幽灵
 * 扫描」会和新一轮扫描抢同一条连接（越慢越容易叠加）。
 *
 * 协作式超时把终止点放在**两次 MQI 调用之间**（见 [PcfObjectScanner.inquire]）：
 * 抛在自己的线程上 → `withAgent` 的 `finally` 照常断 agent → 预算与句柄一起释放。
 */
private const val SCAN_BUDGET_MS = 60_000L

/**
 * [BlockingCalls] 兜底的宽限：留给「最后一次在飞的 send」自己结束。
 *
 * `PCFAgent` 默认 `waitInterval` 是 30s（单次 `get` 最多等这么久），留 40s 余量。
 * **正常情况下永远不该触发** —— 触发说明那条 MQI 调用卡在了不该卡的地方（连接坏了），
 * 这时退回旧的「放弃等待」语义总比无限挂着强。
 */
private const val SEND_GRACE_MS = 40_000L

/**
 * 扫描预算耗尽 —— 协作式超时信号。
 *
 * 只在两次 MQI 调用之间抛出（[PcfObjectScanner.inquire] 的入口），所以它不会打断
 * 在飞的 `send`（那本来也打不断）；异常沿扫描自己的线程上抛，`withAgent` 的 `finally`
 * 正常断掉 agent —— 没有跑在后台的「幽灵扫描」。
 */
internal class ScanBudgetExceededException(val budgetMs: Long) :
    RuntimeException("Scan exceeded its budget (${budgetMs / 1000}s)")

/**
 * 一次扫描的截止时间（构造即开始计时）。
 *
 * 做成显式对象而不是散落的「当前时间比较」：截止时间只能从外面传进来，单类查询 /
 * [PcfObjectScanner.scanAll] / 测试用的是同一套判定。
 */
internal class ScanBudget(val totalMs: Long = SCAN_BUDGET_MS) {
    private val deadlineAt = System.currentTimeMillis() + totalMs

    fun expired(): Boolean = System.currentTimeMillis() >= deadlineAt

    /** 到点就抛 [ScanBudgetExceededException]；每条 INQUIRE 发出前都会调它。 */
    fun check() {
        if (expired()) throw ScanBudgetExceededException(totalMs)
    }
}

/**
 * 一次 PCF 查询会话：agent + 该会话的预算。
 *
 * 两者**捆在一起传**，是为了在结构上保证每条 INQUIRE 都检查预算 —— 分开传的话，
 * 漏传一个 budget 就静默退化成「无超时」，而这正是本轮要修的那个问题。
 */
internal class ScanSession(
    val agent: PCFMessageAgent,
    val budget: ScanBudget = ScanBudget(),
)

/**
 * 扫描日志用的短标签（与 `IbmMQService.objectsKey` 的后缀同形，只为日志好认）。
 *
 * 只用于**显示**：「哪些类型失败」的判定一律用 [MqObjectType] 本身，不靠字符串 ——
 * 字符串一旦和缓存 key 的映射漂移，就会变成「写错了地方却没人发现」。
 */
private fun MqObjectType.scanLabel(): String = when (this) {
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
    /**
     * 本轮**没扫全**的类型（预算耗尽 / 查询失败 / 无权限）。
     *
     * 上层必须据此**跳过**这些类型的缓存写入：把空列表写进 `objects.<type>` 等于
     * 告诉 UI「这个 QM 上没有队列」，导航树会被整片清空 —— 而真相是「这次没读到」。
     */
    val failedTypes: Set<MqObjectType> = emptySet(),
) {
    /** 是否只拿到部分结果。 */
    val partial: Boolean get() = failedTypes.isNotEmpty()
}

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
 * 内部逐类兜住（无权限 2035 / 预算耗尽 / 其他错误都跳过该类记日志，并记入
 * [ScanResult.failedTypes]），不会让一个类拖垮整轮扫描。`*With(session)` /
 * `scanAllWith(manager)` 变体直接在给定连接上执行，供真机验证与测试使用。
 */
class PcfObjectScanner {

    /* --------------------------- 对外：单类查询（抛异常） --------------------------- */

    /** 队列管理器自身属性（单条响应；理论上恒有且只有一条）。 */
    suspend fun scanQueueManager(connectionId: String): MqObjectSnapshot? =
        withAgent(connectionId) { scanQueueManagerWith(it) }

    internal suspend fun scanQueueManagerWith(session: ScanSession): MqObjectSnapshot? {
        val start = System.currentTimeMillis()
        ibmMqLog.debug("pcf scan [qmgr]: start")
        // 注意：INQUIRE_Q_MGR 的 QMgrName **不允许通配符**（带 "*" 回 3097
        // MQRCCF_Q_MGR_NAME_ERROR）—— 省略参数即返回本连接的队列管理器。
        val response = inquire(session, MQConstants.MQCMD_INQUIRE_Q_MGR) {
            // 无参数
        }.firstOrNull() ?: run {
            ibmMqLog.warn("pcf scan [qmgr]: empty response")
            return null
        }
        // 并入运行状态（属性对话框 General 页的「队列管理器状态」）：部分平台 /
        // 权限受限平台可能查不动状态，失败只记日志，不让它拖垮整轮扫描。
        val status = runCatching {
            inquire(session, MQConstants.MQCMD_INQUIRE_Q_MGR_STATUS) {
                // 无参数
            }.firstOrNull()?.intValue(MQConstants.MQIACF_Q_MGR_STATUS)
        }.getOrNull()
        if (status == null) {
            ibmMqLog.warn("pcf scan [qmgr]: status query unavailable, attributes only")
        }
        ibmMqLog.debug(
            "pcf scan [qmgr]: done status=${status ?: "-"} duration=${System.currentTimeMillis() - start}ms",
        )
        return queueManagerSnapshotFrom(response, status)
    }

    /**
     * 所有队列：INQUIRE_Q（静态属性）+ INQUIRE_Q_STATUS（深度 / 句柄数）合并。
     * 只有静态属性、没有状态条目的队列（如远程队列）照常收录，动态字段为 null。
     */
    suspend fun scanQueues(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanQueuesWith(it) }

    internal suspend fun scanQueuesWith(session: ScanSession): List<MqObjectSnapshot> {
        val start = System.currentTimeMillis()
        ibmMqLog.debug("pcf scan [queues]: start")
        // 不带 MQIACF_Q_ATTRS 才是全量应答；一旦带上，QMGR 只回点名的 attr，
        // 整个属性窗口会塌缩成几行（对齐 MQ Explorer 的行为）。
        val attributes = inquire(session, MQConstants.MQCMD_INQUIRE_Q) {
            addParameter(MQConstants.MQCA_Q_NAME, "*")
            addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_ALL)
        }
        // alias / remote 的默认应答不含这两个 attr（属性对话框 Extended 页要用，
        // MQ Explorer 也是点名请求的）——单独一条点名查询，按队列名并进快照。
        val extendedAttrs = inquire(session, MQConstants.MQCMD_INQUIRE_Q) {
            addParameter(MQConstants.MQCA_Q_NAME, "*")
            addParameter(
                MQConstants.MQIACF_Q_ATTRS,
                intArrayOf(MQConstants.MQIA_DEF_READ_AHEAD, MQConstants.MQIA_DEF_PUT_RESPONSE_TYPE),
            )
        }.associateBy { it.stringValue(MQConstants.MQCA_Q_NAME) }
        val statuses = inquire(session, MQConstants.MQCMD_INQUIRE_Q_STATUS) {
            addParameter(MQConstants.MQCA_Q_NAME, "*")
        }.associateBy { it.stringValue(MQConstants.MQCA_Q_NAME) }
        // 谁打开了这个队列：句柄状态（StatusType=MQIACF_Q_HANDLE）每个打开句柄一条
        // 响应，取进程号（MQIACF_PROCESS_ID）按队列名聚合；无人打开 → 无该键，
        // 属性对话框的「访问进程」行自动隐藏。
        val openProcessIds = inquire(session, MQConstants.MQCMD_INQUIRE_Q_STATUS) {
            addParameter(MQConstants.MQCA_Q_NAME, "*")
            addParameter(MQConstants.MQIACF_Q_STATUS_TYPE, MQConstants.MQIACF_Q_HANDLE)
        }.mapNotNull(::openProcessIdPairFrom).groupBy({ it.first }, { it.second })

        val result = attributes.mapNotNull { response ->
            val name = response.stringValue(MQConstants.MQCA_Q_NAME)
            queueSnapshotFrom(
                response = response,
                status = statuses[name],
                extendedAttrs = extendedAttrs[name]?.let { extractAttributes(it) } ?: emptyMap(),
                openProcessIds = openProcessIds[name],
            )
        }
        ibmMqLog.debug(
            "pcf scan [queues]: done count=${result.size} duration=${System.currentTimeMillis() - start}ms",
        )
        return result
    }

    /**
     * 队列状态快扫（心跳用）：只发 INQUIRE_Q_STATUS 一条，取名字 + 当前深度。
     * 不做属性合并 —— 心跳要的是轻。
     */
    suspend fun scanQueueStatuses(connectionId: String): List<Pair<String, Int>> =
        withAgent(connectionId) { scanQueueStatusesWith(it) }

    internal suspend fun scanQueueStatusesWith(session: ScanSession): List<Pair<String, Int>> {
        val start = System.currentTimeMillis()
        val result = inquire(session, MQConstants.MQCMD_INQUIRE_Q_STATUS) {
            addParameter(MQConstants.MQCA_Q_NAME, "*")
        }.mapNotNull(::queueDepthPairFrom)
        // 心跳快扫（默认 10s 一轮）：只打一行出口，控制日志量。
        ibmMqLog.debug("pcf scan [q-statuses]: count=${result.size} duration=${System.currentTimeMillis() - start}ms")
        return result
    }

    /**
     * 通道状态快扫（心跳用）：只发 INQUIRE_CHANNEL_STATUS 一条，取名字 + 运行状态
     * 原始值（MQIACH_CHANNEL_STATUS，MQCHS_*；可读名用 [channelStatusName] 转）。
     */
    suspend fun scanChannelStatuses(connectionId: String): List<Pair<String, Int>> =
        withAgent(connectionId) { scanChannelStatusesWith(it) }

    internal suspend fun scanChannelStatusesWith(session: ScanSession): List<Pair<String, Int>> {
        val start = System.currentTimeMillis()
        val result = inquire(session, MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS) {
            addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*")
        }.mapNotNull(::channelStatusPairFrom)
        // 心跳快扫（同上）：只打一行出口，控制日志量。
        ibmMqLog.debug("pcf scan [ch-statuses]: count=${result.size} duration=${System.currentTimeMillis() - start}ms")
        return result
    }

    /**
     * 单通道状态查询（通道启停后轮询用）：INQUIRE_CHANNEL_STATUS 只查指定通道名，
     * 返回该通道的状态行（一个通道定义可能对应多行，如 SVRCONN 上的多个活动连接），
     * 每行是 名字 → MQIACH_CHANNEL_STATUS 原始值。通道已完全停止（无状态行）返回空列表。
     */
    suspend fun queryChannelStatus(connectionId: String, name: String): List<Pair<String, Int>> =
        withAgent(connectionId) { queryChannelStatusWith(it, name) }

    internal suspend fun queryChannelStatusWith(session: ScanSession, name: String): List<Pair<String, Int>> {
        // 注意：INQUIRE_CHANNEL_STATUS 对**已停 / 从未启动**的通道（按名字查询）不返回
        // 空结果，而是回 MQCC_FAILED + 3065 MQRCCF_CHL_STATUS_NOT_FOUND —— 这正是
        // 「未运行」的信号，必须转成空列表（与监听器查询的 2085 → 空列表同款坑），
        // 否则启停轮询会把已停通道当成查询失败。
        val responses = try {
            inquire(session, MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS) {
                addParameter(MQConstants.MQCACH_CHANNEL_NAME, name)
            }
        } catch (e: PCFException) {
            if (e.reasonCode == MQConstants.MQRCCF_CHL_STATUS_NOT_FOUND) {
                ibmMqLog.debug("pcf channel status: channel=$name not running (3065), treated as stopped")
                return emptyList()
            }
            throw e
        }
        val result = responses.mapNotNull(::channelStatusPairFrom)
        ibmMqLog.debug("pcf channel status: channel=$name rows=${result.size}")
        return result
    }

    /**
     * 单通道运行状态明细（属性对话框「运行状态」分组用）：INQUIRE_CHANNEL_STATUS
     * 只查指定通道名，取**全量应答**的第一个实例行（SVRCONN 多活动连接时多行，
     * 运行指标取第一个实例；一个通道定义的其他实例按同口径处理语义差异不大）。
     *
     * 通道已完全停止（无状态行）返回 null —— 与 [queryChannelStatus] 的空列表同义，
     * UI 显示「通道未运行」而不是拿 0 冒充指标。
     *
     * 取值走 [extractAttributes]（mapper 反查的可读键），与属性对话框「全部属性」
     * 兜底视图同一套键名 —— 同一字段两处显示永远一致。序列号 / in-doubt 的常量
     * 名在 10.0.0.5 客户端有别名同值（1529/1532 各有两个名字、1516/1528 两个
     * in-doubt 族 ID），读取时做别名兼容，不依赖 mapper 反查的第一个名字。
     */
    suspend fun queryChannelStatusDetail(connectionId: String, name: String): ChannelStatusDetail? =
        withAgent(connectionId) { queryChannelStatusDetailWith(it, name) }

    internal suspend fun queryChannelStatusDetailWith(session: ScanSession, name: String): ChannelStatusDetail? {
        // 3065 MQRCCF_CHL_STATUS_NOT_FOUND = 通道无状态实例（已停 / 从未启动）——
        // 与 [queryChannelStatusWith] 同款坑，转成 null 让 UI 显示「通道未运行」。
        fun statusRow(instanceType: Int): PCFMessage? = try {
            inquire(session, MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS) {
                addParameter(MQConstants.MQCACH_CHANNEL_NAME, name)
                addParameter(MQConstants.MQIACH_CHANNEL_INSTANCE_TYPE, instanceType)
            }.firstOrNull()
        } catch (e: PCFException) {
            if (e.reasonCode == MQConstants.MQRCCF_CHL_STATUS_NOT_FOUND) return null
            throw e
        }

        val current = statusRow(MQConstants.MQOT_CURRENT_CHANNEL) ?: run {
            ibmMqLog.debug("pcf channel status detail: channel=$name not running")
            return null
        }
        val detail = channelStatusDetailFrom(name, current) ?: return null
        // 序列号回落：部分 QM（实测 9.2.4 容器）CURRENT 行的序列号恒为 0（流量计数
        // 不更新），真实序列号在 SAVED 行（上次实例的最终值）。0 / 缺失时回落 SAVED，
        // 避免把 0 当成真实值显示。
        val saved = statusRow(MQConstants.MQOT_SAVED_CHANNEL)
        return mergeSavedSeqNumbers(detail, saved?.let(::extractAttributes))
    }

    /**
     * 单队列当前深度（消息处理对话框「共 N 条」用）：INQUIRE_Q 只点名
     * MQIA_CURRENT_Q_DEPTH，比心跳的全量 Q_STATUS 轻得多。队列不存在（应答为空）
     * 返回 0 —— 不抛，交给调用方按空队列展示。
     */
    suspend fun queryQueueDepth(connectionId: String, name: String): Int =
        withAgent(connectionId) { session ->
            val responses = inquire(session, MQConstants.MQCMD_INQUIRE_Q) {
                addParameter(MQConstants.MQCA_Q_NAME, name.trim())
                addParameter(MQConstants.MQIACF_Q_ATTRS, intArrayOf(MQConstants.MQIA_CURRENT_Q_DEPTH))
            }
            responses.firstNotNullOfOrNull { it.intValue(MQConstants.MQIA_CURRENT_Q_DEPTH) } ?: 0
        }

    /** 所有通道：INQUIRE_CHANNEL + INQUIRE_CHANNEL_STATUS（运行状态）合并。 */
    suspend fun scanChannels(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanChannelsWith(it) }

    internal suspend fun scanChannelsWith(session: ScanSession): List<MqObjectSnapshot> {
        val start = System.currentTimeMillis()
        ibmMqLog.debug("pcf scan [channels]: start")
        val attributes = inquire(session, MQConstants.MQCMD_INQUIRE_CHANNEL) {
            addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*")
        }
        val statuses = inquire(session, MQConstants.MQCMD_INQUIRE_CHANNEL_STATUS) {
            addParameter(MQConstants.MQCACH_CHANNEL_NAME, "*")
        }.associateBy { it.stringValue(MQConstants.MQCACH_CHANNEL_NAME) }

        val result = attributes.mapNotNull { response ->
            channelSnapshotFrom(response, statuses[response.stringValue(MQConstants.MQCACH_CHANNEL_NAME)])
        }
        ibmMqLog.debug(
            "pcf scan [channels]: done count=${result.size} duration=${System.currentTimeMillis() - start}ms",
        )
        return result
    }

    /** 所有主题定义。 */
    suspend fun scanTopics(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanTopicsWith(it) }

    internal suspend fun scanTopicsWith(session: ScanSession): List<MqObjectSnapshot> =
        scanSimpleType(session, "topics", MQConstants.MQCMD_INQUIRE_TOPIC, MQConstants.MQCA_TOPIC_NAME, MqObjectType.TOPIC)

    /** 所有订阅（有名字的；系统内部匿名订阅不在 INQUIRE_SUBSCRIPTION 通配范围内）。 */
    suspend fun scanSubscriptions(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanSubscriptionsWith(it) }

    internal suspend fun scanSubscriptionsWith(session: ScanSession): List<MqObjectSnapshot> =
        scanSimpleType(session, "subscriptions", MQConstants.MQCMD_INQUIRE_SUBSCRIPTION, MQConstants.MQCACF_SUB_NAME, MqObjectType.SUBSCRIPTION)

    /** 所有监听器：INQUIRE_LISTENER + INQUIRE_LISTENER_STATUS（运行状态 / 端口）合并。 */
    suspend fun scanListeners(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanListenersWith(it) }

    internal suspend fun scanListenersWith(session: ScanSession): List<MqObjectSnapshot> {
        val start = System.currentTimeMillis()
        ibmMqLog.debug("pcf scan [listeners]: start")
        val attributes = inquire(session, MQConstants.MQCMD_INQUIRE_LISTENER) {
            addParameter(MQConstants.MQCACH_LISTENER_NAME, "*")
        }
        val statuses = inquire(session, MQConstants.MQCMD_INQUIRE_LISTENER_STATUS) {
            addParameter(MQConstants.MQCACH_LISTENER_NAME, "*")
        }.associateBy { it.stringValue(MQConstants.MQCACH_LISTENER_NAME) }

        val result = attributes.mapNotNull { response ->
            listenerSnapshotFrom(response, statuses[response.stringValue(MQConstants.MQCACH_LISTENER_NAME)])
        }
        ibmMqLog.debug(
            "pcf scan [listeners]: done count=${result.size} duration=${System.currentTimeMillis() - start}ms",
        )
        return result
    }

    /**
     * 单监听器状态查询（启停后轮询用）：INQUIRE_LISTENER_STATUS 只查指定监听器名，
     * 返回该监听器的状态行（名字 → MQIACH_LISTENER_STATUS 原始值）。监听器已停（无状态行）返回空列表。
     */
    suspend fun queryListenerStatus(connectionId: String, name: String): List<Pair<String, Int>> =
        withAgent(connectionId) { queryListenerStatusWith(it, name) }

    internal suspend fun queryListenerStatusWith(session: ScanSession, name: String): List<Pair<String, Int>> {
        // 注意：INQUIRE_LISTENER_STATUS 对**未运行的监听器**（按名字查询）不返回空结果，
        // 而是回 MQCC_FAILED + MQRC_UNKNOWN_OBJECT_NAME (2085) —— 这正是「已停」的信号，
        // 必须转成空列表（pollListenerStatus 据此解析为 NOT_RUNNING），否则轮询会一直报错。
        val responses = try {
            inquire(session, MQConstants.MQCMD_INQUIRE_LISTENER_STATUS) {
                addParameter(MQConstants.MQCACH_LISTENER_NAME, name)
            }
        } catch (e: PCFException) {
            if (e.reasonCode == MQConstants.MQRC_UNKNOWN_OBJECT_NAME) {
                ibmMqLog.debug("pcf listener status: listener=$name not running (2085), treated as stopped")
                return emptyList()
            }
            throw e
        }
        val result = responses.mapNotNull(::listenerStatusPairFrom)
        ibmMqLog.debug("pcf listener status: listener=$name rows=${result.size}")
        return result
    }

    /** 所有服务定义。 */
    suspend fun scanServices(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanServicesWith(it) }

    internal suspend fun scanServicesWith(session: ScanSession): List<MqObjectSnapshot> =
        scanSimpleType(session, "services", MQConstants.MQCMD_INQUIRE_SERVICE, MQConstants.MQCA_SERVICE_NAME, MqObjectType.SERVICE)

    /** 所有进程定义。 */
    suspend fun scanProcesses(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanProcessesWith(it) }

    internal suspend fun scanProcessesWith(session: ScanSession): List<MqObjectSnapshot> =
        scanSimpleType(session, "processes", MQConstants.MQCMD_INQUIRE_PROCESS, MQConstants.MQCA_PROCESS_NAME, MqObjectType.PROCESS)

    /** 所有名称列表。 */
    suspend fun scanNamelists(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanNamelistsWith(it) }

    internal suspend fun scanNamelistsWith(session: ScanSession): List<MqObjectSnapshot> =
        scanSimpleType(session, "namelists", MQConstants.MQCMD_INQUIRE_NAMELIST, MQConstants.MQCA_NAMELIST_NAME, MqObjectType.NAMELIST)

    /** 所有认证信息对象。 */
    suspend fun scanAuthInfos(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanAuthInfosWith(it) }

    internal suspend fun scanAuthInfosWith(session: ScanSession): List<MqObjectSnapshot> =
        scanSimpleType(session, "authInfos", MQConstants.MQCMD_INQUIRE_AUTH_INFO, MQConstants.MQCA_AUTH_INFO_NAME, MqObjectType.AUTH_INFO)

    /** 所有通信信息对象。 */
    suspend fun scanCommInfos(connectionId: String): List<MqObjectSnapshot> =
        withAgent(connectionId) { scanCommInfosWith(it) }

    internal suspend fun scanCommInfosWith(session: ScanSession): List<MqObjectSnapshot> =
        scanSimpleType(session, "commInfos", MQConstants.MQCMD_INQUIRE_COMM_INFO, MQConstants.MQCA_COMM_INFO_NAME, MqObjectType.COMM_INFO)

    /**
     * 集群关系（集群关系 Tab）：本地 QMGR 的角色（REPOS 字段非空 = 完整仓库）+
     * 视角内全部成员（DISPLAY CLUSQMGR 实时查询，不经慢扫缓存）。
     */
    suspend fun queryClusterRelations(connectionId: String): ClusterRelations =
        withAgent(connectionId) { queryClusterRelationsWith(it) }

    internal suspend fun queryClusterRelationsWith(session: ScanSession): ClusterRelations {
        // ① 本地 QMGR：QMNAME（节点名）+ REPOS（完整仓库集群名，非空 = 完整仓库）。
        //    INQUIRE_Q_MGR 的 QMgrName 不允许通配符（带 "*" 回 3097）—— 无参数即本连接。
        val qmgrResponse = inquire(session, MQConstants.MQCMD_INQUIRE_Q_MGR) {
        }.firstOrNull()
        val localQmgrName = qmgrResponse?.stringValue(MQConstants.MQCA_Q_MGR_NAME)?.trim().orEmpty()
        val reposClusterName = qmgrResponse?.stringValue(MQConstants.MQCA_REPOSITORY_NAME)
            ?.trim()?.takeIf { it.isNotEmpty() }
        // ② 成员：PCF 命令常量是 MQCMD_INQUIRE_CLUSTER_Q_MGR（Q_MGR 带下划线，已核实
        //    allclient 10.0.0.5 jar），与 MQSC 的 DISPLAY CLUSQMGR 同义。
        val members = queryClusterMembersWith(session)
        val role = when {
            reposClusterName != null -> QmgrClusterRole.FULL
            members.isNotEmpty() -> QmgrClusterRole.PARTIAL
            else -> QmgrClusterRole.NONE
        }
        val relations = ClusterRelations(role, reposClusterName, localQmgrName, members)
        ibmMqLog.debug(
            "pcf cluster relations: localQmgr=${localQmgrName.ifEmpty { "-" }} role=$role " +
                "cluster=${reposClusterName ?: "-"} members=${members.size}",
        )
        return relations
    }

    /** 成员列表（DISPLAY CLUSQMGR 逐条响应 → [ClusterMember]）。 */
    private suspend fun queryClusterMembersWith(session: ScanSession): List<ClusterMember> {
        // 注意：本命令的合法筛选参数是 MQCA_CLUSTER_Q_MGR_NAME（按成员 QMgr 名，通配
        // "*" = 全部）——DISPLAY CLUSQMGR(*) 括号里匹配的是 QMgr 名。发 MQCA_CLUSTER_NAME
        // 会被 QM 回 3015 MQRCCF_CFST_PARM_ID_ERROR（参数 ID 对本命令无效，实测）。
        val responses = inquire(session, MQConstants.MQCMD_INQUIRE_CLUSTER_Q_MGR) {
            addParameter(MQConstants.MQCA_CLUSTER_Q_MGR_NAME, "*")
        }
        return responses.mapNotNull(::clusterMemberFrom)
    }

    /* --------------------------- 对外：全量扫描（逐类兜底） --------------------------- */

    /**
     * 一轮扫齐 11 类对象：各类**并发**查询（各自独立 agent），单类失败（无权限 /
     * 超时 / 其他）只记日志、该类返回空结果，不拖垮整轮；取消（应用退出）原样上抛。
     */
    suspend fun scanAll(connectionId: String): ScanResult = coroutineScope {
        val start = System.currentTimeMillis()
        ibmMqLog.debug("pcf scanAll start: connection=$connectionId")
        // 11 路 async 并发写，用线程安全集合（每条类型只写自己的 key）。
        val failures = ConcurrentHashMap.newKeySet<MqObjectType>()
        val queueManagerDeferred = async { safelyNullable(MqObjectType.QUEUE_MANAGER, failures) { scanQueueManager(connectionId) } }
        val queuesDeferred = async { safelyList(MqObjectType.QUEUE, failures) { scanQueues(connectionId) } }
        val channelsDeferred = async { safelyList(MqObjectType.CHANNEL, failures) { scanChannels(connectionId) } }
        val topicsDeferred = async { safelyList(MqObjectType.TOPIC, failures) { scanTopics(connectionId) } }
        val subscriptionsDeferred = async { safelyList(MqObjectType.SUBSCRIPTION, failures) { scanSubscriptions(connectionId) } }
        val listenersDeferred = async { safelyList(MqObjectType.LISTENER, failures) { scanListeners(connectionId) } }
        val servicesDeferred = async { safelyList(MqObjectType.SERVICE, failures) { scanServices(connectionId) } }
        val processesDeferred = async { safelyList(MqObjectType.PROCESS, failures) { scanProcesses(connectionId) } }
        val namelistsDeferred = async { safelyList(MqObjectType.NAMELIST, failures) { scanNamelists(connectionId) } }
        val authInfosDeferred = async { safelyList(MqObjectType.AUTH_INFO, failures) { scanAuthInfos(connectionId) } }
        val commInfosDeferred = async { safelyList(MqObjectType.COMM_INFO, failures) { scanCommInfos(connectionId) } }

        val result = ScanResult(
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
            // await 全部完成后再快照：此前的 add 都已经 happens-before。
            failedTypes = failures.toSet(),
        )
        ibmMqLog.info(
            "pcf scanAll completed: connection=$connectionId " +
                "queues=${result.queues.size} channels=${result.channels.size} topics=${result.topics.size} " +
                "subscriptions=${result.subscriptions.size} listeners=${result.listeners.size} " +
                "services=${result.services.size} processes=${result.processes.size} " +
                "namelists=${result.namelists.size} authInfos=${result.authInfos.size} " +
                "commInfos=${result.commInfos.size} " +
                "failed=${result.failedTypes.joinToString(",") { it.scanLabel() }} " +
                "duration=${System.currentTimeMillis() - start}ms",
        )
        result
    }

    /**
     * [scanAll] 的「给定 manager」串行变体（真机验证 / 工具用）：一条连接开一个
     * agent 逐类查询，逐类兜底语义与 [scanAll] 相同。
     */
    internal suspend fun scanAllWith(manager: MQQueueManager): ScanResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        ibmMqLog.debug("pcf scanAllWith start")
        // 诊断路径同样受预算约束：卡住的真机扫描不应该把测试进程拖成“看起来疯了”。
        val session = ScanSession(PCFMessageAgent(manager))
        val failures = ConcurrentHashMap.newKeySet<MqObjectType>()
        try {
            val result = ScanResult(
                queueManager = safelyNullable(MqObjectType.QUEUE_MANAGER, failures) { scanQueueManagerWith(session) },
                queues = safelyList(MqObjectType.QUEUE, failures) { scanQueuesWith(session) },
                channels = safelyList(MqObjectType.CHANNEL, failures) { scanChannelsWith(session) },
                topics = safelyList(MqObjectType.TOPIC, failures) { scanTopicsWith(session) },
                subscriptions = safelyList(MqObjectType.SUBSCRIPTION, failures) { scanSubscriptionsWith(session) },
                listeners = safelyList(MqObjectType.LISTENER, failures) { scanListenersWith(session) },
                services = safelyList(MqObjectType.SERVICE, failures) { scanServicesWith(session) },
                processes = safelyList(MqObjectType.PROCESS, failures) { scanProcessesWith(session) },
                namelists = safelyList(MqObjectType.NAMELIST, failures) { scanNamelistsWith(session) },
                authInfos = safelyList(MqObjectType.AUTH_INFO, failures) { scanAuthInfosWith(session) },
                commInfos = safelyList(MqObjectType.COMM_INFO, failures) { scanCommInfosWith(session) },
                failedTypes = failures.toSet(),
            )
            ibmMqLog.debug(
                "pcf scanAllWith completed: queues=${result.queues.size} channels=${result.channels.size} " +
                    "failed=${result.failedTypes.joinToString(",") { it.scanLabel() }} " +
                    "duration=${System.currentTimeMillis() - start}ms",
            )
            result
        } finally {
            runCatching { session.agent.disconnect() }
        }
    }

    /* ------------------------------ 内部：兜底 / 连接 / 查询 ------------------------------ */

    /**
     * 列表类查询的兜底：任何失败 → 空列表（取消除外）。
     *
     * 失败（含预算耗尽）的类型登记进 [failures]：上层据此**不写缓存** ——
     * 空列表写进 `objects.<type>` 等于告诉 UI「这个 QM 上没有对象」。
     *
     * `internal` 是为了让单测能直接验证「失败类型被记录」这条契约（它不是公开 API，
     * 但它是「不拿空列表覆盖缓存」的**唯一**开关，坏了没人看得见）。
     */
    internal suspend fun <T> safelyList(
        type: MqObjectType,
        failures: MutableSet<MqObjectType>,
        block: suspend () -> List<T>,
    ): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ScanBudgetExceededException) {
        failures += type
        // 预算是自己定的上限，不是 QM 出错：记 warn，别当故障。
        ibmMqLog.warn("pcf scan [${type.scanLabel()}] stopped: ${e.message}")
        emptyList()
    } catch (e: PCFException) {
        failures += type
        logScanFailure(type.scanLabel(), e)
        emptyList()
    } catch (e: Exception) {
        failures += type
        ibmMqLog.error("pcf scan [${type.scanLabel()}] failed: ${e.message}")
        emptyList()
    }

    /** 单对象查询（QMGR）的兜底：任何失败 → null（同样登记失败类型）。 */
    internal suspend fun safelyNullable(
        type: MqObjectType,
        failures: MutableSet<MqObjectType>,
        block: suspend () -> MqObjectSnapshot?,
    ): MqObjectSnapshot? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ScanBudgetExceededException) {
        failures += type
        ibmMqLog.warn("pcf scan [${type.scanLabel()}] stopped: ${e.message}")
        null
    } catch (e: PCFException) {
        failures += type
        logScanFailure(type.scanLabel(), e)
        null
    } catch (e: Exception) {
        failures += type
        ibmMqLog.error("pcf scan [${type.scanLabel()}] failed: ${e.message}")
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
     * 取该连接的 MQQueueManager，在其上开 PCFMessageAgent 执行 [block]。
     *
     * **两层超时，职责不同：**
     *  - 内层（真正的）：[ScanBudget]，60s。到点后 [inquire] 拒绝再发请求，扫描在
     *    **自己的线程上**抛 [ScanBudgetExceededException] 并走 `finally` 断 agent。
     *    这是「幽灵扫描」的根治手段。
     *  - 外层（兜底）：[BlockingCalls]，预算 + [SEND_GRACE_MS]。`PCFMessageAgent` 的
     *    构造与每次 `send` 都是**纯阻塞、无挂起点**的 MQI 调用，`withTimeout` 在它身上
     *    不生效；而 `send` 与 `disconnect` 同锁、`interrupt()` 对 socket 等待也没用，
     *    所以真卡住时只能用「放弃等待」。正常情况下它永远不该触发。
     *
     * **千万不要**把 `PCFMessageAgent(manager)` 换成 `PCFMessageAgent(host, port, channel)`：
     * 前者（`connect(MQQueueManager)` 走 `iC=true`）不会把自己的 qmanager 记进字段，
     * 所以 `disconnect()` 只关自己的 admin / reply 队列，不会碰共享连接；后者会把它记进
     * 字段，`disconnect()` 就把**整条连接**关了（心跳 / 控制台 / 消息浏览全断）。
     */
    private suspend fun <T> withAgent(connectionId: String, block: suspend (ScanSession) -> T): T {
        val manager: MQQueueManager = IbmMQModule.connectionOrNull(connectionId)?.mqConnection
            ?: throw ServiceException(
                "ibm_mq_err_not_connected",
                args = listOf(connectionId),
                fallbackMessage = "Connection %1\$s is not established",
            )
        val start = System.currentTimeMillis()
        ibmMqLog.debug("pcf agent open: connection=$connectionId")
        val budget = ScanBudget()
        return try {
            BlockingCalls.withDeadline(
                timeoutMs = budget.totalMs + SEND_GRACE_MS,
                label = "PCF query (connection=$connectionId)",
            ) {
                // 在 worker 线程上桥接挂起函数：block 内部基本是阻塞的 MQI 调用，
                // 用 runBlocking 把它包成同步体，与 agent 的创建 / 断开同一线程。
                runBlocking {
                    val agent = PCFMessageAgent(manager)
                    try {
                        block(ScanSession(agent, budget))
                    } finally {
                        runCatching { agent.disconnect() }
                        ibmMqLog.debug(
                            "pcf agent closed: connection=$connectionId " +
                                "duration=${System.currentTimeMillis() - start}ms",
                        )
                    }
                }
            }
        } catch (e: BlockingCallTimeoutException) {
            // 走到这里说明连预算 + 宽限都超了：连接真的坏了（不是 QM 只是慢）。
            ibmMqLog.error(
                "pcf query stuck: connection=$connectionId " +
                    "waited=${(budget.totalMs + SEND_GRACE_MS) / 1000}s",
            )
            throw e
        }
    }

    /**
     * 发一条 INQUIRE 请求，返回响应消息列表（完成码非 OK 时 send 抛 PCFException）。
     *
     * `session.budget.check()` 是**唯一的协作式取消点**，也是本轮修复的核心：放在
     * `send` **之前**，所以它只拒绝发起下一次请求，从不试图打断在飞的那一次（那本来
     * 也做不到）。于是预算耗尽时异常抛在扫描自己的线程上，agent 由 `finally` 正常断开。
     */
    private fun inquire(
        session: ScanSession,
        command: Int,
        parameters: PCFMessage.() -> Unit,
    ): Array<out PCFMessage> {
        session.budget.check()
        val request = PCFMessage(command).apply(parameters)
        ibmMqLog.debug("pcf inquire: cmd=$command")
        return session.agent.send(request)
    }

    /**
     * 简单类对象（单条通配 INQUIRE → 名字快照）的统一扫描：入口/出口 DEBUG 打点
     * 在这里集中做，7 个同构的 `scanXxxWith` 复用。
     */
    private suspend fun scanSimpleType(
        session: ScanSession,
        name: String,
        command: Int,
        nameSelector: Int,
        type: MqObjectType,
    ): List<MqObjectSnapshot> {
        val start = System.currentTimeMillis()
        ibmMqLog.debug("pcf scan [$name]: start")
        val result = inquire(session, command) {
            addParameter(nameSelector, "*")
        }.mapNotNull { it.toSnapshotOrNull(type, nameSelector) }
        ibmMqLog.debug(
            "pcf scan [$name]: done count=${result.size} duration=${System.currentTimeMillis() - start}ms",
        )
        return result
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

/** PCF 值转可读形态：byte[]（字符串类参数的底层形态）解码后去 NUL 与**尾随空格**，
 *  数组拼串。PCF 字符串按固定宽度补空格（如队列名 48 字符），不 trim 的话
 *  "CUSTOMER.LOCAL.Q     " 这类值永远匹配不上按干净名字建的索引 —— 拓扑边曾因此全断。 */
internal fun readableValue(value: Any?): Any? = when (value) {
    null -> null
    is ByteArray -> value.decodeToString().trimEnd('\u0000').trimEnd()
    is Array<*> -> value.joinToString(", ") { readableValue(it)?.toString().orEmpty() }
    // Java 侧的 int[] / long[] / short[]（PCF 列表参数如 MQIACF_Q_TIME_INDICATOR 的
    // 底层形态，Kotlin 里不是 Array<*>）——不转的话 UI 只能显示 "[I@41cf4272"。
    is IntArray -> value.joinToString(", ")
    is LongArray -> value.joinToString(", ")
    is ShortArray -> value.joinToString(", ")
    else -> value
}

/** 取字符串参数（兼容 String / ByteArray 两种底层形态）。
 *  PCF 字符串按固定宽度补尾随空格（通道名 20 字符等），末尾 NUL 与空格都去掉 ——
 *  MQ 对象名本身不含尾随空格，留着只会污染卡片标题与 LazyGrid 的 key。 */
internal fun PCFMessage.stringValue(selector: Int): String? = when (val value = getParameterValue(selector)) {
    null -> null
    is String -> value.trimEnd('\u0000').trimEnd()
    is ByteArray -> value.decodeToString().trimEnd('\u0000').trimEnd()
    else -> value.toString().trimEnd('\u0000').trimEnd()
}

/** 取整型参数（非 Int 一律 null，调用方用 null 语义兜底）。 */
internal fun PCFMessage.intValue(selector: Int): Int? = getParameterValue(selector) as? Int

/** 属性 map 里按候选键取整数值（别名同值常量做兼容，见 [queryChannelStatusDetailWith]）。 */
internal fun Map<String, Any?>.longOf(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? Number)?.toLong() }

/** 属性 map 里按候选键取字符串值（空白视为缺失）。 */
internal fun Map<String, Any?>.strOf(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() } }

/**
 * 属性 map 里按候选键取布尔值：in-doubt 族在响应里是 0/1 整型
 * （MQCHID_NO=0 / MQCHID_YES=1 语义）；任一候选键非 0 即 true。
 */
internal fun Map<String, Any?>.boolOf(vararg keys: String): Boolean? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? Number)?.toInt() }?.let { it != 0 }

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

/**
 * CHSTATUS 应答 → 运行状态明细（纯转换，internal 供单测）：[response] 为 null
 * （通道已停，无状态行）返回 null。字段映射与别名兼容策略见
 * [queryChannelStatusDetail] 的 KDoc —— 键名走 mapper 反查的可读键，序列号 /
 * in-doubt 按别名候选集兜底。
 */
internal fun channelStatusDetailFrom(name: String, response: PCFMessage?): ChannelStatusDetail? {
    if (response == null) return null
    val attrs = extractAttributes(response)
    return ChannelStatusDetail(
        channelName = name.trim(),
        channelType = (attrs["MQIACH_CHANNEL_TYPE"] as? Number)?.let { channelTypeName(it.toInt()) },
        status = (attrs["MQIACH_CHANNEL_STATUS"] as? Number)?.let { channelStatusName(it.toInt()) },
        currentSeqNo = attrs.longOf("MQIACH_CURRENT_SEQ_NUMBER", "MQIACH_CURRENT_SEQUENCE_NUMBER"),
        lastSeqNum = attrs.longOf("MQIACH_LAST_SEQ_NUMBER", "MQIACH_LAST_SEQUENCE_NUMBER"),
        inDoubt = attrs.boolOf("MQIACH_IN_DOUBT", "MQIACH_INDOUBT_STATUS"),
        messages = attrs.longOf("MQIACH_MSGS"),
        bytesSent = attrs.longOf("MQIACH_BYTES_SENT"),
        bytesReceived = attrs.longOf("MQIACH_BYTES_RCVD"),
        lastMsgDate = attrs.strOf("MQCACH_LAST_MSG_DATE"),
        lastMsgTime = attrs.strOf("MQCACH_LAST_MSG_TIME"),
    )
}

/**
 * 序列号的 SAVED 行回落（纯转换，internal 供单测）：CURRENT 行的序列号为 0 / 缺失
 * （部分 QM 的当前实例行不上报序列号，实测 9.2.4 容器）时，取 SAVED 行（上次实例的
 * 最终值）兜底；CURRENT 行有真实值（> 0）时不覆盖 —— SAVED 在长连接下是旧的。
 * [savedAttrs] 为 null（无 SAVED 行，如从未运行过）原样返回。
 */
internal fun mergeSavedSeqNumbers(detail: ChannelStatusDetail, savedAttrs: Map<String, Any?>?): ChannelStatusDetail {
    if (savedAttrs == null) return detail
    val needCur = detail.currentSeqNo == null || detail.currentSeqNo == 0L
    val needLast = detail.lastSeqNum == null || detail.lastSeqNum == 0L
    if (!needCur && !needLast) return detail
    return detail.copy(
        currentSeqNo = if (needCur) savedAttrs.longOf("MQIACH_CURRENT_SEQ_NUMBER", "MQIACH_CURRENT_SEQUENCE_NUMBER") ?: detail.currentSeqNo else detail.currentSeqNo,
        lastSeqNum = if (needLast) savedAttrs.longOf("MQIACH_LAST_SEQ_NUMBER", "MQIACH_LAST_SEQUENCE_NUMBER") ?: detail.lastSeqNum else detail.lastSeqNum,
    )
}

/** 单条 Q_STATUS 应答 → 名字 + 当前深度（心跳快扫用）；名字 / 深度缺一即丢弃。 */
internal fun queueDepthPairFrom(response: PCFMessage): Pair<String, Int>? {
    val name = response.stringValue(MQConstants.MQCA_Q_NAME) ?: return null
    val depth = response.intValue(MQConstants.MQIA_CURRENT_Q_DEPTH) ?: return null
    return name to depth
}

/** 单条 CHSTATUS 应答 → 名字 + 运行状态原始值（快扫与单查共用，名字 / 状态缺一即丢弃）。 */
internal fun channelStatusPairFrom(response: PCFMessage): Pair<String, Int>? {
    val name = response.stringValue(MQConstants.MQCACH_CHANNEL_NAME) ?: return null
    val status = response.intValue(MQConstants.MQIACH_CHANNEL_STATUS) ?: return null
    return name to status
}

/** 单条 LISTENER_STATUS 应答 → 名字 + 运行状态原始值；名字 / 状态缺一即丢弃。 */
internal fun listenerStatusPairFrom(response: PCFMessage): Pair<String, Int>? {
    val name = response.stringValue(MQConstants.MQCACH_LISTENER_NAME) ?: return null
    val status = response.intValue(MQConstants.MQIACH_LISTENER_STATUS) ?: return null
    return name to status
}

/** 单条句柄状态应答 → 名字 + 打开者进程号；名字 / 进程号缺一即丢弃。 */
internal fun openProcessIdPairFrom(response: PCFMessage): Pair<String, Int>? {
    val name = response.stringValue(MQConstants.MQCA_Q_NAME) ?: return null
    val pid = response.intValue(MQConstants.MQIACF_PROCESS_ID) ?: return null
    return name to pid
}

/**
 * QMGR 属性应答 → 快照，并入 QMGR 运行状态（[qmgrStatus] 为 null = 状态查询没拿到，
 * 不加 `MQIACF_Q_MGR_STATUS` 键）。
 */
internal fun queueManagerSnapshotFrom(response: PCFMessage, qmgrStatus: Int?): MqObjectSnapshot {
    val snapshot = response.toSnapshot(MqObjectType.QUEUE_MANAGER, MQConstants.MQCA_Q_MGR_NAME)
    return if (qmgrStatus == null) snapshot
    else snapshot.copy(attributes = snapshot.attributes + ("MQIACF_Q_MGR_STATUS" to qmgrStatus))
}

/**
 * 队列属性应答 → 快照，叠加扩展属性（alias / remote 点名查询的产物）与打开者
 * 进程号列表（null = 无人打开，不加 `MQIACF_OPEN_PROCESS_IDS` 键）。
 */
internal fun queueSnapshotFrom(
    response: PCFMessage,
    status: PCFMessage?,
    extendedAttrs: Map<String, Any?> = emptyMap(),
    openProcessIds: List<Int>? = null,
): MqObjectSnapshot =
    response.toQueueSnapshot(status).let { snapshot ->
        val extras = extendedAttrs +
            listOfNotNull(openProcessIds?.let { "MQIACF_OPEN_PROCESS_IDS" to it })
        if (extras.isEmpty()) snapshot else snapshot.copy(attributes = snapshot.attributes + extras)
    }

/** 通道属性应答 + 状态应答 → 快照（channelStatus / channelType 提升为可读名强类型字段）。 */
internal fun channelSnapshotFrom(response: PCFMessage, status: PCFMessage?): MqObjectSnapshot =
    response.toSnapshot(MqObjectType.CHANNEL, MQConstants.MQCACH_CHANNEL_NAME, status).copy(
        channelStatus = status?.let { channelStatusName(it.intValue(MQConstants.MQIACH_CHANNEL_STATUS)) },
        channelType = channelTypeName(response.intValue(MQConstants.MQIACH_CHANNEL_TYPE)),
    )

/** 监听器属性应答 + 状态应答 → 快照（port 优先取状态行，缺状态回落定义属性）。 */
internal fun listenerSnapshotFrom(response: PCFMessage, status: PCFMessage?): MqObjectSnapshot =
    response.toSnapshot(MqObjectType.LISTENER, MQConstants.MQCACH_LISTENER_NAME, status).copy(
        listenerStatus = status?.let { listenerStatusName(it.intValue(MQConstants.MQIACH_LISTENER_STATUS)) },
        port = status?.intValue(MQConstants.MQIACH_PORT)
            ?: response.intValue(MQConstants.MQIACH_PORT),
    )

/**
 * 单条 CLUSQMGR 应答 → [ClusterMember]（QMgr 名缺失丢弃该条）。字段映射的常量坑
 * 注释随逻辑一起搬到这里 —— 纯转换，离线可测。
 */
internal fun clusterMemberFrom(response: PCFMessage): ClusterMember? {
    val attrs = extractAttributes(response)
    val qmgrName = (attrs["MQCA_CLUSTER_Q_MGR_NAME"]
        ?: attrs["MQCA_Q_MGR_NAME"])?.toString()?.trim() ?: return null
    return ClusterMember(
        qmgrName = qmgrName,
        clusterName = attrs["MQCA_CLUSTER_NAME"]?.toString()?.trim().orEmpty(),
        // 通道名：10.0.0.5 客户端 MQCACH_CHANNEL_NAME 与 MQCACH_FIRST 同值
        // （3501，经典 CHANNEL_NAME 应为 3510，见 ClusterConstantsDiagTest），
        // mapper 把该参数命名成 MQCACH_FIRST（实测 dump：TO.QMx）。常规键在前，
        // 客户端修正后自动切换。
        channelName = (attrs["MQCACH_CHANNEL_NAME"] ?: attrs["MQCACH_FIRST"])
            ?.toString()?.trim().orEmpty(),
        // QMTYPE：响应参数是 MQIACF_Q_MGR_TYPE（MQQMT_* 值族，实测 1=完整仓库
        // 0=部分仓库）；MQIA_CLUSTER_Q_TYPE（59）在 CLUSQMGR 响应中不存在。
        qmType = clusterQmTypeName(attrs["MQIACF_Q_MGR_TYPE"]),
        defType = clusterDefTypeName(attrs["MQIACF_Q_MGR_DEFINITION_TYPE"]),
        conName = attrs["MQCACH_CONNECTION_NAME"]?.toString()?.trim(),
        // 状态：远端成员带 MQIACH_CHANNEL_STATUS（MQCHS_* 值族，0=INACTIVE）；
        // 本地 QMgr 自身行无此参数 → null（UI 显示 "-" + tooltip）。
        status = (attrs["MQIACH_CHANNEL_STATUS"] as? Number)
            ?.let { channelStatusName(it.toInt()) },
    )
}

/**
 * CLUSQMGR 的仓库角色（响应参数 MQIACF_Q_MGR_TYPE，值族 MQQMT_*）→ REPOS / NORMAL
 * 可读名（MQ Explorer 同款措辞）；查不到（客户端常量缺失 / 未知值）返回数字本身，不猜。
 */
internal fun clusterQmTypeName(value: Any?): String {
    val intValue = (value as? Number)?.toInt() ?: return value?.toString().orEmpty()
    return when (MqAttributeNameMapper.lookupName(intValue, "MQQMT")) {
        "MQQMT_REPOSITORY" -> "REPOS"
        "MQQMT_NORMAL" -> "NORMAL"
        else -> intValue.toString()
    }
}

/**
 * CLUSQMGR 的通道定义类型（响应参数 MQIACF_Q_MGR_DEFINITION_TYPE，值族 MQQMDT_*，
 * 常量已实证存在且值正确）→ MQSC 风格 CLUSSDR / CLUSSDRA / CLUSRCVR。实测 dump：
 * 本地 QMgr 自身行 = 3（CLUSTER_RECEIVER，自己的 CLUSRCVR）、远端成员 = 2
 * （AUTO_CLUSTER_SENDER，自动定义的集群发送方）。
 */
internal fun clusterDefTypeName(value: Any?): String {
    val intValue = (value as? Number)?.toInt() ?: return value?.toString().orEmpty()
    return when (MqAttributeNameMapper.lookupName(intValue, "MQQMDT")) {
        "MQQMDT_EXPLICIT_CLUSTER_SENDER" -> "CLUSSDR"
        "MQQMDT_AUTO_CLUSTER_SENDER" -> "CLUSSDRA"
        "MQQMDT_CLUSTER_RECEIVER" -> "CLUSRCVR"
        else -> intValue.toString()
    }
}

internal fun channelTypeName(value: Int?): String? =
    value?.let { MqAttributeNameMapper.lookupName(it, "MQCHT") }?.substringAfter("MQCHT_") ?: value?.let { "TYPE_$it" }

/**
 * 监听器状态值 → 可读短名。**不能**走 [MqAttributeNameMapper]，因为 IBM MQ 的
 * allclient jar 里根本没有 `MQLSS_*` / `MQLSR_*` 这类命名常量（已核实），反射反查
 * 必然返回 null，之前因此把状态值直接拼成 `STATE_2` 暴露给用户。这里手写映射：
 *  - 0 = 未运行 / 1 = 启动中 / 2 = 运行中 / 3 = 停止中（与 IBM MQ 文档一致，且实测
 *    `STATE_2` 对应运行中的监听器）；
 *  - 其它值：记日志原始值并返回 `UNKNOWN`，由适配器显示「未知」而非裸状态码。
 */
internal fun listenerStatusName(value: Int?): String? =
    when (value) {
        null -> null
        0 -> "NOT_RUNNING"
        1 -> "STARTING"
        2 -> "RUNNING"
        3 -> "STOPPING"
        else -> {
            ibmMqLog.warn("未识别的监听器状态值（已标记 UNKNOWN）: $value")
            "UNKNOWN"
        }
    }
