package com.mqclient.ibmmq.ui.topology

import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import com.dk.kuiver.model.buildKuiver
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import androidx.compose.ui.unit.dp

/**
 * 一次构建的完整产物：图模型 + 类型表 + 告警级别表（节点卡片着色与列布局都靠前两者）。
 */
data class MqTopologyData(
    val kuiver: Kuiver,
    val nodeTypes: Map<String, TopologyNodeType>,
    val severities: Map<String, NodeSeverity>,
)

/**
 * 从 [IbmMQModule] 的对象慢扫缓存（[IbmMQService.cachedObjects]）构建 Kuiver 拓扑图。
 *
 * 只读缓存、不碰 MQ：缓存未就绪时返回空图（面板侧由 OBJECTS_SCANNED 事件驱动重建）。
 *
 * 节点：alias / local / remote / xmitq 队列、topic、subscription、sender|requester 通道，
 * 以及从通道 RQMNAME 读出的 REMOTE_QMGR **虚拟节点**（id 加 `rqmgr:` 前缀，避免与真实
 * 对象重名；指向本地 QMgr 自身的通道不画）。QMODEL、系统对象（SYSTEM.* / AMQ.*）不进图。
 *
 * 告警级别（[NodeSeverity]）：
 *  - local queue：currentDepth/maxDepth ≥ [warningThreshold]%（与队列列表页同源阈值）→ WARNING；
 *  - sender/requester 通道：状态 STOPPED / RETRYING → ERROR。
 *
 * 边（方向 = 消息/关系的发出方向，环交由 kuiver 的 BACK 分类标橙）：
 *  1. Alias → Target（TARGET 可指向 local / remote / topic / 另一条 alias，链式）
 *  2. Remote Queue → Transmission Queue（XMITQ）
 *  3. Transmission Queue → Channel（反向查通道的 XMITQ）
 *  4. Topic → Subscription（优先 MQCA_TOPIC_NAME，缺失按 TOPICSTR 宽松匹配）
 *  5. Subscription → Destination（DEST，可为 local / remote / alias）
 *  6. Channel → Remote QMGR（RQMNAME，仅 Sender/Requester）
 */
