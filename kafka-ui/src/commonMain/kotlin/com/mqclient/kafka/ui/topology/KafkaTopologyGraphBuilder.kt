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
    /** 内部主题标记（badge 前缀）：「内部主题 · 50 分区」，让"内部主题"开关效果可辨识。 */
    val internalLabel: String,
)

/**
 * 从 [KafkaTopologySnapshot] 构建 Kafka 拓扑图（纯函数，jvmTest 直测）。
 *
 * 结构：**聚合视图**（两列 topic → broker）——每个 topic 一个 TOPIC 节点（badge =
 * 分区数），topic → broker 一条边代表「该 broker 承载此 topic 的 ≥1 个 leader 分区」；
 * 内部主题（__consumer_offsets 等）默认不进图（[showInternalTopics] 开关）。
 * 分区级明细在主题属性对话框的「分区」Tab 查看（分区展开视图已移除）。
 *
 * 告警级别：topic 取自身分区的最差级别（无 leader → ERROR，欠副本 → WARNING）；
 * broker 恒 NORMAL（v1 无独立健康口径）。
 *
 * 节点 id 约定（`:` 不在 Kafka topic 名的合法字符集内，解析安全）：
 * topic 用原名；broker `b:<id>`（前缀避免相互冲突）。
 */
fun buildKuiverFromSnapshot(
    snapshot: KafkaTopologySnapshot,
    strings: KafkaTopologyStrings,
    showInternalTopics: Boolean = false,
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

    // ---- topic 节点 ----
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

    // ---- 边：聚合 topic → 每个 leader 所在 broker ----
    val edges = LinkedHashSet<Pair<String, String>>()
    fun link(from: String, to: String) {
        if (from != to && from in nodeTypes && to in nodeTypes) edges += from to to
    }

    visibleTopics.values.forEach { topic ->
        topic.partitions.mapNotNull { it.leaderId }.distinct().forEach { leader ->
            link(topic.name, brokerNodeId(leader))
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

/** 分区健康：无 leader → ERROR；欠副本（副本数 > ISR 数）→ WARNING。 */
private fun partitionSeverity(p: KafkaPartitionInfo): NodeSeverity = when {
    p.leaderId == null -> NodeSeverity.ERROR
    p.replicaIds.size > p.isrIds.size -> NodeSeverity.WARNING
    else -> NodeSeverity.NORMAL
}

/** broker 节点 id（`b:` 前缀避免与 topic 名冲突）。 */
fun brokerNodeId(id: Int): String = "b:$id"
