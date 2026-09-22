package com.mqclient.uicommon

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * A raised button that drops its shadow and shrinks slightly while pressed —
 * like a physical key being pushed in — then springs back on release.
 *
 * The shadow is driven by [shadowElevation] (0 while pressed), and the shrink
 * by animating [Modifier.scale] off the button's [MutableInteractionSource].
 *
 * Buttons follow the theme: [containerColor] defaults to the primary role
 * (Material's "p40" — primary palette, tone 40) and [contentColor] to its
 * contrasting [androidx.compose.material3.ColorScheme.onPrimary]. Every action
 * button in the app uses this component, so they all stay in sync; the native
 * menu bar / dropdown menu items use Material's own styles and are excluded.
 *
 * 标准宽度：[standardMinWidth] 为 true（默认）时，按钮有「4 个 CJK 字符宽」的
 * 最小宽度（按按钮实际字号 + 水平内边距动态计算，随用户字体缩放走）——文字不超过
 * 4 个字的按钮（关闭 / 取消 / 保存 / 上一步…）宽度全部一致，短按钮不再参差；文字
 * 更长的按钮自然变宽，不受影响。图标按钮（如 ✕）传 `false` 退出这个约定。
 *
 * @param standardMinWidth 是否套用「4 字宽」的最小宽度；纯图标内容必须传 `false`。
 * @param danger 危险操作样式：为 true 时背景走 `colorScheme.error`、文字走
 *   `colorScheme.onError`（语义色，默认调色板保证对比度 ≥ 4.5:1），并忽略传入的
 *   [containerColor] / [contentColor]。删除连接之类的破坏性操作用它，避免各调用处
 *   各自硬编码 error 色、样式不一致。
 */
@Composable
fun PressableButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    elevation: Dp = 2.dp,
    pressedScale: Float = 0.96f,
    standardMinWidth: Boolean = true,
    danger: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // danger 优先：走 error / onError 语义色对，忽略传入的 containerColor / contentColor。
    val resolvedContainerColor =
        if (danger) MaterialTheme.colorScheme.error else containerColor
    val resolvedContentColor =
        if (danger) MaterialTheme.colorScheme.onError else contentColor
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, label = "press-scale")
    val minWidthModifier = if (standardMinWidth) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        // 按钮文字样式是 labelLarge（ProvideTextStyle 设置）；主题若没给具体字号则退回 M3 默认 14sp。
        val fontSize = MaterialTheme.typography.labelLarge.fontSize.takeIf { it.isSpecified } ?: 14.sp
        val charWidth = with(density) { fontSize.toDp() }
        val horizontalPadding = with(density) {
            contentPadding.calculateLeftPadding(layoutDirection) +
                contentPadding.calculateRightPadding(layoutDirection)
        }
        Modifier.defaultMinSize(minWidth = charWidth * 4 + horizontalPadding)
    } else {
        Modifier
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = resolvedContainerColor,
        contentColor = resolvedContentColor,
        shadowElevation = if (pressed) 0.dp else elevation,
        interactionSource = interactionSource,
        modifier = modifier
            .then(minWidthModifier)
            .scale(scale),
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(
                modifier = Modifier.padding(contentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}