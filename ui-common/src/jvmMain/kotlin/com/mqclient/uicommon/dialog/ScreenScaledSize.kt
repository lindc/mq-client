package com.mqclient.uicommon.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
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
 * 尺寸取屏幕 2/3」这个用途可以接受 —— 窗口一旦打开，用户就是手动调整大小，而不是
 * 期待它跟着屏变。
 */
private val screenSize by lazy { Toolkit.getDefaultToolkit().screenSize }

/**
 * 屏幕尺寸的 2/3（宽、高各取 2/3），用作对象属性类对话框的默认初始尺寸。
 * 物理像素经当前 [LocalDensity] 换算成 dp，与 `DialogState.size` 的单位一致。
 */
@Composable
fun rememberTwoThirdsScreenSize(): DpSize {
    val density = LocalDensity.current
    return remember(density) {
        val screen = screenSize
        with(density) {
            DpSize(
                width = screen.width.toFloat().toDp() * 2f / 3f,
                height = screen.height.toFloat().toDp() * 2f / 3f,
            )
        }
    }
}

/**
 * 屏幕尺寸（宽、高各取全屏），用作对话框初始尺寸的封顶基准：需要让对话框在中小屏不溢出
 * 时，用 `(screen.width * 0.95f)` 作为 [ObjectDetailDialog] 的 maxWidth / maxHeight
 * （该 max 只钳制初始尺寸，不限制用户后续手动拉伸）。
 */
@Composable
fun rememberFullScreenSize(): DpSize {
    val density = LocalDensity.current
    return remember(density) {
        val screen = screenSize
        with(density) {
            DpSize(
                width = screen.width.toFloat().toDp(),
                height = screen.height.toFloat().toDp(),
            )
        }
    }
}
