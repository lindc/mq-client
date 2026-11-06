package com.mqclient.uicommon

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * 全局按钮体系（Material 3，扁平化）。
 *
 * 主 / 次级 / 危险按钮**共用同一个私有实现 [BaseActionButton]**，物理规格（shape /
 * 内边距 / 4 字宽最小宽度）100% 一致，**只有配色不同**，且**全部无阴影、平贴界面**：
 * - [PrimaryButton]：主操作（创建 / 保存 / 发送 / 确定 / 重试 / 关闭 / 下一步 / 提交）。
 *   `primary` 深绿底 + `onPrimary` 白字。
 * - [SecondaryButton]：次要操作（取消 / 返回 / 查看全文 / 查看开源许可证 / 复制全文 /
 *   上一步）。`surfaceVariant` 极浅灰绿底 + `onSurfaceVariant` 深灰字。
 * - [DangerButton]：破坏性操作（删除 / 清空 / 重置）。`error` 底 + `onError` 字。
 *
 * 扁平化：elevation 全 0、无 `Modifier.shadow`。交互反馈改用**颜色变化**代替物理下沉——
 * 悬浮时背景微微变深、按下时再深一点（由我们自己的 interactionSource 驱动，见
 * [BaseActionButton]）。调用方不再自行拼 `colors` / `border` / `elevation` / `shadow`。
 *
 * 内边距默认用 [ButtonDefaults.TextButtonContentPadding] 保持弹窗 / 工具栏的紧凑密度；
 * 需要 M3 默认宽松内边距的调用方显式传 [ButtonDefaults.ContentPadding]。
 */

private const val DISABLED_CONTAINER_ALPHA = 0.12f
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * 底层按钮实现：主 / 次级 / 危险按钮都走它，**除了颜色，其它 100% 一样**。
 *
 *  - `shape = RoundedCornerShape(8.dp)`：固定圆角。
 *  - **零阴影**：elevation 全部 0.dp，不画任何 `Modifier.shadow`，按钮像纸片一样平贴。
 *  - **悬浮 / 按下反馈用色变代替阴影**：自己的 `interactionSource` 探测 hover / press，
 *    把背景色朝黑方向 lerp 一点点（hover 0.06、press 0.12），给出「沉下去」的手感。
 *  - 高度由 M3 `Button` 默认最小高度 + 统一 `contentPadding` 决定，主 / 次级 / 危险一致。
 *
 * [IconButton] / `TextButton` / `ToolbarButton` 不套这套，保持扁平无底色。
 */

@Composable
private fun BaseActionButton(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    standardMinWidth: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    // 扁平化：交互反馈用颜色变化代替物理阴影。
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val resolvedContainerColor = when {
        // 朝黑色 lerp 一点点：悬浮略深、按下更深，模拟「按下去」而不靠阴影。
        isPressed -> lerp(containerColor, Color.Black, 0.12f)
        isHovered -> lerp(containerColor, Color.Black, 0.06f)
        else -> containerColor
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .then(
                standardMinWidthModifier(
                    standardMinWidth = standardMinWidth,
                    contentPadding = contentPadding,
                ),
            ),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = resolvedContainerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA),
        ),
        // 扁平：所有 elevation 归零，绝无浮起阴影。
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            hoveredElevation = 0.dp,
            focusedElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        interactionSource = interactionSource,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * 主操作按钮：实心 [MaterialTheme.colorScheme.primary] 底 +
 * [MaterialTheme.colorScheme.onPrimary] 文字，无边框、无阴影。
 *
 * 禁用态为 M3 标准：`onSurface@12%` 底 + `onSurface@38%` 文字。
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
    BaseActionButton(
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
 * [MaterialTheme.colorScheme.onError] 文字，无边框、无阴影。
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
    BaseActionButton(
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
 * 次要操作按钮：保留旧入口名，实现委托给 [AppSecondaryButton]。
 * 结构与 [PrimaryButton] 完全一致（共用 [BaseActionButton]），只有配色不同；业务层无需改动。
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
    AppSecondaryButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        standardMinWidth = standardMinWidth,
        content = content,
    )
}

/**
 * 次级操作按钮的统一实现：与 [PrimaryButton] 共用 [BaseActionButton]，**除了颜色 100% 一样**。
 *
 *  - 背景 [MaterialTheme.colorScheme.surfaceVariant]（极浅灰绿实心底，作为无阴影下的底色区分）；
 *  - 文字 [MaterialTheme.colorScheme.onSurfaceVariant]（深灰，清晰可读）；
 *  - 同样的 8.dp 圆角，和「关闭」等主按钮放在一起时高度 / 圆角完全一致；
 *  - 无边框、无阴影，扁平化；悬浮 / 按下时背景微微变深代替阴影反馈。
 *
 * 一律使用 [BaseActionButton]，禁止再用 `OutlinedButton` / `TextButton` / `BorderStroke` / 阴影。
 * 禁用态沿用 [BaseActionButton] 的灰色让位。
 */
@Composable
fun AppSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    standardMinWidth: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    BaseActionButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        contentPadding = contentPadding,
        standardMinWidth = standardMinWidth,
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
