package com.mqclient.mqui.objectlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.uicommon.i18n.stringResource

private const val HOVER_ANIM_MS = 150

/** 卡片内边距：水平 12dp；垂直 10dp（10 + 20 + 4 + 20 + 10 = 卡片定高 64dp）。 */
private val CardPaddingHorizontal = 12.dp
private val CardPaddingVertical = 10.dp

/**
 * 对象卡片：严格 2 行的紧凑卡片。
 *
 * 第 1 行：类型图标（可选）+ 对象名（单行省略）+ 操作按钮（[ObjectCardData.actions]，
 * 图标 + Tooltip，由各 MQ 模块提供）；
 * 第 2 行：状态图标（可选）+ 状态文字（可选）+ 数值（可选）。
 *
 * 交互：单击 [onClick]、双击 [onDoubleClick]（整卡可点，操作按钮除外）；
 * 悬停背景 / 边框 150ms 过渡。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ObjectCard(
    data: ObjectCardData,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit = onClick,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val backgroundColor by animateColorAsState(
        targetValue =
            if (isHovered) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface,
        animationSpec = tween(HOVER_ANIM_MS),
        label = "objectCardBackground",
    )
    val borderColor by animateColorAsState(
        targetValue =
            if (isHovered) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(HOVER_ANIM_MS),
        label = "objectCardBorder",
    )

    Surface(
        modifier = modifier
            .hoverable(interactionSource)
            .pointerInput(onClick, onDoubleClick) {
                detectTapGestures(
                    onTap = { onClick() },
                    onDoubleTap = { onDoubleClick() },
                )
            },
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = CardPaddingHorizontal,
                vertical = CardPaddingVertical,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.heightIn(min = 20.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                data.typeIcon?.let { icon ->
                    val label = data.typeLabel
                    val iconNode: @Composable () -> Unit = {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (label != null) {
                        TooltipArea(
                            tooltip = {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shadowElevation = 4.dp,
                                ) {
                                    Text(
                                        text = label,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                        ) { iconNode() }
                    } else {
                        iconNode()
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = data.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                data.actions.forEach { action ->
                    ActionIconButton(action)
                }
            }
            Row(
                modifier = Modifier.heightIn(min = 20.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧：状态图标 + 状态文字（都可空，分别判断；文字颜色跟
                // 图标 tint 保持一致 —— 「绿字配绿勾」才是同一个状态）。
                if (data.statusIcon != null || data.statusText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        data.statusIcon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = data.statusText,
                                modifier = Modifier.size(14.dp),
                                tint = data.statusIconTint
                                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        data.statusText?.let { text ->
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp,
                                color = data.statusIconTint
                                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                data.metricValue?.let { value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 操作按钮：24dp 点击区 + 16dp 图标，TooltipArea 悬停显示 i18n 提示。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionIconButton(action: CardAction) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = stringResource(action.tooltip),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    ) {
        IconButton(
            onClick = action.onClick,
            enabled = action.enabled,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = stringResource(action.tooltip),
                modifier = Modifier.size(16.dp),
                tint = when {
                    !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    action.isDangerous -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
