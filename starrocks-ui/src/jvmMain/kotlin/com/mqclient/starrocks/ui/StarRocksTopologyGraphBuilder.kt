package com.mqclient.starrocks.ui

import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import com.dk.kuiver.model.buildKuiver
import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyData
import com.mqclient.graph.TopologyNode
import com.mqclient.starrocks.cluster.ClusterNodeInfo
import com.mqclient.starrocks.cluster.ClusterNodeRole
import com.mqclient.starrocks.cluster.ClusterSnapshot
import com.mqclient.topology.CARD_HEIGHT
import com.mqclient.topology.CARD_WIDTH

/** 磁盘占用达到这个百分比就把节点标成 WARNING（与 StarRocks Web UI 的橙色系同口径）。 */
internal const val DISK_WARNING_PERCENT = 85.0

/** 内存使用率达到这个百分比同样标 WARNING。 */
internal const val MEM_WARNING_PERCENT = 90.0

/**
 * 从 [ClusterSnapshot] 构建 StarRocks 三列集群拓扑（纯函数，jvmTest 直测）。
 *
 * ```
 * Catalog ──▶ FE(leader) ──▶ BE / CN
 *               ▲   │
 *               └───┘ leader ─▶ follower / observer
 * ```
 *
 * 连线口径（都是**通信 / 依赖方向**，由 kuiver 分类成 FORWARD，回流边标橙）：
 *  - leader FE → 每个 BE / CN：FE 向 BE 下发查询、BE 向 FE 心跳；
 *  - leader FE → 每个 follower / observer：元数据复制；
 *  - leader FE → 每个 catalog：外部目录的元数据由 FE 读取。
 *
 * 降级：没有 FE 数据（`SHOW FRONTENDS` 被拒）时，用连接主机名合成一个
 * `FE_LEADER` 占位节点 —— 接入层永远存在，BE 仍然挂在它下面，图不会散架。
 *
 * badge 只放**一条**最有信息量的数字（卡片宽 180dp，两条就挤掉名称了）：
 * FE 显角色、BE/CN 显磁盘占用、Catalog 显类型。
 */
internal fun buildStarRocksTopology(
    snapshot: ClusterSnapshot,
    fallbackFeName: String,
): TopologyData {
    val nodes = LinkedHashMap<String, TopologyNode>()

    val frontends = snapshot.nodes.filter { it.role.isFrontend }
    val computeNodes = snapshot.nodes.filter { it.role.isCompute }
    val catalogs = snapshot.nodes.filter { it.role == ClusterNodeRole.CATALOG }

    // ---- 接入层：FE（缺失但有其他节点时合成占位）----
    // 什么都不缺时才不画：两个查询都失败（无 FE 也无 BE）应该是**空图**（面板走空状态），
    // 而不是一张只有问号占位节点的图。
    if (frontends.isEmpty()) {
        val hasOtherNodes = computeNodes.isNotEmpty() || catalogs.isNotEmpty()
        if (hasOtherNodes) {
            val placeholderId = "fe:$fallbackFeName"
            nodes[placeholderId] = TopologyNode(id = placeholderId, type = OlapNodeType.FE_LEADER)
        }
    } else {
        frontends.forEach { fe ->
            nodes[fe.nodeId] = TopologyNode(
                id = fe.nodeId,
                type = fe.role.toOlapNodeType(),
                severity = if (fe.alive) NodeSeverity.NORMAL else NodeSeverity.ERROR,
                // FE 的 badge 用角色（LEADER / FOLLOWER / OBSERVER），运维一眼分出权威节点。
                badge = fe.role.frontendBadge(),
            )
        }
    }

    // ---- 执行层：BE / CN ----
    computeNodes.forEach { node ->
        nodes[node.nodeId] = TopologyNode(
            id = node.nodeId,
            type = node.role.toOlapNodeType(),
            severity = node.computeSeverity(),
            badge = node.diskBadge(),
        )
    }

    // ---- 数据源层：外部 Catalog ----
    catalogs.forEach { catalog ->
        nodes[catalog.nodeId] = TopologyNode(
            id = catalog.nodeId,
            type = OlapNodeType.CATALOG,
            badge = catalog.extra["type"]?.uppercase(),
        )
    }

    if (nodes.isEmpty()) return TopologyData(Kuiver(), emptyMap())

    // 连线中心：有 leader 用 leader，否则退到第一个节点（占位 FE / 首个 BE）。
    val hubId = nodes.values.firstOrNull { it.type == OlapNodeType.FE_LEADER }?.id
        ?: nodes.keys.first()

    val edges = LinkedHashSet<Pair<String, String>>()
    fun link(from: String?, to: String?) {
        if (from != null && to != null && from != to && from in nodes && to in nodes) {
            edges += from to to
        }
    }

    // 其余 FE 挂到 leader（元数据复制方向）。
    nodes.values
        .filter { it.type == OlapNodeType.FE_FOLLOWER || it.type == OlapNodeType.FE_OBSERVER }
        .forEach { link(hubId, it.id) }
    // leader → 每个 BE / CN。
    computeNodes.forEach { link(hubId, it.nodeId) }
    // leader → 每个外部目录。
    catalogs.forEach { link(hubId, it.nodeId) }

    val kuiver = buildKuiver {
        nodes.keys.forEach { id ->
            addNode(KuiverNode(id, NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp)))
        }
        edges.forEach { (from, to) -> addEdge(KuiverEdge(fromId = from, toId = to)) }
    }
    return TopologyData(kuiver, nodes)
}

/** FE 卡片 badge：角色短标识（协议原文，不翻译 —— 与 `SHOW FRONTENDS` 的 Role 列对得上）。 */
internal fun ClusterNodeRole.frontendBadge(): String? = when (this) {
    ClusterNodeRole.FE_LEADER -> "LEADER"
    ClusterNodeRole.FE_FOLLOWER -> "FOLLOWER"
    ClusterNodeRole.FE_OBSERVER -> "OBSERVER"
    ClusterNodeRole.BE, ClusterNodeRole.CN, ClusterNodeRole.CATALOG -> null
}

/**
 * BE / CN 的告警级别：不存活 → ERROR；磁盘 / 内存越线 → WARNING。
 * 阈值常量见 [DISK_WARNING_PERCENT] / [MEM_WARNING_PERCENT]。
 */
internal fun ClusterNodeInfo.computeSeverity(): NodeSeverity {
    // 跨模块 public 属性不能 smart cast：先取本地值。
    val diskPercent = diskUsedPercent ?: 0.0
    val memPercent = memUsedPercent ?: 0.0
    return when {
        !alive -> NodeSeverity.ERROR
        diskPercent >= DISK_WARNING_PERCENT -> NodeSeverity.WARNING
        memPercent >= MEM_WARNING_PERCENT -> NodeSeverity.WARNING
        else -> NodeSeverity.NORMAL
    }
}

/** 执行层卡片 badge：磁盘占用（CN 可能没有盘 → 退回内存使用率）。 */
internal fun ClusterNodeInfo.diskBadge(): String? {
    val diskPercent = diskUsedPercent
    val memPercent = memUsedPercent
    return when {
        diskPercent != null -> "${diskPercent.toInt()}% 磁盘"
        memPercent != null -> "${memPercent.toInt()}% 内存"
        else -> null
    }
}
