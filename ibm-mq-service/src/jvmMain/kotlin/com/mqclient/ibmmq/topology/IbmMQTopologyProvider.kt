package com.mqclient.ibmmq.topology

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.core.event.EventBus
import com.mqclient.core.topology.TopologyProvider
import com.mqclient.graph.topology.EdgeStyle
import com.mqclient.graph.topology.NodeColor
import com.mqclient.graph.topology.NodeStatus
import com.mqclient.graph.topology.ObjectRef
import com.mqclient.graph.topology.TopologyEdge
import com.mqclient.graph.topology.TopologyGraph
import com.mqclient.graph.topology.TopologyNode
import com.mqclient.graph.topology.emptyGraph
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.IbmMQService
import com.mqclient.ibmmq.event.IbmMQEvents
import com.mqclient.ibmmq.pcf.MqAttributeNameMapper
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.pcf.MqObjectType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Job

/**
 * IBM MQ 的拓扑提供方：把本连接的 [MqObjectSnapshot] 缓存（对象慢扫产物）映射成通用
 * [TopologyGraph]。只读缓存、不碰 MQ；连接未建立或缓存为空时返回空图（拓扑是辅助视图）。
 *
 * 链路关系来自快照的 [MqObjectSnapshot.attributes]（PCF 参数键，键名由
 * [MqAttributeNameMapper] 反查；实测于 MQ 9.3+/10）：
 *  - alias 队列 → 目标（本版本响应不带 TARGQ，目标在 MQCA_BASE_OBJECT_NAME 携带，
 *    MQIA_BASE_TYPE 区分目标为队列(1)/主题；旧键 MQCA_TARGQ / MQCA_TARGET_Q_NAME 作回落）
 *  - remote 队列 → 传输队列（MQCA_XMIT_Q_NAME），再反向找通道（通道的 XMITQ 键是
 *    MQCACH_XMIT_Q_NAME —— 通道族用 MQCACH_ 前缀，不是 MQCA_）
 *  - 主题 → 订阅（订阅自带 MQCA_TOPIC_NAME 指向主题对象；缺失时按 MQCA_TOPIC_STRING
 *    与主题名/主题串做「去尾部 /」的宽松匹配）
 *  - 订阅 → 目标队列（MQCACF_DESTINATION）
 *
 * 注意：alias/remote/xmitq 在 IBM MQ 里不是独立对象类型，而是 QUEUE + MQIA_Q_TYPE /
 * MQIA_USAGE，映射成我们的字符串 type（"alias_queue" / "remote_queue" / "xmitq" / "queue"）。
 *
 * 只画业务链路的 7 类对象：alias / local / remote / xmitq 队列、topic、subscription、
 * channel。QMGR / Listener / Service / Process / Namelist 等不进图（属性在对象面板单独看）。
 * 不纳入系统对象：名称以 `SYSTEM` / `AMQ` 开头的对象（系统队列、系统通道等）一律过滤。
 *
 * 数据就绪遵循「先连接 → 全面扫描 → 出图」：[refresh] 触发一次对象慢扫（未连接则 no-op，
 * 由连接后的定时扫描接管），[dataChanged] 在慢扫完成后发出对应 connectionId 通知 UI 重建。
 */
