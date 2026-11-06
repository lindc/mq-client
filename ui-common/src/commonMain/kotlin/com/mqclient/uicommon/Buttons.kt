package com.mqclient.uicommon

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * 全局按钮体系（Material 3）。
 *
 * 三个入口按语义分工，调用方不再自行拼 `colors` / `border`：
 * - [PrimaryButton]：主操作（创建 / 保存 / 发送 / 确定 / 重试）。实心 primary 底。
 * - [SecondaryButton]：次要操作（取消 / 关闭 / 返回 / 刷新）。透明底 + outline 细边框。
 * - [DangerButton]：破坏性操作（删除 / 清空 / 重置）。实心 error 底。
 *
 * 统一禁用态：填充按钮 = onSurface@12% 底 + onSurface@38% 文字、无边框；
 * 次要按钮 = 透明底 + onSurface@38% 文字、**无边框**（禁用时完全不画轮廓，融入背景）。
 * 不再出现「灰色底 + 灰色粗边框」的旧样式。
 *
 * 形状沿用主题的 `shapes.small`（与项目其余控件一致）。内边距默认用
 * [ButtonDefaults.TextButtonContentPadding] 保持弹窗 / 工具栏的紧凑密度；
 * 需要 M3 默认宽松内边距的调用方显式传 [ButtonDefaults.ContentPadding]。
 */

private const val DISABLED_CONTAINER_ALPHA = 0.12f
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * 主操作按钮：实心 [MaterialTheme.colorScheme.primary] 底 +
 * [MaterialTheme.colorScheme.onPrimary] 文字，无边框。
 *
 * 禁用态为 M3 标准：`onSurface@12%` 底 + `onSurface@38%` 文字，无边框。
 *
 * @param standardMinWidth 是否套用「4 个 CJK 字符宽」的最小宽度（按实际字号 +
 *   水平内边距计算），让弹窗底部的短按钮宽度一致；图标按钮传 `false`。
 */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    standardMinWidth: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    FilledAppButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        contentPadding = contentPadding,
        standardMinWidth = standardMinWidth,
        content = content,
    )
}

/**
 * 危险操作按钮：实心 [MaterialTheme.colorScheme.error] 底 +
 * [MaterialTheme.colorScheme.onError] 文字，无边框。
 *
 * 用于删除 / 清空 / 重置等破坏性确认。禁用态与 [PrimaryButton] 相同
 * （灰色让位，避免禁用后仍显示高饱和红色误导可点）。
 */
@Composable
fun DangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    standardMinWidth: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    FilledAppButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        contentPadding = contentPadding,
        standardMinWidth = standardMinWidth,
        content = content,
    )
}

/**
 * 次要操作按钮：透明底 + 1dp [MaterialTheme.colorScheme.outline] 边框 +
 * [MaterialTheme.colorScheme.primary] 文字。
 *
 * 可用态边框取 `ButtonDefaults.outlinedButtonBorder(enabled = true)`；
 * **禁用态显式传 `border = null`**——`OutlinedButton` 默认会给禁用态留一条
 * `outline@12%` 的极浅轮廓，按规范禁用按钮不允许出现任何边框，因此必须覆盖掉，
 * 只保留透明底 + `onSurface@38%` 文字。
 */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    standardMinWidth: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.then(
            standardMinWidthModifier(
                standardMinWidth = standardMinWidth,
                contentPadding = contentPadding,
            ),
        ),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA),
        ),
        // 禁用态绝对无边框；不要用默认的 outlinedButtonBorder(enabled)，
        // 它在 disabled 时会返回 outline@12% 的可见轮廓。
        border = if (enabled) ButtonDefaults.outlinedButtonBorder(enabled = true) else null,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
private fun FilledAppButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    contentPadding: PaddingValues,
    standardMinWidth: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.then(
            standardMinWidthModifier(
                standardMinWidth = standardMinWidth,
                contentPadding = contentPadding,
            ),
        ),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA),
        ),
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * 弹窗底部短按钮的统一最小宽度：约 4 个 CJK 字符 + 水平内边距，随用户字号缩放。
 * 图标按钮传 `standardMinWidth = false` 退出该约定。
 */
@Composable
private fun standardMinWidthModifier(
    standardMinWidth: Boolean,
    contentPadding: PaddingValues,
): Modifier {
    if (!standardMinWidth) return Modifier
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // 按钮文字样式是 labelLarge；主题若没给具体字号则退回 M3 默认 14sp。
    val fontSize = MaterialTheme.typography.labelLarge.fontSize.takeIf { it.isSpecified } ?: 14.sp
    val charWidth = with(density) { fontSize.toDp() }
    val horizontalPadding = with(density) {
        contentPadding.calculateLeftPadding(layoutDirection) +
            contentPadding.calculateRightPadding(layoutDirection)
    }
    return Modifier.defaultMinSize(minWidth = charWidth * 4 + horizontalPadding)
}
