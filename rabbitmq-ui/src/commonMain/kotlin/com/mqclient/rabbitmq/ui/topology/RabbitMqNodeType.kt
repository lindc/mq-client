package com.mqclient.rabbitmq.ui.topology

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mqclient.graph.TopologyNode
import com.mqclient.graph.TopologyNodeType
import com.mqclient.topology.FallbackTopologyNodeStyle
import com.mqclient.topology.TopologyLayoutProvider
import com.mqclient.topology.TopologyNodeStyleProvider
import com.mqclient.topology.twoColumnLayoutProvider

/**
 * RabbitMQ（AMQP 0.9.1 路由链路 exchange → binding → queue）的节点角色。
 */
enum class RabbitMqNodeType : TopologyNodeType {
    /** 交换机（路由 / 分发角色；含 name="" 的默认交换机）。 */
    EXCHANGE,

    /** 队列（AMQP 0.9.1 语义，区别于 IBM MQ 的 LOCAL_QUEUE）。 */
    RABBIT_QUEUE,
}

/**
 * RabbitMQ 的节点样式表。两个 `when` 穷尽 [RabbitMqNodeType]——新增节点角色而不补
 * 图标 / 颜色是编译错误；兜底只处理「不是 RabbitMqNodeType」。
 */
object RabbitMqNodeStyle : TopologyNodeStyleProvider {

    override fun getIcon(node: TopologyNode): ImageVector {
        val type = node.type as? RabbitMqNodeType ?: return FallbackTopologyNodeStyle.getIcon(node)
        return when (type) {
            // 交换机用 CallSplit（路由分发语义），队列用 Inbox（收件语义）。
            RabbitMqNodeType.EXCHANGE -> Icons.Filled.CallSplit
            RabbitMqNodeType.RABBIT_QUEUE -> Icons.Filled.Inbox
        }
    }

    override fun getColor(node: TopologyNode): Color {
        val type = node.type as? RabbitMqNodeType ?: return FallbackTopologyNodeStyle.getColor(node)
        return when (type) {
            // 交换机青色（路由分发）；队列蓝（取蓝的深阶 1565C0，与 LOCAL_QUEUE 绿、
            // TOPIC 蓝区分）。
            RabbitMqNodeType.EXCHANGE -> Color(0xFF00838F)
            RabbitMqNodeType.RABBIT_QUEUE -> Color(0xFF1565C0)
        }
    }
}

/** 路由链路：exchange（左列）| queue（右列）。 */
val rabbitMqExchangeQueueLayout: TopologyLayoutProvider =
    twoColumnLayoutProvider { it.type == RabbitMqNodeType.RABBIT_QUEUE }
