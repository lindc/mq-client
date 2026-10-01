package com.mqclient.topology

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mqclient.graph.TopologyNodeType

/** 类型 → 图标。图标选择与 IBM MQ 拓扑样式注册同族语义。 */
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
    // 集群关系图：完整仓库用「仓库」语义图标，部分仓库沿用 QMGR 的服务器图标。
    TopologyNodeType.QMGR_FULL -> Icons.Filled.Storage
    TopologyNodeType.QMGR_PARTIAL -> Icons.Filled.Dns
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
    // 集群关系图：完整仓库紫（权威仓库），部分仓库灰（同 QMGR 系）。
    TopologyNodeType.QMGR_FULL -> Color(0xFF7C3AED)
    TopologyNodeType.QMGR_PARTIAL -> Color(0xFF6B7280)
}
