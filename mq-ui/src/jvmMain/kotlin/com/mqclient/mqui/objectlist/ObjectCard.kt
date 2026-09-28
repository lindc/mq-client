package com.mqclient.mqui.objectlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.uicommon.i18n.stringResource

private const val HOVER_ANIM_MS = 150

/** 卡片圆角与阴影共用同一形状；12dp 与「新建连接」卡片一致（大圆角阴影更柔）。 */
private val CardShape = RoundedCornerShape(12.dp)

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
 * 悬停阴影抬升 + 边框变色（与「新建连接」卡片一致）：默认 0dp、悬停 4dp、
 * 按下 0dp，150ms 过渡。阴影用 [Modifier.shadow]（clip = false，避免被圆角裁掉），
 * 背景固定 surface —— 由阴影做层次，不靠背景色变化。
 *
 * 点击/双击走 [detectTapGestures]（Surface onClick 不支持双击），因此按下状态
 * 由单独的 pointerInput 手动 emit [PressInteraction] 到同一个 interactionSource，
 * 与 hoverable 共用一套交互源。
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
    val isPressed by interactionSource.collectIsPressedAsState()

    // 阴影抬升：与「新建连接」卡片完全一致 —— 默认 0dp、悬停 4dp、按下归零
    //（阴影颜色用默认值，柔和感来自大圆角 + 适中的 elevation，不调 spotColor）。
    val elevation by animateDpAsState(
        targetValue =
            when {
                isPressed -> 0.dp
                isHovered -> 4.dp
                else -> 0.dp
            },
        animationSpec = tween(HOVER_ANIM_MS),
        label = "objectCardElevation",
    )
    // 边框颜色：按下实色 primary、悬停半透明 primary、默认 outlineVariant。
    val borderColor by animateColorAsState(
        targetValue =
            when {
                isPressed -> MaterialTheme.colorScheme.primary
                isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        animationSpec = tween(HOVER_ANIM_MS),
        label = "objectCardBorder",
    )

    Surface(
        modifier = modifier
            // 阴影在 Surface 之外画：clip = false，否则阴影被圆角裁剪。
            .shadow(elevation = elevation, shape = CardShape, clip = false)
            .hoverable(interactionSource)
            // 按下状态：observe down/up 并 emit 到 interactionSource
            //（detectTapGestures 不产生 PressInteraction）。
            .pointerInput(interactionSource) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)
                    // 所有指针抬起（或被取消，同样表现为 pressed=false）即按下结束。
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    interactionSource.tryEmit(PressInteraction.Release(press))
                }
            }
            .pointerInput(onClick, onDoubleClick) {
                detectTapGestures(
                    onTap = { onClick() },
                    onDoubleTap = { onDoubleClick() },
                )
            },
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
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
                    ActionIconButton(action, executing = action.id in data.executingActionIds)
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
private fun ActionIconButton(action: CardAction, executing: Boolean) {
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
            enabled = action.enabled && !executing,
            modifier = Modifier.size(24.dp),
        ) {
            if (executing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
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
}
