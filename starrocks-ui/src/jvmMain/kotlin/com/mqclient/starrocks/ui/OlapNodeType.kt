package com.mqclient.starrocks.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mqclient.graph.TopologyNode
import com.mqclient.graph.TopologyNodeType
import com.mqclient.topology.FallbackTopologyNodeStyle
import com.mqclient.topology.TopologyLayoutProvider
import com.mqclient.topology.TopologyNodeStyleProvider
import com.mqclient.topology.twoColumnLayoutProvider

/**
 * StarRocks 集群拓扑的节点角色：
 *  - [FE] Frontend：查询接入 / 元数据节点，星形拓扑的中心；
 *  - [BE] Backend：存储 / 计算节点，作为 FE 的子节点连线。
 */
enum class OlapNodeType : TopologyNodeType {
    FE,
    BE,
}

/**
 * StarRocks 的节点样式表。两个 `when` 穷尽 [OlapNodeType]——新增节点角色而不补图标 /
 * 颜色是编译错误；兜底只处理「不是 OlapNodeType」。
 *
 * BE 不存活由 [com.mqclient.graph.NodeSeverity] 覆盖为红色（见 topology-ui 卡片的
 * severity 分支），与概览页「Dead 红」同口径。
 */
object StarRocksNodeStyle : TopologyNodeStyleProvider {

    override fun getIcon(node: TopologyNode): ImageVector {
        val type = node.type as? OlapNodeType ?: return FallbackTopologyNodeStyle.getIcon(node)
        return when (type) {
            // FE 是查询接入层用 Storage（元数据 / 调度语义），BE 是存储计算节点用 Dns。
            OlapNodeType.FE -> Icons.Filled.Storage
            OlapNodeType.BE -> Icons.Filled.Dns
        }
    }

    override fun getColor(node: TopologyNode): Color {
        val type = node.type as? OlapNodeType ?: return FallbackTopologyNodeStyle.getColor(node)
        return when (type) {
            // FE 靛蓝（中心权威节点）、BE 蓝绿（存活计算节点）。
            OlapNodeType.FE -> Color(0xFF4F46E5)
            OlapNodeType.BE -> Color(0xFF0D9488)
        }
    }
}

/**
 * FE | BE 的星形投影 —— 单个 FE 在左列，所有 BE 在右列，每条 FE→BE 边是一条
 * 「接入层 → 存储层」连线。复用通用两列布局算法，不新造布局。
 */
val starRocksFeBeLayout: TopologyLayoutProvider =
    twoColumnLayoutProvider { it.type == OlapNodeType.BE }
