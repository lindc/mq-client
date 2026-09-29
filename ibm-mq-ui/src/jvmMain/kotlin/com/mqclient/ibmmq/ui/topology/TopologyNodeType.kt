package com.mqclient.ibmmq.ui.topology

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * IBM MQ 拓扑图的节点类型。
 *
 * 与 [com.mqclient.ibmmq.pcf.MqObjectType]（PCF 对象大类）不同：这里是**业务链路角色**
 * —— alias / remote / xmitq 在 MQ 里不是独立对象类型，而是 QUEUE + Q_TYPE / USAGE 的
 * 组合（见 [MqAttributeExtensions]），这里把它们提升成一等节点角色；REMOTE_QMGR 是
 * 从 Channel 的 RQMNAME 读出的**虚拟节点**（MQ 里没有这个对象）。
 *
 * 只画业务链路的 8 类：QMGR（本地）、Listener、Service、Process、Namelist、AuthInfo、
 * CommInfo、QMODEL 一律不进图（属性在对象面板单独看）。
 */
enum class TopologyNodeType {
    ALIAS_QUEUE,
    LOCAL_QUEUE,
    REMOTE_QUEUE,
    TRANSMISSION_QUEUE,
    TOPIC,
    SUBSCRIPTION,
    CHANNEL,
    REMOTE_QMGR,
}

/** 类型 → 图标。图标选择与既有 [com.mqclient.ibmmq.ui.IbmMqTopologyStyle] 保持同族语义。 */
fun TopologyNodeType.getIcon(): ImageVector = when (this) {
    TopologyNodeType.ALIAS_QUEUE -> Icons.AutoMirrored.Filled.AltRoute
    TopologyNodeType.LOCAL_QUEUE -> Icons.Filled.Inbox
    TopologyNodeType.REMOTE_QUEUE -> Icons.Filled.CloudUpload
    // material-icons-extended 没有 Truck，用 LocalShipping（xmitq 既有注册同款）。
    TopologyNodeType.TRANSMISSION_QUEUE -> Icons.Filled.LocalShipping
    TopologyNodeType.TOPIC -> Icons.Filled.Campaign
    TopologyNodeType.SUBSCRIPTION -> Icons.Filled.MarkEmailUnread
    TopologyNodeType.CHANNEL -> Icons.Filled.Link
    TopologyNodeType.REMOTE_QMGR -> Icons.Filled.Dns
}

/**
 * 类型 → 强调色（图标着色 / 卡片描边 / 图例）。
 *
 * 固定 hex 与项目既有约定一致（拓扑样式注册发生在组合之外，拿不到 MaterialTheme）；
 * 这是**类型级**的语义色，类似状态色「客观事实不随主题翻转」。卡片底色、文字、边线等
 * 界面铬色仍走主题（见 [TopologyNodeCard]）。
 */
fun TopologyNodeType.getColor(): Color = when (this) {
    TopologyNodeType.ALIAS_QUEUE -> Color(0xFFF59E0B)
    TopologyNodeType.LOCAL_QUEUE -> Color(0xFF22C55E)
    TopologyNodeType.REMOTE_QUEUE -> Color(0xFF8B5CF6)
    TopologyNodeType.TRANSMISSION_QUEUE -> Color(0xFF14B8A6)
    TopologyNodeType.TOPIC -> Color(0xFF3B82F6)
    TopologyNodeType.SUBSCRIPTION -> Color(0xFFEC4899)
    TopologyNodeType.CHANNEL -> Color(0xFFEF4444)
    TopologyNodeType.REMOTE_QMGR -> Color(0xFF6B7280)
}