fun buildKuiverFromCache(connectionId: String, warningThreshold: Int): MqTopologyData {
    val service = IbmMQModule.serviceOrNull()
        ?: return MqTopologyData(Kuiver(), emptyMap(), emptyMap())

    val queues = service.cachedObjects(connectionId, MqObjectType.QUEUE)
        .filterNotSystem()
        .filterNot { it.isModelQueue() }
    val channels = service.cachedObjects(connectionId, MqObjectType.CHANNEL)
        .filterNotSystem()
        .filter { it.isSenderOrRequester() }
    val topics = service.cachedObjects(connectionId, MqObjectType.TOPIC).filterNotSystem()
    val subs = service.cachedObjects(connectionId, MqObjectType.SUBSCRIPTION).filterNotSystem()

    val aliasQueues = queues.filter { it.isAliasQueue() }
    val remoteQueues = queues.filter { it.isRemoteQueue() }
    val xmitQueues = queues.filter { it.isTransmission() }
    val localQmgrName = service.cachedQueueManager(connectionId)?.name

    // ---- 节点 ----
    val nodeTypes = LinkedHashMap<String, TopologyNodeType>()
    val severities = LinkedHashMap<String, NodeSeverity>()
    fun put(id: String, type: TopologyNodeType, severity: NodeSeverity = NodeSeverity.NORMAL) {
        if (id.isNotBlank()) {
            nodeTypes.putIfAbsent(id, type)
            severities.putIfAbsent(id, severity)
        }
    }
    queues.forEach {
        val type = when {
            it.isAliasQueue() -> TopologyNodeType.ALIAS_QUEUE
            it.isRemoteQueue() -> TopologyNodeType.REMOTE_QUEUE
            it.isTransmission() -> TopologyNodeType.TRANSMISSION_QUEUE
            else -> TopologyNodeType.LOCAL_QUEUE
        }
        // 消息积压：currentDepth / maxDepth ≥ 阈值（与队列列表页同一判定，maxDepth 未知不算）。
        val ratio = it.maxDepth?.takeIf { max -> max > 0 }?.let { max ->
            (it.currentDepth ?: 0).toFloat() / max
        } ?: 0f
        val severity = if (type == TopologyNodeType.LOCAL_QUEUE &&
            ratio >= warningThreshold / 100f
        ) {
            NodeSeverity.WARNING
        } else {
            NodeSeverity.NORMAL
        }
        put(it.name, type, severity)
    }
    topics.forEach { put(it.name, TopologyNodeType.TOPIC) }
    subs.forEach { put(it.name, TopologyNodeType.SUBSCRIPTION) }
    channels.forEach {
        // 通道链路故障：状态 STOPPED / RETRYING → ERROR（对齐旧管线 isProblem 语义）。
        val severity = when (it.channelStatus?.uppercase()) {
            "STOPPED", "RETRYING" -> NodeSeverity.ERROR
            else -> NodeSeverity.NORMAL
        }
        put(it.name, TopologyNodeType.CHANNEL, severity)
    }
    // REMOTE_QMGR 虚拟节点：从通道 RQMNAME 收集；指向本地 QMgr 的不算「远程」。
    val remoteQmgrIds = channels.mapNotNull { c ->
        c.getChannelRqmName()
            ?.takeIf { rq -> rq.isNotBlank() && rq != localQmgrName }
            ?.let(::remoteQmgrNodeId)
    }
    remoteQmgrIds.forEach { put(it, TopologyNodeType.REMOTE_QMGR) }
    if (nodeTypes.isEmpty()) return MqTopologyData(Kuiver(), emptyMap(), emptyMap())

    // ---- 边 ----
    val byName = (queues + topics + subs + channels).associateBy { it.name }
    val edges = LinkedHashSet<Pair<String, String>>()
    fun link(from: String?, to: String?) {
        if (from == null || to == null || from == to) return
        if (from in nodeTypes && to in nodeTypes) edges += from to to
    }

    // 1. Alias → Target（目标须是图内已知对象；alias→alias 自然成链）
    for (a in aliasQueues) link(a.name, byName[a.getAliasTarget()]?.name)
    // 2. Remote → XMITQ
    for (r in remoteQueues) link(r.name, r.getRemoteXmitQueue())
    // 3. XMITQ → Channel：传输队列没有指向通道的属性，反向查通道的 XMITQ。
    for (x in xmitQueues) {
        for (c in channels) if (c.getChannelXmitQueue() == x.name) link(x.name, c.name)
    }
    // 4. Topic → Subscription：优先订阅自带的所属主题对象名；缺失按主题串宽松匹配。
    for (t in topics) {
        val topicStr = t.getTopicStringOrName()
        for (s in subs) {
            val byObjectName = s.getSubscriptionTopicObjectName() == t.name
            val byString = s.getSubscriptionTopicObjectName().isNullOrBlank() &&
                topicStringsMatch(s.getSubscriptionTopic(), topicStr)
            if (byObjectName || byString) link(t.name, s.name)
        }
    }
    // 5. Subscription → Destination
    for (s in subs) link(s.name, s.getSubscriptionDestination())
    // 6. Channel → Remote QMGR
    for (c in channels) link(c.name, c.getChannelRqmName()?.takeIf { it != localQmgrName }?.let(::remoteQmgrNodeId))

    val kuiver = buildKuiver {
        nodeTypes.forEach { (id, _) -> addNode(KuiverNode(id, NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp))) }
        edges.forEach { (from, to) -> addEdge(KuiverEdge(fromId = from, toId = to)) }
    }
    return MqTopologyData(kuiver, nodeTypes, severities)
}

/** REMOTE_QMGR 虚拟节点的前缀 + id（避免与真实对象名冲突）。 */
internal const val REMOTE_QMGR_PREFIX = "rqmgr:"

internal fun remoteQmgrNodeId(qmgrName: String): String = REMOTE_QMGR_PREFIX + qmgrName
