@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.mqclient.uicommon

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 统一的悬停提示（全项目 Tooltip 事实标准的收敛）：TooltipArea + Surface
 * （4dp 圆角 / surfaceVariant 跟动态主题色相走 / 4dp 投影），文字颜色由 Surface
 * 的 contentColor 自动配对成 onSurfaceVariant。
 *
 * 背景：此前 ObjectCard 操作提示、IBM MQ 的 HoverTooltip、RabbitMQ 节点提示三处
 * 各自手写同一段 Surface 样式（rabbitmq 曾用 inverseSurface 反色对 —— 在 MaterialKolor
 * TonalSpot 彩色主题下近黑、与全局观感脱节）。抽取后一处改、全局生效。
 *
 * 浅色主题 = 浅灰底深字，深色主题 = 深灰底浅字，均带主题色调。
 */
@Composable
fun AppTooltip(
    tooltip: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    delayMillis: Int = 400,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    content = tooltip,
                )
            }
        },
        delayMillis = delayMillis,
        modifier = modifier,
        content = content,
    )
}

/** 文字型提示的便捷重载（绝大多数调用点只是一句话）。 */
@Composable
fun AppTooltip(
    text: String,
    modifier: Modifier = Modifier,
    delayMillis: Int = 400,
    content: @Composable () -> Unit,
) {
    AppTooltip(
        tooltip = { Text(text = text, style = MaterialTheme.typography.bodySmall) },
        modifier = modifier,
        delayMillis = delayMillis,
        content = content,
    )
}
