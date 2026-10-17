package com.mqclient.mqui.metric

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val METRIC_CARD_SHAPE = RoundedCornerShape(12.dp)
private const val HOVER_ANIM_MS = 150

/**
 * 通用「指标卡片」：Dashboard 概览页的统计数字卡片（大数字 + 图标标签 + 可选脚注）。
 *
 * 结构：圆角 Surface（outlineVariant 边框、surface 背景）→ Column：图标 + 标签行
 * （onSurfaceVariant 小字）→ 大数字（headlineMedium 粗体，[valueColor]，默认 primary）→
 * 可选 footer 小字（如「就绪: X / 未确认: Y」）。
 *
 * [onClick] 非空时卡片可点击，hover 走与 ObjectCard 同款语言：阴影 0 → 4dp 抬升、
 * 边框 outlineVariant → primary 50%，150ms 动画；null 时纯展示、无 hover 反馈。
 *
 * 告警语义由调用方经 [valueColor] 表达（如消息堆积用语义橙 0xFFED6C02，与 Kafka
 * lag 染色同一约定）；本组件不内置阈值逻辑。
 */
@Composable
fun MetricCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.primary,
    footer: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val clickable = onClick != null

    // hover 只对可点击卡片生效：阴影抬升 + 边框加深（ObjectCard 同款，150ms）。
    val elevation by animateDpAsState(
        targetValue = if (clickable && isHovered) 4.dp else 0.dp,
        animationSpec = tween(HOVER_ANIM_MS),
        label = "metricCardElevation",
    )
    val borderColor by animateColorAsState(
        targetValue =
            when {
                clickable && isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        animationSpec = tween(HOVER_ANIM_MS),
        label = "metricCardBorder",
    )

    Surface(
        modifier = modifier
            // 阴影在 Surface 之外画：clip = false，否则阴影被圆角裁剪（ObjectCard 同款）。
            .shadow(elevation = elevation, shape = METRIC_CARD_SHAPE, clip = false)
            .hoverable(interactionSource, enabled = clickable)
            .pointerInput(onClick) {
                if (onClick != null) {
                    detectTapGestures(onTap = { onClick() })
                }
            },
        shape = METRIC_CARD_SHAPE,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = valueColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
            )
            footer?.let { footerText ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = footerText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
