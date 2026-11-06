package com.mqclient.uicommon

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * 全局按钮体系（Material 3）。
 *
 * 三个入口**共用同一个私有实现 `FilledAppButton`**，物理规格完全一致
 * （shape / contentPadding / elevation / 4 字宽最小宽度），唯一差异是配色：
 * - [PrimaryButton]：主操作（创建 / 保存 / 发送 / 确定 / 重试）。primary 底 + onPrimary 字。
 * - [SecondaryButton]：次要操作（取消 / 关闭 / 返回 / 刷新）。primaryContainer 浅绿底 + primary 字。
 * - [DangerButton]：破坏性操作（删除 / 清空 / 重置）。error 底 + onError 字。
 *
 * 调用方不再自行拼 `colors` / `border` / `elevation`；所有实体按钮都是
 * `Button`（无边框），不再出现描边按钮与实心按钮混用导致的「灰色底 + 粗边框」。
 *
 * 形状沿用主题的 `shapes.small`（与项目其余控件一致）。内边距默认用
 * [ButtonDefaults.TextButtonContentPadding] 保持弹窗 / 工具栏的紧凑密度；
 * 需要 M3 默认宽松内边距的调用方显式传 [ButtonDefaults.ContentPadding]。
 */

private const val DISABLED_CONTAINER_ALPHA = 0.12f
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * 实体按钮的统一抬升：默认 2dp、悬停 4dp、按下 0dp、禁用 0dp。
 *
 * [ButtonDefaults.buttonElevation] 是 `@Composable`，必须在 composable 上下文求值；
 * 写成顶层 `val` 会触发「@Composable invocations can only happen from the context
 * of a @Composable function」（见 30e80e00 的回滚），因此这里用 `@Composable` 函数。
 * 只给实体按钮挂阴影，IconButton / TextButton / ToolbarButton 保持扁平。
 */
@Composable
private fun physicalElevation() = ButtonDefaults.buttonElevation(
    defaultElevation = 2.dp,
    hoveredElevation = 4.dp,
    pressedElevation = 0.dp,
    disabledElevation = 0.dp,
)

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
 * 次要操作按钮：保留旧入口名，实现委托给 [AppSecondaryButton]。
 * 结构与 [PrimaryButton] 完全一致，只有配色不同；业务层无需改动。
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
 * 次级操作按钮的统一实现：与 [PrimaryButton] 共用 `FilledAppButton`，
 * 形状、内边距、抬升、最小宽度逐项一致，只把配色换成 `primaryContainer`
 * 浅绿底 + `primary` 深绿字（与 PrimaryButton 的深绿底区分）。
 *
 * 必须是**不透明**的实心色：透明 container 配 2dp elevation 时，M3 的 Surface
 * 会把 tonal/shadow 叠加暴露成「浅灰块 + 灰边」；深绿实心能吸收，透明底不能。
 * 背景只由 `containerColor` 决定，禁止在调用处套 `background` / `Surface` / `Card`；
 * `Button` 没有 `border` 参数，因此无边框。禁用态沿用 `FilledAppButton` 的灰色让位。
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
    FilledAppButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary,
        contentPadding = contentPadding,
        standardMinWidth = standardMinWidth,
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
        elevation = physicalElevation(),
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
