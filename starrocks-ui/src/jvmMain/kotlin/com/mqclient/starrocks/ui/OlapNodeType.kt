package com.mqclient.starrocks.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mqclient.graph.TopologyNode
import com.mqclient.graph.TopologyNodeType
import com.mqclient.starrocks.cluster.ClusterNodeRole
import com.mqclient.topology.FallbackTopologyNodeStyle
import com.mqclient.topology.TopologyLayoutProvider
import com.mqclient.topology.TopologyNodeStyleProvider
import com.mqclient.topology.layeredLayoutProvider

/**
 * StarRocks 集群拓扑的节点角色。
 *
 * | 值 | 语义 | 列 |
 * | --- | --- | --- |
 * | [FE_LEADER] | 主 FE：元数据写入 + 调度，集群权威 | 1 |
 * | [FE_FOLLOWER] | 跟随 FE：元数据可写、参与选主 | 1 |
 * | [FE_OBSERVER] | 观察 FE：只读副本，不参与选主 | 1 |
 * | [BE] | 存储计算节点（有本地盘） | 2 |
 * | [CN] | 无状态计算节点（3.0+，只算不存） | 2 |
 * | [CATALOG] | 外部数据目录（Hive / Iceberg…） | 0 |
 */
enum class OlapNodeType : TopologyNodeType {
    FE_LEADER,
    FE_FOLLOWER,
    FE_OBSERVER,
    BE,
    CN,
    CATALOG,
}

/**
 * StarRocks 的节点样式表。两个 `when` 穷尽 [OlapNodeType]——新增角色而不补图标 /
 * 颜色是编译错误；兜底只处理「不是 OlapNodeType」。
 *
 * BE / CN 不存活由 [com.mqclient.graph.NodeSeverity] 覆盖为红色（见拓扑 builder 的
 * severity 判定），与概览页「Dead 红」同口径。
 */
object StarRocksNodeStyle : TopologyNodeStyleProvider {

    override fun getIcon(node: TopologyNode): ImageVector {
        val type = node.type as? OlapNodeType ?: return FallbackTopologyNodeStyle.getIcon(node)
        return when (type) {
            // 主 FE = 集群权威（Workspaces 的中心语义）；观察者用「只读」的眼睛。
            OlapNodeType.FE_LEADER -> Icons.Filled.Workspaces
            OlapNodeType.FE_FOLLOWER -> Icons.Filled.Storage
            OlapNodeType.FE_OBSERVER -> Icons.Filled.Visibility
            // BE 有盘（服务器节点），CN 只算（内存语义），Catalog 是外部目录。
            OlapNodeType.BE -> Icons.Filled.Dns
            OlapNodeType.CN -> Icons.Filled.Memory
            OlapNodeType.CATALOG -> Icons.Filled.FolderShared
        }
    }

    override fun getColor(node: TopologyNode): Color {
        val type = node.type as? OlapNodeType ?: return FallbackTopologyNodeStyle.getColor(node)
        return when (type) {
            // FE 三档同族递进：主节点最浓，观察者最淡（一眼看出谁是权威）。
            OlapNodeType.FE_LEADER -> Color(0xFF4F46E5)
            OlapNodeType.FE_FOLLOWER -> Color(0xFF6366F1)
            OlapNodeType.FE_OBSERVER -> Color(0xFF94A3B8)
            // 执行层：BE 蓝绿（存储）、CN 青色（纯计算）。
            OlapNodeType.BE -> Color(0xFF0D9488)
            OlapNodeType.CN -> Color(0xFF0891B2)
            // 外部数据源：紫（与 FE 系蓝、执行层绿青区隔）。
            OlapNodeType.CATALOG -> Color(0xFFA855F7)
        }
    }
}

/**
 * 三列集群拓扑：**Catalog（0） | FE（1） | BE / CN（2）**。
 *
 * 列序按数据流方向排：外部目录是数据源 → FE 做元数据 / 查询规划 → BE / CN 执行。
 * 这样 `catalog → FE`、`FE → BE/CN` 的边都只跨一列，不会横穿中间列的卡片。
 *
 * 想改成「FE | BE/CN | Catalog」，只需把这三行的列号换掉（catalog ↔ 右侧）。
 */
val starRocksClusterLayout: TopologyLayoutProvider =
    layeredLayoutProvider { node ->
        when (node.type as? OlapNodeType) {
            OlapNodeType.CATALOG -> 0
            OlapNodeType.FE_LEADER, OlapNodeType.FE_FOLLOWER, OlapNodeType.FE_OBSERVER -> 1
            // BE 与 CN 同为执行层，混合部署时并列；未标注节点兜底到最右列。
            OlapNodeType.BE, OlapNodeType.CN, null -> 2
        }
    }

/** Service 层角色 → 拓扑类型（穷尽 when：新增后端角色不配图标即编译错误）。 */
internal fun ClusterNodeRole.toOlapNodeType(): OlapNodeType = when (this) {
    ClusterNodeRole.FE_LEADER -> OlapNodeType.FE_LEADER
    ClusterNodeRole.FE_FOLLOWER -> OlapNodeType.FE_FOLLOWER
    ClusterNodeRole.FE_OBSERVER -> OlapNodeType.FE_OBSERVER
    ClusterNodeRole.BE -> OlapNodeType.BE
    ClusterNodeRole.CN -> OlapNodeType.CN
    ClusterNodeRole.CATALOG -> OlapNodeType.CATALOG
}
