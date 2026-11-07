package com.mqclient.mqui.objectlist

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.mqclient.uicommon.AppIconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.uicommon.AppTooltip
import com.mqclient.uicommon.i18n.stringResource

private const val HOVER_ANIM_MS = 150

/** 卡片圆角与阴影共用同一形状；12dp 与「新建连接」卡片一致（大圆角阴影更柔）。 */
private val CardShape = RoundedCornerShape(12.dp)

/** 卡片内边距：水平 12dp；垂直 10dp（行高随内容，含 24dp 操作按钮时卡片约 72dp）。 */
private val CardPaddingHorizontal = 12.dp
private val CardPaddingVertical = 10.dp

/**
 * 对象卡片：严格 2 行的紧凑卡片。
 *
 * 第 1 行：类型图标（可选）+ 对象名（单行省略）+ 数值（可选，右上角 badge 位，
 * 如队列深度/监听端口）；第 2 行：状态图标（可选，16dp 与操作按钮同尺寸）+
 * 状态文字（可选，左侧）+ 纯展示指标（可选，[ObjectCardData.indicators]，icon + 数字）+
 * 操作按钮（[ObjectCardData.actions]，图标 + Tooltip，
 * 由各 MQ 模块提供，右侧）。左读状态、右做操作，操作按钮不挤压对象名。
 *
 * 交互：单击 [onClick]、双击 [onDoubleClick]（整卡可点，操作按钮除外）；
 * 阴影随 **Hover** 抬升（与「新建连接」卡片一致）：默认 2dp、悬停 6dp、按下
 * 归零，150ms 过渡。**阴影只跟随鼠标 Hover/Press，不绑定点击选中态** —— 选中
 * 一个对象不会让卡片持续浮起，移开鼠标即恢复默认投影（桌面端直觉）。
 * 阴影用 [Modifier.shadow]（clip = false，避免被圆角裁掉），背景固定 surface ——
 * 由阴影做层次，不靠背景色变化。
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

    // 阴影抬升：仅随 Hover / Press 变化，不绑定选中态 —— 点击选中对象不会让卡片
    // 持续浮起。默认 2dp、悬停 6dp、按下归零（阴影颜色用默认值，柔和感来自大圆角
    // + 适中的 elevation，不调 spotColor）。
    val elevation by animateDpAsState(
        targetValue =
            when {
                isPressed -> 0.dp
                isHovered -> 6.dp
                else -> 2.dp
            },
        animationSpec = tween(HOVER_ANIM_MS),
        label = "objectCardElevation",
    )
    // 边框弱化：阴影承担层次，边框降到极低透明度避免「脏」感；告警卡片保留
    // 中等透明的 tint 以便仍然能读出异常态（交互反馈改由阴影抬升提供）。
    val border =
        if (data.alertTint != null) {
            BorderStroke(1.dp, data.alertTint.copy(alpha = 0.5f))
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        }

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
        border = border,
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
                        AppTooltip(tooltip = { Text(text = label, style = MaterialTheme.typography.bodySmall) }) {
                            iconNode()
                        }
                    } else {
                        iconNode()
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = data.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 右上角 badge 位：数值（队列深度 / Lag 等），弱色小字号不与对象名混淆；
                // metricColor 告警语义时换色（如 Lag 超阈值警示橙）。
                data.metricValue?.let { value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = data.metricColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                                modifier = Modifier.size(16.dp),
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
                // 右侧：纯展示指标（icon + 数字，不可点）→ 操作按钮。
                // 左读状态、右做操作，都不挤压对象名。
                data.indicators.forEach { indicator ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = indicator.icon,
                            contentDescription = indicator.label,
                            modifier = Modifier.size(14.dp),
                            tint = indicator.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = indicator.label,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = indicator.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                data.actions.forEach { action ->
                    ActionIconButton(action, cardHovered = isHovered, executing = action.id in data.executingActionIds)
                }
            }
        }
    }
}

/** 操作按钮：24dp 点击区 + 16dp 图标，AppTooltip 悬停显示 i18n 提示。 */
@Composable
private fun ActionIconButton(action: CardAction, cardHovered: Boolean, executing: Boolean) {
    AppTooltip(tooltip = { Text(text = stringResource(action.tooltip), style = MaterialTheme.typography.bodySmall) }) {
        AppIconButton(
            onClick = action.onClick,
            enabled = action.enabled && !executing,
            size = 24.dp,
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
                        cardHovered -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
