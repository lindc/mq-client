package com.mqclient.uicommon

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一的纯图标按钮：默认 28dp 点击区 / 18dp 图标 / onSurfaceVariant 图标色。
 *
 * hover 反馈直接用 [IconButton] 原生的 state layer（浅色圆形），不手写 background、
 * 不套任何带边框容器：透明底、无边框、原生 ripple。
 *
 * 两种用法：
 * - 简写：`AppIconButton(onClick = ..., imageVector = Icons.Default.Close, size = 28.dp)`，
 *   图标尺寸 / tint 走统一默认值；
 * - 尾随 [content]：调用方自带图标内容（进度圈、语义色、特殊尺寸等），
 *   此时 [imageVector] 可省略，原有 content 块可保持逐字不动。
 *
 * 表格行内 / 紧凑布局必须显式传 [size]（如 24.dp），否则会撑高行。
 */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 28.dp,
    iconSize: Dp = 18.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: (@Composable () -> Unit)? = null,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
    ) {
        when {
            content != null -> content()
            imageVector != null -> Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
