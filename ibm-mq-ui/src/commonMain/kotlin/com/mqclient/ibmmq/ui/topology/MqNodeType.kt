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
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mqclient.graph.TopologyNode
import com.mqclient.graph.TopologyNodeType
import com.mqclient.topology.FallbackTopologyNodeStyle
import com.mqclient.topology.TopologyNodeStyleProvider

/**
 * IBM MQ 侧的拓扑节点角色。
 *
 * 值来自 MQ 的**业务链路角色**——alias / remote / xmitq 在 MQ 里不是独立对象类型，
 * 而是 QUEUE + Q_TYPE / USAGE 的组合，这里把它们提升成一等节点角色；
 * REMOTE_QMGR / QMGR_* 是从通道或集群关系推导出的**虚拟节点**（MQ 里没有这个对象）。
 *
 * IBM MQ 侧只画业务链路：Listener / Service / Process / Namelist / AuthInfo /
 * CommInfo / QMODEL 一律不进图（属性在对象面板单独看）。
 */
enum class MqNodeType : TopologyNodeType {
    ALIAS_QUEUE,
    LOCAL_QUEUE,
    REMOTE_QUEUE,
    TRANSMISSION_QUEUE,
    TOPIC,
    SUBSCRIPTION,
    CHANNEL,
    REMOTE_QMGR,
    /** 集群关系图专用：完整仓库 QMgr。 */
    QMGR_FULL,
    /** 集群关系图专用：部分仓库 QMgr。 */
    QMGR_PARTIAL,
}

/**
 * IBM MQ 的节点样式表（图标 / 强调色）。
 *
 * 两个 `when` 都是**穷尽**本枚举的表达式 —— 新增一个 [MqNodeType] 值而不补图标 /
 * 颜色是编译错误。兜底分支只处理「不是 MqNodeType」（别家模块的类型混进图里 /
 * builder 漏标），走通用问号样式。
 *
 * 固定 hex 是**类型级**语义色（类似状态色：客观事实不随主题翻转）；卡片底色、
 * 文字、边线等界面铬色仍走主题（见 topology-ui 的 `TopologyNodeCard`）。
 */
object MqNodeStyle : TopologyNodeStyleProvider {

    override fun getIcon(node: TopologyNode): ImageVector {
        val type = node.type as? MqNodeType ?: return FallbackTopologyNodeStyle.getIcon(node)
        return when (type) {
            MqNodeType.ALIAS_QUEUE -> Icons.AutoMirrored.Filled.AltRoute
            MqNodeType.LOCAL_QUEUE -> Icons.Filled.Inbox
            MqNodeType.REMOTE_QUEUE -> Icons.Filled.CloudUpload
            // material-icons-extended 没有 Truck，用 LocalShipping（xmitq 语义同族）。
            MqNodeType.TRANSMISSION_QUEUE -> Icons.Filled.LocalShipping
            MqNodeType.TOPIC -> Icons.Filled.Campaign
            MqNodeType.SUBSCRIPTION -> Icons.Filled.MarkEmailUnread
            MqNodeType.CHANNEL -> Icons.Filled.Link
            MqNodeType.REMOTE_QMGR -> Icons.Filled.Dns
            // 集群关系图：完整仓库用「仓库」语义图标，部分仓库沿用 QMGR 的服务器图标。
            MqNodeType.QMGR_FULL -> Icons.Filled.Storage
            MqNodeType.QMGR_PARTIAL -> Icons.Filled.Dns
        }
    }

    override fun getColor(node: TopologyNode): Color {
        val type = node.type as? MqNodeType ?: return FallbackTopologyNodeStyle.getColor(node)
        return when (type) {
            MqNodeType.ALIAS_QUEUE -> Color(0xFFF59E0B)
            MqNodeType.LOCAL_QUEUE -> Color(0xFF22C55E)
            MqNodeType.REMOTE_QUEUE -> Color(0xFF8B5CF6)
            MqNodeType.TRANSMISSION_QUEUE -> Color(0xFF14B8A6)
            MqNodeType.TOPIC -> Color(0xFF3B82F6)
            MqNodeType.SUBSCRIPTION -> Color(0xFFEC4899)
            MqNodeType.CHANNEL -> Color(0xFFEF4444)
            MqNodeType.REMOTE_QMGR -> Color(0xFF6B7280)
            // 集群关系图：完整仓库紫（权威仓库），部分仓库灰（同 QMGR 系）。
            MqNodeType.QMGR_FULL -> Color(0xFF7C3AED)
            MqNodeType.QMGR_PARTIAL -> Color(0xFF6B7280)
        }
    }
}
