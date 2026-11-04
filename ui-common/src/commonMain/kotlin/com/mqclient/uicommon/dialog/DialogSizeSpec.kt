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

    /**
     * 内容区宽度占对话框**窗口**宽度的比例（不是屏幕宽度——窗口本身已是屏幕的
     * 85%/60%，见 [DialogSizeClass]）。由 [computeContentWidth] 统一计算，
     * 调用方无法覆盖。
     */
    const val CONTENT_WIDTH_FRACTION = 0.8f

    /** 内容区最小宽度：窄窗口下保证表格不被压变形，同时也是窗口拖拽下限。 */
    val CONTENT_MIN_WIDTH: Dp = 900.dp
}

/**
 * 内容区宽度曲线：`min(W, max(0.8W, 900dp))`，W 为内容区可用宽度。
 *
 * 等价于 `Modifier.fillMaxWidth(0.8f).widthIn(min = 900.dp)` 但**在 W < 900dp 时
 * 会退回 W 而不是撑出窗口**（被外壳 `clipToBounds` 裁掉右侧）。1125dp 是连续点
 * （0.8 × 1125 = 900），窗口缩放时宽度变化无跳变。
 *
 * 抽成无 Compose / 无 AWT 依赖的纯函数，便于无头单测钉住三档区间。
 */
fun computeContentWidth(availableWidth: Dp): Dp {
    val proportional = availableWidth * DialogSizeSpec.CONTENT_WIDTH_FRACTION
    val floored = if (proportional > DialogSizeSpec.CONTENT_MIN_WIDTH) {
        proportional
    } else {
        DialogSizeSpec.CONTENT_MIN_WIDTH
    }
    return if (availableWidth < floored) availableWidth else floored
}

/**
 * 窗口最小宽度：内容区下限 [DialogSizeSpec.CONTENT_MIN_WIDTH]，但**不超过屏幕的
 * 95%** —— 否则小屏（如 1024×600）上 900dp 的最小窗口会撑出屏幕。
 * 供 `setMinimumSize` 使用，保证用户拖拽缩放时 900dp 约束依然生效。
 */
fun computeWindowMinWidth(screenWidth: Dp): Dp {
    val cap = screenWidth * DialogSizeSpec.MAX_SCREEN_FRACTION
    return if (DialogSizeSpec.CONTENT_MIN_WIDTH < cap) {
        DialogSizeSpec.CONTENT_MIN_WIDTH
    } else {
        cap
    }
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
