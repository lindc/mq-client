package com.mqclient.ibmmq.ui.command

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor

/**
 * DBeaver 风格的扁平工具栏按钮：默认透明底，悬停浅色底，紧凑（图标 16dp +
 * 13sp 文字）。禁用态整体降透明。hover 用 interactionSource（与 ModuleSidebar
 * 同一套手法），光标用系统手型。
 */
@Composable
internal fun ToolbarButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDangerous: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val handCursor = remember { PointerIcon(Cursor(Cursor.HAND_CURSOR)) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    !enabled -> androidx.compose.ui.graphics.Color.Transparent
                    hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .pointerHoverIcon(handCursor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            isDangerous -> MaterialTheme.colorScheme.error
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
