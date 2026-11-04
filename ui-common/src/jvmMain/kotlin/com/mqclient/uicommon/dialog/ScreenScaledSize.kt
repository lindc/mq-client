package com.mqclient.uicommon.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import java.awt.Toolkit

/**
 * 主屏尺寸（像素），**整个进程只查一次**。
 *
 * 为什么提到顶层懒加载：`Toolkit.getDefaultToolkit()` 首次调用会初始化 AWT 的 native
 * 层，而本应用的主线程是 nucleus / Tao 自绘框架、**不是 AWT EDT**（见 [ObjectDetailDialog]
 * 的说明）—— 在非 EDT 线程首次初始化 AWT 是已知的停顿源。原先它写在
 * `remember(density)` 里，而 remember 的作用域只是「当前这个对话框实例」，于是每开一次
 * 对话框就重查一次 `screenSize`，叠加在本来就要建 AMQP 连接的打开路径上。
 *
 * 代价：进程启动后分辨率变更（多显示器切换、缩放调整）不会被感知。对「对话框初始
 * 尺寸取屏幕比例」这个用途可以接受 —— 窗口一旦打开，用户就是手动调整大小，而不是
 * 期待它跟着屏变。
 */
private val screenSize by lazy { Toolkit.getDefaultToolkit().screenSize }

/**
 * 标准弹窗尺寸：按 [DialogSizeClass] 档位取屏幕比例，并钳到 [DialogSizeSpec] 的统一
 * min / 95% 屏幕上限。**全项目所有独立窗口弹窗**（[ObjectDetailDialog] 及 app-ui 里
 * 直接用 `DecoratedDialog` / `Dialog` 的连接、设置、关于窗口）都走这里，业务模块
 * 不再自行写死宽高。物理像素经当前 [LocalDensity] 换算成 dp，与 `DialogState.size`
 * 的单位一致。
 */
@Composable
fun rememberStandardDialogSize(sizeClass: DialogSizeClass = DialogSizeClass.CONTENT): DpSize {
    val density = LocalDensity.current
    return remember(density, sizeClass) {
        val screen = screenSize
        with(density) {
            computeStandardDialogSize(
                screenWidth = screen.width.toFloat().toDp(),
                screenHeight = screen.height.toFloat().toDp(),
                sizeClass = sizeClass,
            )
        }
    }
}

/**
 * 主屏宽度（dp）。用于 [ObjectDetailDialog] 计算窗口最小宽度：内容区要求 900dp，
 * 但小屏上 900dp 的最小窗口会撑出屏幕，需按屏幕 95% 封顶（见
 * [computeWindowMinWidth]）。物理像素经当前 [LocalDensity] 换算成 dp。
 */
@Composable
fun rememberScreenWidth(): Dp {
    val density = LocalDensity.current
    return remember(density) {
        with(density) { screenSize.width.toFloat().toDp() }
    }
}
