package com.mqclient.uicommon.dialog

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * 弹窗尺寸档位。**所有** [ObjectDetailDialog] 弹窗的尺寸统一由公共外壳按屏幕比例计算，
 * 业务模块只能选择语义档位，不得再写死 dp 宽高。
 *
 * 背景：历史上 20+ 个调用点各自传 `minWidth/minHeight`（400~780dp 不等），
 * RocketMQ 消息对话框还单独覆盖了 800×600 初始尺寸 —— 尺寸既不统一，偏大的 min
 * 又会经 `setMinimumSize` 在小屏顶出屏幕。现全部收敛到本规范。
 */
enum class DialogSizeClass(val screenFraction: Float) {
    /** 属性 / 消息 / 消息详情等大内容弹窗：屏幕的 85%。 */
    CONTENT(0.85f),

    /** 创建 / 绑定 / 转发 / 发送等表单弹窗：屏幕的 60%。 */
    FORM(0.60f),
}

/** 弹窗尺寸的统一规范常量。 */
object DialogSizeSpec {
    /** 初始尺寸的硬上限：屏幕的 95%，保证任何屏幕都不溢出。 */
    const val MAX_SCREEN_FRACTION = 0.95f

    /** 统一最小尺寸（原各模块 400~780dp 各异，现收敛为一套）。 */
    val MIN_WIDTH: Dp = 480.dp
    val MIN_HEIGHT: Dp = 320.dp
}

/**
 * 纯计算：屏幕尺寸 → 标准弹窗尺寸。抽成无 Compose / 无 AWT 依赖的函数，便于无头单测
 * 验证小屏不溢出；[screenWidth] / [screenHeight] 为已换算成 dp 的屏幕尺寸。
 *
 * 先按档位取比例，再钳到 `[min, 屏幕×95%]` 区间。上限先 `coerceAtLeast(min)`，
 * 避免极小屏上 max < min 导致 `coerceIn` 抛异常。
 */
fun computeStandardDialogSize(
    screenWidth: Dp,
    screenHeight: Dp,
    sizeClass: DialogSizeClass = DialogSizeClass.CONTENT,
): DpSize {
    val maxWidth = (screenWidth * DialogSizeSpec.MAX_SCREEN_FRACTION)
        .coerceAtLeast(DialogSizeSpec.MIN_WIDTH)
    val maxHeight = (screenHeight * DialogSizeSpec.MAX_SCREEN_FRACTION)
        .coerceAtLeast(DialogSizeSpec.MIN_HEIGHT)
    return DpSize(
        width = (screenWidth * sizeClass.screenFraction)
            .coerceIn(DialogSizeSpec.MIN_WIDTH, maxWidth),
        height = (screenHeight * sizeClass.screenFraction)
            .coerceIn(DialogSizeSpec.MIN_HEIGHT, maxHeight),
    )
}
