package com.mqclient.app.connection

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.app.middlewareIcon

/**
 * 中间件类型选择卡片：图标 + 名称 + 描述，整卡可点，替代标准按钮。
 *
 * 悬停 = 阴影抬起 + 边框变主题色（淡），按下 = 阴影落下 + 边框主题色（深），
 * 全部经 `animateDpAsState` / `animateColorAsState`（150ms）平滑过渡：
 *  - 默认：elevation 0、边框 `outlineVariant`；
 *  - 悬停：elevation 4dp、边框 `primary` 50% 透明度；
 *  - 按下：elevation 0（落回）、边框 `primary`。
 *
 * `Modifier.shadow(..., clip = false)`：阴影画在卡片轮廓外，clip 会把它裁掉。
 * [androidx.compose.foundation.clickable] 自带焦点与键盘激活（Tab 聚焦后
 * Enter / Space 触发点击），键盘按下同样进 [interactionSource]，按下动画对
 * 键盘操作也成立。`indication = null`：不要 ripple，状态由阴影和边框表达。
 *
 * 高度自适应内容，宽度由网格 cell 决定。
 */
@Composable
internal fun MiddlewareTypeCard(
    type: MiddlewareType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val shape = RoundedCornerShape(12.dp)
    val elevation by animateDpAsState(
        targetValue = if (isHovered) 4.dp else 0.dp,
        animationSpec = tween(durationMillis = 150),
        label = "middlewareCardElevation",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isPressed -> MaterialTheme.colorScheme.primary
            isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(durationMillis = 150),
        label = "middlewareCardBorder",
    )

    Surface(
        modifier =
            modifier
                .shadow(elevation, shape, clip = false)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = middlewareIcon(type.id),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = type.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                // 描述固定占 2 行（短描述留白补齐）：网格里所有卡片等高，不会因
                // 某行描述只有 1 行而参差。
                Text(
                    text = type.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
