package com.mqclient.uicommon.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import java.awt.Toolkit

/**
 * 屏幕尺寸的 2/3（宽、高各取 2/3），用作对象属性类对话框的默认初始尺寸。
 * 物理像素经当前 [LocalDensity] 换算成 dp，与 `DialogState.size` 的单位一致。
 */
@Composable
fun rememberTwoThirdsScreenSize(): DpSize {
    val density = LocalDensity.current
    return remember(density) {
        val screen = Toolkit.getDefaultToolkit().screenSize
        with(density) {
            DpSize(
                width = screen.width.toFloat().toDp() * 2f / 3f,
                height = screen.height.toFloat().toDp() * 2f / 3f,
            )
        }
    }
}
