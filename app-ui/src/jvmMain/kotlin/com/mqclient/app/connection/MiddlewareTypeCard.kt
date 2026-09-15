package com.mqclient.app.connection

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.app.middlewareIcon

/**
 * 中间件类型选择卡片：图标 + 名称 + 描述，整卡可点，替代标准按钮。
 *
 * 视觉反馈全部走 `animateColorAsState`（150ms）平滑过渡，不生硬跳变：
 *  - 常态：`surface` 底 + `outlineVariant` 边框；
 *  - 悬停：`surfaceContainerHigh` 底 + 边框加深为 `outline`；
 *  - 按下：`surfaceContainerHighest` 底。
 *
 * [androidx.compose.foundation.clickable] 自带焦点与键盘激活（Tab 聚焦后
 * Enter / Space 触发点击），键盘按下的 press 交互同样进 [interactionSource]，
 * 所以按下动画对键盘操作同样成立。`indication = null`：不要 ripple，卡片自己
 * 用背景色表达状态（与仓库侧边栏 / 设置下拉同一惯例）。
 *
 * 高度自适应内容（约束 7），宽度由网格 cell 决定。
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

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isPressed -> MaterialTheme.colorScheme.surfaceContainerHighest
            isHovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(durationMillis = 150),
        label = "middlewareCardBackground",
    )
    val borderColor by animateColorAsState(
        targetValue =
            if (isHovered || isPressed) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(durationMillis = 150),
        label = "middlewareCardBorder",
    )

    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = middlewareIcon(type.id),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = type.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (type.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = type.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
