package com.mqclient.rocketmq.ui.topology

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Dns
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mqclient.graph.TopologyNode
import com.mqclient.graph.TopologyNodeType
import com.mqclient.topology.FallbackTopologyNodeStyle
import com.mqclient.topology.TopologyLayoutProvider
import com.mqclient.topology.TopologyNodeStyleProvider
import com.mqclient.topology.twoColumnLayoutProvider

/**
 * RocketMQ 聚合拓扑的节点角色：topic 与其承载 broker（读写队列明细在 route 投影里，
 * 进节点 badge，不进图）。
 *
 * 与 Kafka 的节点角色语义相似但**各自独立**：两边是各自的枚举，配色撞车纯属审美巧合，
 * 任何一边改风格都不会牵动另一边（重构前二者共用同一批全局枚举值）。
 */
enum class RocketMqNodeType : TopologyNodeType {
    TOPIC,
    BROKER,
}

/**
 * RocketMQ 的节点样式表。两个 `when` 穷尽 [RocketMqNodeType]——新增节点角色而不补
 * 图标 / 颜色是编译错误；兜底只处理「不是 RocketMqNodeType」。
 */
object RocketMqNodeStyle : TopologyNodeStyleProvider {

    override fun getIcon(node: TopologyNode): ImageVector {
        val type = node.type as? RocketMqNodeType ?: return FallbackTopologyNodeStyle.getIcon(node)
        return when (type) {
            RocketMqNodeType.TOPIC -> Icons.Filled.Campaign
            RocketMqNodeType.BROKER -> Icons.Filled.Dns
        }
    }

    override fun getColor(node: TopologyNode): Color {
        val type = node.type as? RocketMqNodeType ?: return FallbackTopologyNodeStyle.getColor(node)
        return when (type) {
            RocketMqNodeType.TOPIC -> Color(0xFF3B82F6)
            RocketMqNodeType.BROKER -> Color(0xFF10B981)
        }
    }
}

/** 聚合视图：topic（左列）| broker（右列）。 */
val rocketMqTopicBrokerLayout: TopologyLayoutProvider =
    twoColumnLayoutProvider { it.type == RocketMqNodeType.BROKER }
