package com.mqclient.kafka.ui.topology

import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import com.dk.kuiver.model.buildKuiver
import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyData
import com.mqclient.graph.TopologyNodeType
import com.mqclient.topology.CARD_HEIGHT
import com.mqclient.topology.CARD_WIDTH

/**
 * 拓扑图 badge / 展示文案的取词口径：builder 在组合外执行，模板由面板用
 * stringResource 解析好再传进来（语言切换随重组自然刷新）。
 */
class KafkaTopologyStrings(
    /** topic 节点 badge：「%1$d 分区」。 */
    val partitionsBadge: (Int) -> String,
    /** partition 节点 badge：「leader %1$d」。 */
    val leaderBadge: (Int) -> String,
    /** partition 离线 badge：「无 leader」。 */
    val noLeaderBadge: String,
    /** 内部主题标记（badge 前缀）：「内部主题 · 50 分区」，让"内部主题"开关效果可辨识。 */
    val internalLabel: String,
)

/**
 * 从 [KafkaTopologySnapshot] 构建 Kafka 拓扑图（纯函数，jvmTest 直测）。
 *
 * 规模控制（聚合 + 按需展开）：
 *  - 默认**聚合视图**：每个 topic 一个 TOPIC 节点（badge = 分区数），分区折叠；
 *    topic → broker 一条边代表「该 broker 承载此 topic 的 ≥1 个 leader 分区」；
 *  - [expandedTopic] 非空时展开该 topic：插入它的 PARTITION 节点（badge = leader id），
 *    topic → partition（belongs_to），partition → leader broker（led_by 实线），
 *    partition → 副本 broker（replicated_on，[showReplicaEdges] 开时画，由面板虚线化）；
 *    其余 topic 维持聚合形态；
 *  - 内部主题（__consumer_offsets 等）默认不进图（[showInternalTopics] 开关）。
 *
 * 告警级别（[NodeSeverity]）：
 *  - partition：无 leader → ERROR；副本数 > ISR 数（欠副本）→ WARNING；
 *  - topic：取自身分区的最差级别（离线分区 → ERROR，欠副本 → WARNING）；
 *  - broker：恒 NORMAL（v1 无独立健康口径）。
 *
 * 节点 id 约定（`:` 不在 Kafka topic 名的合法字符集内，解析安全）：
 * topic 用原名；partition `p:<topic>:<id>`；broker `b:<id>`（前缀避免相互冲突）。
 */
