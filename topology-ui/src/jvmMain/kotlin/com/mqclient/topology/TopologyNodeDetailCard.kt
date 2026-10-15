package com.mqclient.topology

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyNodeType

/**
 * 选中节点的详情浮层（画布左下角；点击画布空白或关闭按钮消失）。
 * 告警节点图标同步换警告色。原 IBM MQ 面板私有实现，泛化后各拓扑面板共用。
 */
@Composable
fun TopologyNodeDetailCard(
    name: String,
    type: TopologyNodeType,
    severity: NodeSeverity,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (severity) {
        NodeSeverity.ERROR -> MaterialTheme.colorScheme.error
        NodeSeverity.WARNING -> Color(0xFFED6C02)
        NodeSeverity.NORMAL -> type.getColor()
    }
    Surface(
        modifier = modifier.width(300.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = type.getIcon(),
                contentDescription = type.name,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = type.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