internal class IbmMQTopologyProvider(
    private val source: IbmMqTopologySource,
    bus: EventBus? = CoreModule.eventBusOrNull,
) : TopologyProvider {

    /** 慢扫完成 → 通知 UI 重建图（按 connectionId）。 */
    private val _dataChanged = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val dataChanged: Flow<String> = _dataChanged.asSharedFlow()

    /** 订阅对象慢扫完成事件；[dispose] 时取消。 */
    private val busSub: Job? = bus?.on(IbmMQEvents.OBJECTS_SCANNED) { event ->
        val cid = event.payload["connectionId"] as? String
        if (cid != null) _dataChanged.tryEmit(cid)
    }

    /**
     * 订阅「连接建立」事件：拓扑视图挂载时若连接尚未就绪，[refresh] 会把该连接记入
     * [pendingRefresh]；一旦连上，这里立刻发起一次对象慢扫（绕过连接后定时扫描任务的
     * 5s 起步延迟），让拓扑图尽快出图，而不是干等 5 秒。
     */
    private val connectedSub: Job? = bus?.on(IbmMQEvents.CONNECTED) { event ->
        val cid = event.payload["connectionId"] as? String ?: return@on
        if (synchronized(pendingRefresh) { pendingRefresh.remove(cid) }) {
            source.scan(cid)
        }
    }

    /** 连接失败：清理 pending，避免泄漏（不再尝试扫描）。 */
    private val failedSub: Job? = bus?.on(IbmMQEvents.CONNECT_FAILED) { event ->
        val cid = event.payload["connectionId"] as? String ?: return@on
        synchronized(pendingRefresh) { pendingRefresh.remove(cid) }
    }

    /** 已请求刷新但尚未连上的连接（等连接建立后立即扫一次）。 */
    private val pendingRefresh = LinkedHashSet<String>()

    /** 模块停机时调用：取消事件订阅，避免泄漏。 */
    fun dispose() {
        busSub?.cancel()
        connectedSub?.cancel()
        failedSub?.cancel()
        pendingRefresh.clear()
    }

    override suspend fun refresh(connectionId: String) {
        // 已连上：立刻慢扫（扫完经 OBJECTS_SCANNED 通知面板重建）；
        // 未连上：记入 pending，等 CONNECTED 事件触发后立即扫，绕过 5s 起步延迟。
        if (source.isConnected(connectionId)) {
            source.scan(connectionId)
        } else {
            synchronized(pendingRefresh) { pendingRefresh.add(connectionId) }
        }
    }

    override suspend fun buildGraph(
        connectionId: String,
        rootName: String,
        rootType: String,
        depth: Int,
    ): TopologyGraph {
        if (!source.isConnected(connectionId)) return emptyGraph(connectionId)

        // 只画业务链路的 7 类对象；QMGR / Listener 不进图，MQQT_MODEL（模板队列）同样跳过。
        val all = buildList {
            addAll(source.snapshots(connectionId, MqObjectType.QUEUE).filterNotSystem())
            addAll(source.snapshots(connectionId, MqObjectType.CHANNEL).filterNotSystem())
            addAll(source.snapshots(connectionId, MqObjectType.TOPIC).filterNotSystem())
            addAll(source.snapshots(connectionId, MqObjectType.SUBSCRIPTION).filterNotSystem())
        }.filterNot { it.type == MqObjectType.QUEUE && it.queueType() == "MQQT_MODEL" }
        if (all.isEmpty()) return emptyGraph(connectionId)
        val byName = all.associateBy { it.name }

        // 无向邻接表：双向展开，自然覆盖「上游 + 下游各 depth 层」。
        val adj = mutableMapOf<String, MutableList<Pair<String, EdgeStyle?>>>()
        fun link(a: String, b: String?, style: EdgeStyle?) {
            if (b == null || b == a || b !in byName) return
            adj.getOrPut(a) { mutableListOf() } += b to style
            adj.getOrPut(b) { mutableListOf() } += a to style
        }

        for (s in all) {
            when {
                // Alias → 目标（队列或主题；目标类型由 BASE_TYPE 区分，这里只按名字连线）
                s.type == MqObjectType.QUEUE && s.queueType() == "MQQT_ALIAS" ->
                    link(s.name, s.aliasTarget(), null)
                // Remote → 传输队列
                s.type == MqObjectType.QUEUE && s.queueType() == "MQQT_REMOTE" ->
                    link(s.name, s.attrStr("MQCA_XMIT_Q_NAME"), EdgeStyle.DASHED)
                // 传输队列没有指向通道的属性，反向查：通道的 XMITQ == 本传输队列名
                s.type == MqObjectType.QUEUE &&
                    s.queueType() == "MQQT_LOCAL" && s.usage() == MQUS_TRANSMISSION ->
                    for (c in all) if (c.type == MqObjectType.CHANNEL &&
                        c.channelXmitQ() == s.name
                    ) {
                        link(s.name, c.name, EdgeStyle.DASHED)
                    }
                // 主题 → 订阅：优先用订阅自带的所属主题对象名（MQCA_TOPIC_NAME）；
                // 缺失时按主题串做宽松匹配（去掉尾部 '/' 后相等）。
                s.type == MqObjectType.TOPIC ->
                    for (sub in all) if (sub.type == MqObjectType.SUBSCRIPTION) {
                        val byName_ = sub.attrStr("MQCA_TOPIC_NAME") == s.name
                        val byStr = sub.attrStr("MQCA_TOPIC_NAME").isNullOrBlank() &&
                            topicStringsMatch(sub.subscriptionTopicStr(), s.attrStr("MQCA_TOPIC_STRING") ?: s.name)
                        if (byName_ || byStr) link(s.name, sub.name, null)
                    }
                // 订阅 → 目标队列（DEST；可为 local / remote / alias）
                s.type == MqObjectType.SUBSCRIPTION ->
                    link(s.name, s.subscriptionDest(), null)
                // 本地队列 / 通道等：由对端（alias / remote / xmitq / 订阅）连回来
                else -> {}
            }
        }

        val nodes = mutableListOf<TopologyNode>()
        val edges = mutableListOf<TopologyEdge>()
        val seen = mutableSetOf<String>()
        // 邻接表是无向的，BFS 两个方向都会经过同一对节点 —— 按「无序节点对」去重，
        // 每条链路只生成一条边，避免重复连线。
        val seenEdges = mutableSetOf<String>()
        // rootName 为空（导航「拓扑图」工具项，未指定起点）→ 「全量局部链路」模式：
        // 以所有节点为种子做 BFS，返回整张单机图（单 QMgr 规模不大）。
        // 指定起点则按该对象 BFS 展开 depth 层（向上游/下游）。
        val explicitRoot = rootName.takeIf { it.isNotBlank() }
        val seeds = explicitRoot?.let { setOf(it) } ?: byName.keys
        val queue = ArrayDeque<Pair<String, Int>>().apply { seeds.forEach { add(it to 0) } }
        while (queue.isNotEmpty()) {
            val (name, level) = queue.removeFirst()
            if (name in seen || name !in byName) continue
            seen += name
            nodes += byName[name]!!.toNode(connectionId, explicitRoot != null && name == explicitRoot)
            if (level >= depth) continue
            for ((nb, style) in adj[name] ?: emptyList()) {
                val edgeKey = listOf(name, nb).sorted().joinToString("|")
                if (edgeKey !in seenEdges) {
                    seenEdges += edgeKey
                    edges += TopologyEdge(from = name, to = nb, style = style)
                }
                if (nb !in seen) queue += nb to level + 1
            }
        }
        return TopologyGraph(connectionId = connectionId, nodes = nodes, edges = edges)
    }

    private fun MqObjectSnapshot.toNode(connectionId: String, isRoot: Boolean): TopologyNode {
        val (type, badge) = resolveTypeAndBadge()
        return TopologyNode(
            name = name,
            type = type,
            color = when {
                isRoot -> NodeColor.PRIMARY
                isProblem() -> NodeColor.ERROR
                else -> NodeColor.NORMAL
            },
            status = resolveStatus(),
            badge = badge,
            properties = buildProperties(),
            objectRef = ObjectRef(connectionId = connectionId, objectType = type, objectName = name),
        )
    }

    /** IBM MQ 里 alias/remote/xmitq 是 QUEUE + MQIA_Q_TYPE / MQIA_USAGE，映射成我们的字符串 type。 */
    private fun MqObjectSnapshot.resolveTypeAndBadge(): Pair<String, String?> = when (type) {
        MqObjectType.QUEUE -> when (queueType()) {
            "MQQT_ALIAS" -> "alias_queue" to null
            "MQQT_REMOTE" -> "remote_queue" to attrStr("MQCA_REMOTE_Q_MGR_NAME")
            "MQQT_LOCAL" -> if (usage() == MQUS_TRANSMISSION) {
                "xmitq" to null
            } else {
                "queue" to currentDepth?.let { "depth=$it" }
            }
            "MQQT_MODEL" -> "queue" to null
            else -> "queue" to null
        }
        MqObjectType.QUEUE_MANAGER -> "qmgr" to null
        MqObjectType.CHANNEL -> "channel" to channelType
        MqObjectType.TOPIC -> "topic" to null
        MqObjectType.SUBSCRIPTION -> "subscription" to null
        MqObjectType.LISTENER -> "listener" to port?.let { ":$it" }
        else -> "unknown" to null
    }

    private fun MqObjectSnapshot.resolveStatus(): NodeStatus? = when (type) {
        MqObjectType.CHANNEL -> when (channelStatus) {
            "RUNNING" -> NodeStatus.RUNNING
            "RETRYING" -> NodeStatus.RETRYING
            "STOPPED" -> NodeStatus.STOPPED
            "STARTING" -> NodeStatus.STARTING
            "STOPPING" -> NodeStatus.STOPPING
            else -> NodeStatus.UNKNOWN
        }
        MqObjectType.LISTENER -> when (listenerStatus) {
            "RUNNING" -> NodeStatus.RUNNING
            else -> NodeStatus.STOPPED
        }
        else -> null
    }

    private fun MqObjectSnapshot.isProblem(): Boolean =
        channelStatus == "RETRYING" || channelStatus == "STOPPED" ||
            (listenerStatus != null && listenerStatus != "RUNNING")

    private fun MqObjectSnapshot.buildProperties(): Map<String, String> = buildMap {
        port?.let { put("port", it.toString()) }
        channelType?.let { put("channelType", it) }
        currentDepth?.let { put("depth", it.toString()) }
        attrStr("MQCA_REMOTE_Q_MGR_NAME")?.let { put("remoteQmgr", it) }
    }

    /** 队列子类型可读名（MQQT_*）；非队列或缺失返回 null。复用项目既有的 [MqAttributeNameMapper]。 */
    private fun MqObjectSnapshot.queueType(): String? {
        val v = attributes["MQIA_Q_TYPE"] as? Int ?: return null
        return MqAttributeNameMapper.lookupName(v, "MQQT")
    }

    private fun MqObjectSnapshot.usage(): Int =
        (attributes["MQIA_USAGE"] as? Int) ?: 0

    private fun MqObjectSnapshot.attrStr(key: String): String? =
        attributes[key]?.toString()?.takeIf { it.isNotBlank() }

    /**
     * Alias 目标（TARGET）：MQ 9.3+/10 的 INQUIRE_Q 响应不带 TARGQ 参数，
     * 目标经 MQCA_BASE_OBJECT_NAME 携带（MQIA_BASE_TYPE 区分队列/主题）；
     * 旧版键名作回落。
     */
    private fun MqObjectSnapshot.aliasTarget(): String? =
        attrStr("MQCA_BASE_OBJECT_NAME")
            ?: attrStr("MQCA_TARGQ")
            ?: attrStr("MQCA_TARGET_Q_NAME")

    /** 通道的传输队列（XMITQ）：通道族属性用 MQCACH_ 前缀，不是 MQCA_。 */
    private fun MqObjectSnapshot.channelXmitQ(): String? =
        attrStr("MQCACH_XMIT_Q_NAME") ?: attrStr("MQCA_XMIT_Q_NAME")

    /** 订阅的目标队列（DEST；可为 local / remote / alias）。 */
    private fun MqObjectSnapshot.subscriptionDest(): String? = attrStr("MQCACF_DESTINATION")

    /** 订阅的主题串（TOPICSTR）。 */
    private fun MqObjectSnapshot.subscriptionTopicStr(): String? = attrStr("MQCA_TOPIC_STRING")

    /** 主题串宽松匹配：完全相等，或去掉尾部 '/' 后相等（通配符语义的保守近似）。 */
    private fun topicStringsMatch(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return a.trimEnd('/') == b.trimEnd('/')
    }
}

