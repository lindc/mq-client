package com.mqclient.mqui.objectlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.uicommon.AppTooltip
import com.mqclient.uicommon.i18n.stringResource

/** 卡片固定阴影层数：任何时刻都一致，不随 hover / 点击等任何状态改变。 */
private val CARD_ELEVATION = 2.dp

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
 * 交互：**卡片主体不可点击** —— 整卡是纯展示，没有任何点击 / 悬浮反馈（无 hover、
 * 无抬升、无变色），避免此前动态阴影在弹窗开关后卡死的顽疾，也契合「极简克制」的
 * 视觉规范。所有操作都收敛到右下角的图标按钮（[ObjectCardData.actions]，由各 MQ
 * 模块注入），图标仅在按下时有 Compose 原生水波纹反馈，悬停时颜色与背景均不变化。
 *
 * 阴影固定为 [CARD_ELEVATION]（[Modifier.shadow]，clip = false 避免被圆角裁掉），任何
 * 时刻都一致，不随任何状态改变。
 */
@Composable
fun ObjectCard(
    data: ObjectCardData,
    modifier: Modifier = Modifier,
) {
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
            // 固定阴影：任何时刻都一致，clip = false 避免被圆角裁剪。
            .shadow(elevation = CARD_ELEVATION, shape = CardShape, clip = false),
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
                    ActionIconButton(action, executing = action.id in data.executingActionIds)
                }
            }
        }
    }
}

/** 操作按钮：24dp 点击区 + 16dp 图标，AppTooltip 悬停显示 i18n 提示。
 *
 * 视觉极简：图标颜色固定（普通 = onSurfaceVariant、危险 = error、禁用 = onSurface
 * 30%）—— 鼠标悬浮时颜色与背景**绝不变**。点击反馈用 `Modifier.clickable` 的
 * 原生水波纹（仅按下时出现，且不含 hover state layer），不套
 * [androidx.compose.material3.IconButton]，以免其自带 hover state layer 在悬停时
 * 渲染出浅色圆形底色。
 */
@Composable
private fun ActionIconButton(action: CardAction, executing: Boolean) {
    val enabled = action.enabled && !executing
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        action.isDangerous -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    AppTooltip(tooltip = { Text(text = stringResource(action.tooltip), style = MaterialTheme.typography.bodySmall) }) {
        Box(
            modifier = Modifier
                .size(24.dp)
                // clickable 默认只响应按下（水波纹），不带 hover：悬停时无任何视觉变化。
                .clickable(enabled = enabled, onClick = action.onClick),
            contentAlignment = Alignment.Center,
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
                    tint = tint,
                )
            }
        }
    }
}
