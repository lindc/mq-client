package com.mqclient.rocketmq.ui.topology

import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import com.dk.kuiver.model.buildKuiver
import com.mqclient.rocketmq.isSystemTopic
import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyData
import com.mqclient.graph.TopologyNodeType
import com.mqclient.topology.CARD_HEIGHT
import com.mqclient.topology.CARD_WIDTH

/**
 * 拓扑图 badge / 展示文案的取词口径：builder 在组合外执行，模板由面板用
 * stringResource 解析好再传进来（语言切换随重组自然刷新）。
 */
class RocketMqTopologyStrings(
    /** topic 节点 badge：「读 N · 写 N 队列」。 */
    val routeBadge: (read: Int, write: Int) -> String,
)

/**
 * 从拓扑快照构建 RocketMQ 拓扑图（纯函数，jvmTest 直测）。
 *
 * 结构：**聚合视图**（两列 topic → broker）——每个 topic 一个 TOPIC 节点（badge =
 * 读写队列数），topic → broker 一条边代表「该 broker 承载此 topic 的 ≥1 个队列」
 * （route 明细投影）。系统主题（%RETRY% / %DLQ% / SCHEDULE_TOPIC / TBW102 等）
 * 默认不进图（[showSystemTopics] 开关）。
 *
 * 告警级别：topic 无写权限（perm 无 4 位）→ WARNING（只读异常，发布不可用）；
 * broker 恒 NORMAL。
 *
 * 节点 id 约定（`:` 不在 RocketMQ topic / broker 命名的合法场景内，解析安全）：
 * topic 用原名；broker `b:<brokerName>`（前缀避免与 topic 名冲突）。
 */
fun buildKuiverFromSnapshot(
    topics: List<RocketMqTopicTopologyInfo>,
    brokerAddresses: Map<String, String>,
    strings: RocketMqTopologyStrings,
    showSystemTopics: Boolean = false,
): TopologyData {
    val visibleTopics = topics.filter { showSystemTopics || !isSystemTopic(it.name) }
    if (visibleTopics.isEmpty() && brokerAddresses.isEmpty()) {
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

    // ---- broker 节点（badge = 地址；地址缺失退化为名称本身）----
    brokerAddresses.forEach { (name, addr) ->
        put(brokerNodeId(name), TopologyNodeType.BROKER, badge = addr.ifBlank { name })
    }

    // ---- topic 节点 ----
    visibleTopics.forEach { topic ->
        // 无写权限（含无路由）→ WARNING：发布不可用，与 perm 语义同源。
        val severity = if (topic.routes.isEmpty() || topic.routes.all { (it.perm and 4) == 0 }) {
            NodeSeverity.WARNING
        } else {
            NodeSeverity.NORMAL
        }
        put(
            id = topicNodeId(topic.name),
            type = TopologyNodeType.TOPIC,
            severity = severity,
            badge = strings.routeBadge(
                topic.routes.sumOf { it.readQueueNums },
                topic.routes.sumOf { it.writeQueueNums },
            ),
        )
    }

    // ---- 边：聚合 topic → 每个承载它的 broker ----
    val edges = LinkedHashSet<Pair<String, String>>()
    fun link(from: String, to: String) {
        if (from != to && from in nodeTypes && to in nodeTypes) edges += from to to
    }
    visibleTopics.forEach { topic ->
        topic.routes.forEach { route ->
            link(topicNodeId(topic.name), brokerNodeId(route.brokerName))
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

/** topic 节点 id（原名）。 */
fun topicNodeId(name: String): String = name

/** broker 节点 id（`b:` 前缀避免与 topic 名冲突）。 */
fun brokerNodeId(name: String): String = "b:$name"