/** MQUS_TRANSMISSION：传输队列用途（cmqc.h，值 = 1）。 */
private const val MQUS_TRANSMISSION = 1

/**
 * 系统对象判定：名称以 `SYSTEM` / `AMQ` 开头的对象（系统队列、系统通道等）不纳入拓扑图。
 * 大小写不敏感匹配，覆盖 MQ 约定俗成的 `SYSTEM.*` 与 `AMQ.*` 命名。
 */
private fun String.isSystemObject(): Boolean =
    startsWith("SYSTEM", ignoreCase = true) || startsWith("AMQ", ignoreCase = true)

private fun List<MqObjectSnapshot>.filterNotSystem(): List<MqObjectSnapshot> =
    filter { !it.name.isSystemObject() }

/**
 * 拓扑数据来源的抽象：把「读缓存 + 连接状态 + 触发扫描」从 [IbmMQTopologyProvider] 里抽出来，
 * 便于单测用假实现替换（mock 缓存），同时把 provider 从 [IbmMQModule] 单例里解耦。
 */
internal interface IbmMqTopologySource {
    fun isConnected(connectionId: String): Boolean
    fun snapshots(connectionId: String, type: MqObjectType): List<MqObjectSnapshot>
    fun queueManager(connectionId: String): MqObjectSnapshot?
    /** 触发一次对象慢扫（未连接时实现应 no-op）。 */
    suspend fun scan(connectionId: String)
}

/** 真实实现：走 [IbmMQService] 的缓存读入口 + [IbmMQModule] 的连接状态守卫。 */
internal class IbmMqServiceTopologySource(
    private val service: IbmMQService,
) : IbmMqTopologySource {
    override fun isConnected(connectionId: String): Boolean =
        IbmMQModule.connectionOrNull(connectionId)?.status == ConnectionStatus.Connected

    override fun snapshots(connectionId: String, type: MqObjectType): List<MqObjectSnapshot> =
        service.cachedObjects(connectionId, type)

    override fun queueManager(connectionId: String): MqObjectSnapshot? =
        service.cachedQueueManager(connectionId)

    override suspend fun scan(connectionId: String) {
        // 未连接时 scanObjects 内部 no-op；连接就绪才真正发起慢扫。
        service.scanObjects(connectionId)
    }
}
