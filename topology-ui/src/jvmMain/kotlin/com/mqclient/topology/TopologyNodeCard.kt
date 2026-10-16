package com.mqclient.topology

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyNodeType

/** 节点卡片统一尺寸：kuiver 的 NodeDimensions（布局）与此处卡片必须对齐（公开：各面板建图时要用）。 */
const val CARD_WIDTH = 180
const val CARD_HEIGHT = 56

/**
 * 拓扑节点卡片：类型图标 + 对象名。
 *
 * 底色 / 文字走 MaterialTheme；**类型强调色**只用于图标着色与描边（类型级语义色，
 * 不随主题翻转）。悬停加粗描边 + 投影，选中用类型色实色描边 + 更强投影。
 *
 * 告警级别（[severity]）覆盖类型色：队列积压 WARNING → 警告橙，通道故障 ERROR →
 * 红（主题 error 色，随主题适配）——描边与图标同步换色，一眼定位问题节点。
 *
 * 状态投影 / 拖动缩放走 spring 动画：拾起加深投影并弹性放大，松手弹回。
 */
@Composable
fun TopologyNodeCard(
    name: String,
    type: TopologyNodeType,
    isHovered: Boolean = false,
    /** 节点正被拖动：悬停级描边 + 选中级投影（提起感）。 */
    isDragging: Boolean = false,
    isSelected: Boolean = false,
    severity: NodeSeverity = NodeSeverity.NORMAL,
    /** 链路高亮模式下不在选中闭包内：整体压暗，让选中的线路凸显。 */
    dimmed: Boolean = false,
    /** 名称下方的辅助小字（如 Kafka topic 的分区数）；null 时不占位，维持纯名称形态。 */
    badge: String? = null,
) {
    val accent = when (severity) {
        NodeSeverity.ERROR -> MaterialTheme.colorScheme.error
        NodeSeverity.WARNING -> WARNING_ACCENT
        NodeSeverity.NORMAL -> type.getColor()
    }

    // 拖动动效：拾起时投影弹性加深 + 卡片弹性放大，松手弹回 —— 「提离桌面」手感。
    // kuiver 拖动中经 placement 移动节点（不重组合），缩放动画在卡片内部，二者无冲突；
    // 松手提交的位置与松手点一致，无位置跳跃需要动画。
    val shadowElevation by animateDpAsState(
        targetValue = when {
            isSelected || isDragging -> 8.dp
            isHovered -> 4.dp
            else -> 1.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "topology_node_shadow",
    )
    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) DRAG_LIFT_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "topology_node_drag_scale",
    )

    Surface(
        modifier = Modifier
            .width(CARD_WIDTH.dp)
            .height(CARD_HEIGHT.dp)
            .alpha(if (dimmed) 0.25f else 1f)
            // 缩放走 draw 期 lambda 直读动画值：拖动中节点仅 placement 移动，逐帧只更新图层。
            .graphicsLayer {
                scaleX = dragScale
                scaleY = dragScale
            },
        shape = RoundedCornerShape(12.dp),
        // 底色恒为主题表面（不垫类型色块）：选中只用「描边 + 投影」表达，
        // 避免描边外圈与内部填充块两层颜色不一致的割裂感。
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = when {
                isSelected -> 2.dp
                isHovered || isDragging -> 2.dp
                else -> 1.dp
            },
            color = when {
                isSelected -> accent
                isDragging -> accent.copy(alpha = 0.9f)
                isHovered -> accent.copy(alpha = 0.8f)
                else -> accent.copy(alpha = 0.5f)
            },
        ),
        shadowElevation = shadowElevation,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = type.getIcon(),
                contentDescription = type.name,
                modifier = Modifier.size(20.dp),
                tint = accent,
            )
            if (badge == null) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                // badge 形态：名称一行 + 徽标一行（badge 有值时名称压到单行，防止溢出）。
                Column {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** WARNING 级固定警告色（橙）：容量类告警语义，不随主题翻转（与 ERROR 的主题红区分）。 */
private val WARNING_ACCENT = Color(0xFFED6C02)

/** 拖动中卡片的放大倍数：轻微提离感，不盖住相邻节点的可读性。 */
private const val DRAG_LIFT_SCALE = 1.06f
