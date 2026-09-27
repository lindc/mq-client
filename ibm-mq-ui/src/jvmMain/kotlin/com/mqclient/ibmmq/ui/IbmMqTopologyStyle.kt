package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.ui.graphics.Color
import com.mqclient.topology.ui.TopologyStyle
import com.mqclient.topology.ui.TopologyStyleRegistry

/**
 * IBM MQ 各对象类型 → 拓扑节点视觉样式（图标 + 强调色）的登记。
 *
 * 这里把 IBM MQ 的领域概念（[com.mqclient.ibmmq.topology.IbmMQTopologyProvider] 输出的
 * 字符串 type 判别符）映射成具体图标与颜色；[TopologyPanel] 渲染时只按 `(moduleId, type)`
 * 查表，不认识任何 MQ 细节 —— 满足「核心层 / 渲染层不认识具体中间件类型」。
 *
 * 强调色用固定 hex（登记发生在 SPI 初始化、组合之外，拿不到 MaterialTheme）；节点若带语义
 * [com.mqclient.graph.topology.NodeColor]（如 PROBLEM→ERROR），渲染器会用主题色覆盖此处默认值。
 */
object IbmMqTopologyStyle {

    /** 幂等登记：可重复调用（SPI 重复初始化 / 多实例）不重复覆盖。 */
    fun register(serviceId: String) {
        register(serviceId, "queue", Icons.Filled.Inbox, Color(0xFF1565C0))
        register(serviceId, "xmitq", Icons.Filled.LocalShipping, Color(0xFF6A1B9A))
        register(serviceId, "alias_queue", Icons.Filled.AltRoute, Color(0xFF00838F))
        register(serviceId, "remote_queue", Icons.Filled.CloudUpload, Color(0xFFEF6C00))
        register(serviceId, "qmgr", Icons.Filled.Dns, Color(0xFF2E7D32))
        register(serviceId, "channel", Icons.Filled.Link, Color(0xFFAD1457))
        register(serviceId, "topic", Icons.Filled.Campaign, Color(0xFF4527A0))
        register(serviceId, "subscription", Icons.Filled.MarkEmailUnread, Color(0xFF00695C))
        register(serviceId, "listener", Icons.Filled.Hearing, Color(0xFF558B2F))
        register(serviceId, "unknown", Icons.Filled.HelpOutline, Color(0xFF757575))
    }

    private fun register(serviceId: String, type: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color) {
        TopologyStyleRegistry.register(serviceId, type, TopologyStyle(icon, accent))
    }
}