fun buildKuiverFromSnapshot(
    snapshot: KafkaTopologySnapshot,
    strings: KafkaTopologyStrings,
    expandedTopic: String? = null,
    showInternalTopics: Boolean = false,
    showReplicaEdges: Boolean = false,
): TopologyData {
    val visibleTopics = snapshot.topics
        .filter { showInternalTopics || !it.internal }
        .associateBy { it.name }
    if (visibleTopics.isEmpty() && snapshot.brokers.isEmpty()) {
        return TopologyData(Kuiver(), emptyMap(), emptyMap())
    }

    val nodeTypes = LinkedHashMap<String, TopologyNodeType>()
    val severities = LinkedHashMap<String, NodeSeverity>()
    val badges = LinkedHashMap<String, String>()

    fun put(id: String, type: TopologyNodeType, severity: NodeSeverity = NodeSeverity.NORMAL, badge: String? = null) {
        nodeTypes.putIfAbsent(id, type)
        severities.putIfAbsent(id, severity)
        badge?.let { badges.putIfAbsent(id, it) }
    }

    // ---- broker 节点 ----
    snapshot.brokers.forEach { b ->
        // host 为空（广告地址缺失等）退化为 id 展示。
        val badge = if (b.host.isNotBlank()) "${b.host}:${b.port}" else "id ${b.id}"
        put(brokerNodeId(b.id), TopologyNodeType.BROKER, badge = badge)
    }

    // ---- topic / partition 节点 ----
    val expanded = expandedTopic?.let { visibleTopics[it] }
    visibleTopics.values.forEach { topic ->
        val severity = topic.partitions
            .map(::partitionSeverity)
            .maxOrNull() ?: NodeSeverity.NORMAL
        val badge = if (topic.internal) {
            "${strings.internalLabel} · ${strings.partitionsBadge(topic.partitions.size)}"
        } else {
            strings.partitionsBadge(topic.partitions.size)
        }
        put(
            id = topic.name,
            type = TopologyNodeType.TOPIC,
            severity = severity,
            badge = badge,
        )
    }
    expanded?.partitions?.forEach { p ->
        val badge = p.leaderId?.let { strings.leaderBadge(it) } ?: strings.noLeaderBadge
        put(partitionNodeId(expanded.name, p.id), TopologyNodeType.PARTITION, partitionSeverity(p), badge)
    }

    // ---- 边 ----
    val edges = LinkedHashSet<Pair<String, String>>()
    fun link(from: String, to: String) {
        if (from != to && from in nodeTypes && to in nodeTypes) edges += from to to
    }

    visibleTopics.values.forEach { topic ->
        val isExpanded = topic.name == expanded?.name
        if (!isExpanded) {
            // 聚合：topic → 每个 leader 所在 broker 一条边。
            topic.partitions.mapNotNull { it.leaderId }.distinct().forEach { leader ->
                link(topic.name, brokerNodeId(leader))
            }
        } else {
            // 展开：topic → 各分区；分区 → leader broker；副本边按开关补。
            topic.partitions.forEach { p ->
                val pid = partitionNodeId(topic.name, p.id)
                link(topic.name, pid)
                p.leaderId?.let { link(pid, brokerNodeId(it)) }
                if (showReplicaEdges) {
                    p.replicaIds
                        .filter { it != p.leaderId }
                        .forEach { link(pid, brokerNodeId(it)) }
                }
            }
        }
    }

    val kuiver = buildKuiver {
        nodeTypes.forEach { (id, _) ->
            addNode(KuiverNode(id, NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp)))
        }
        edges.forEach { (from, to) -> addEdge(KuiverEdge(fromId = from, toId = to)) }
    }
    return TopologyData(kuiver, nodeTypes, severities, badges)
}

/** 汇总中需要标记为「副本边」的（from, to）对：面板据此对边做虚线定制。 */
fun replicaEdgeIds(
    snapshot: KafkaTopologySnapshot,
    expandedTopic: String?,
    showInternalTopics: Boolean = false,
): Set<Pair<String, String>> {
    val topic = snapshot.topics
        .firstOrNull { it.name == expandedTopic && (showInternalTopics || !it.internal) }
        ?: return emptySet()
    return buildSet {
        topic.partitions.forEach { p ->
            p.replicaIds
                .filter { it != p.leaderId }
                .forEach { add(partitionNodeId(topic.name, p.id) to brokerNodeId(it)) }
        }
    }
}

/** 分区健康：无 leader → ERROR；欠副本（副本数 > ISR 数）→ WARNING。 */
private fun partitionSeverity(p: KafkaPartitionInfo): NodeSeverity = when {
    p.leaderId == null -> NodeSeverity.ERROR
    p.replicaIds.size > p.isrIds.size -> NodeSeverity.WARNING
    else -> NodeSeverity.NORMAL
}

/** broker 节点 id（`b:` 前缀避免与 topic 名冲突）。 */
fun brokerNodeId(id: Int): String = "b:$id"

/** partition 节点 id（`p:<topic>:<id>`；`:` 不是 topic 合法字符，解析安全）。 */
fun partitionNodeId(topic: String, partition: Int): String = "p:$topic:$partition"

/** partition 节点 id → 所属 topic 名（供列布局按 topic 泳道分组）。 */
fun partitionNodeTopic(nodeId: String): String =
    nodeId.removePrefix(PARTITION_PREFIX).substringBeforeLast(':')

private const val PARTITION_PREFIX = "p:"
