package com.mqclient.starrocks.ui

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
 * 从 `SHOW BACKENDS` 结果构建 FE → BE 星形拓扑（纯函数，jvmTest 直测）。
 *
 * 结构：单个 [feName] 作为中心 FE 节点，每个 BE 一个子节点，FE→BE 一条边。
 * 节点 id 带前缀避免 IP 与 FE 名冲突：FE = `fe:<name>`，BE = `be:<ip>:<port>`。
 *
 * 告警：BE 不存活 → [NodeSeverity.ERROR]（卡片描边 / 图标转红，与概览页
 * 「Dead 红」同口径）；存活 → NORMAL。badge 显示 BE 的版本号。
 *
 * 没有 BE 节点时返回**空图**（`Kuiver()`），由面板渲染空状态占位图。
 */
internal fun buildStarRocksTopology(
    feName: String,
    backends: List<ClusterNode>,
): TopologyData {
    if (backends.isEmpty()) return TopologyData(Kuiver(), emptyMap(), emptyMap())

    val feId = feNodeId(feName)
    val nodeTypes = LinkedHashMap<String, TopologyNodeType>()
    val severities = LinkedHashMap<String, NodeSeverity>()
    val badges = LinkedHashMap<String, String>()

    nodeTypes[feId] = TopologyNodeType.OLAP_FE
    severities[feId] = NodeSeverity.NORMAL

    backends.forEach { backend ->
        val id = beNodeId(backend.ip, backend.port)
        nodeTypes[id] = TopologyNodeType.OLAP_BE
        severities[id] = if (backend.alive) NodeSeverity.NORMAL else NodeSeverity.ERROR
        if (backend.version != "-") badges[id] = backend.version
    }

    val kuiver = buildKuiver {
        nodeTypes.keys.forEach { id ->
            addNode(KuiverNode(id, NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp)))
        }
        backends.forEach { backend ->
            addEdge(KuiverEdge(fromId = feId, toId = beNodeId(backend.ip, backend.port)))
        }
    }
    return TopologyData(kuiver, nodeTypes, severities, badges)
}

/** FE 节点 id（前缀隔离）。 */
internal fun feNodeId(name: String): String = "fe:$name"

/** BE 节点 id（IP + 端口保证同一主机多实例也不冲突）。 */
internal fun beNodeId(ip: String, port: String): String = "be:$ip:$port"
