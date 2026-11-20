package com.mqclient.kafka.ui.topology

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
 * Kafka 聚合拓扑的节点角色：topic 与其承载 broker（分区级明细在主题属性对话框的
 * 「分区」Tab，不进图）。
 */
enum class KafkaNodeType : TopologyNodeType {
    TOPIC,
    BROKER,
}

/**
 * Kafka 的节点样式表。两个 `when` 穷尽 [KafkaNodeType]——新增节点角色而不补图标 /
 * 颜色是编译错误；兜底只处理「不是 KafkaNodeType」（builder 漏标 / 别家类型）。
 */
object KafkaNodeStyle : TopologyNodeStyleProvider {

    override fun getIcon(node: TopologyNode): ImageVector {
        val type = node.type as? KafkaNodeType ?: return FallbackTopologyNodeStyle.getIcon(node)
        return when (type) {
            KafkaNodeType.TOPIC -> Icons.Filled.Campaign
            KafkaNodeType.BROKER -> Icons.Filled.Dns
        }
    }

    override fun getColor(node: TopologyNode): Color {
        val type = node.type as? KafkaNodeType ?: return FallbackTopologyNodeStyle.getColor(node)
        return when (type) {
            KafkaNodeType.TOPIC -> Color(0xFF3B82F6)
            KafkaNodeType.BROKER -> Color(0xFF10B981)
        }
    }
}

/** 聚合视图：topic（左列）| broker（右列）。 */
val kafkaTopicBrokerLayout: TopologyLayoutProvider =
    twoColumnLayoutProvider { it.type == KafkaNodeType.BROKER }
