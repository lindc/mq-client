package com.mqclient.ibmmq.ui.topology

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 节点卡片统一尺寸：kuiver 的 NodeDimensions（布局）与此处卡片必须对齐。 */
internal const val CARD_WIDTH = 180
internal const val CARD_HEIGHT = 56

/**
 * 拓扑节点卡片：类型图标 + 对象名。
 *
 * 底色 / 文字走 MaterialTheme；**类型强调色**只用于图标着色与描边（类型级语义色，
 * 不随主题翻转）。悬停加粗描边 + 投影，选中用主题 primary 描边。
 */
@Composable
fun TopologyNodeCard(
    name: String,
    type: TopologyNodeType,
    isHovered: Boolean = false,
    isSelected: Boolean = false,
) {
    val accent = type.getColor()
    Surface(
        modifier = Modifier
            .width(CARD_WIDTH.dp)
            .height(CARD_HEIGHT.dp),
        shape = RoundedCornerShape(12.dp),
        // 底色恒为主题表面（不垫类型色块）：选中只用「描边 + 投影」表达，
        // 避免描边外圈与内部填充块两层颜色不一致的割裂感。
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = when {
                isSelected -> 2.dp
                isHovered -> 2.dp
                else -> 1.dp
            },
            color = when {
                isSelected -> accent
                isHovered -> accent.copy(alpha = 0.8f)
                else -> accent.copy(alpha = 0.5f)
            },
        ),
        shadowElevation = when {
            isSelected -> 8.dp
            isHovered -> 4.dp
            else -> 1.dp
        },
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
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
