package com.mqclient.uicommon.command

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor

/**
 * DBeaver 风格的扁平工具栏按钮：默认透明底，悬停浅色底，紧凑（图标 16dp +
 * 13sp 文字）。禁用态整体降透明。hover 用 interactionSource（与 ModuleSidebar
 * 同一套手法），光标用系统手型。
 *
 * [isActive] 用于开关类按钮（「显示系统对象」「只看异常队列」等）的选中态高亮：
 * 激活时文字 / 图标变主题主色，与默认的次要色区分。
 */
@Composable
fun ToolbarButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDangerous: Boolean = false,
    isActive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val handCursor = remember { PointerIcon(Cursor(Cursor.HAND_CURSOR)) }
    val currentOnClick by rememberUpdatedState(onClick)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    !enabled -> Color.Transparent
                    hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interactionSource)
            // 手写点击检测而不是 clickable：clickable 内部自带 focusable，按下会把
            // 焦点从 BasicTextField 抢走并收起选区，「执行」读到空选区就会跑全部
            // 命令（CommandConsoleViewModel.execute 依赖 selection）。工具栏按钮不应
            // 夺焦 —— 与 IDE 工具栏行为一致。
            .pointerInput(enabled) {
                awaitEachGesture {
                    awaitFirstDown()
                    val up = waitForUpOrCancellation()
                    if (up != null && enabled) currentOnClick()
                }
            }
            .pointerHoverIcon(handCursor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            isDangerous -> MaterialTheme.colorScheme.error
            isActive -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = contentColor,
            fontWeight = FontWeight.Medium,
        )
    }
}
