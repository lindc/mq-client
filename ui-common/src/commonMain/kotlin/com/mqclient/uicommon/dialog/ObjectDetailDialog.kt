package com.mqclient.uicommon.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.mqclient.uicommon.i18n.stringResource
import java.awt.Cursor

/**
 * 无系统标题栏（undecorated）的对象详情对话框：自定义标题栏拖动移动、
 * 8 向边缘手柄调整大小、右下角关闭按钮。
 *
 * 实现要点（compose desktop 1.12，`DialogState` 可变属性为 `position: WindowPosition`
 * 与 `size: DpSize`）：
 *  - `undecorated = true` + `transparent = true`：去掉系统装饰后窗口是矩形，
 *    16dp 圆角必须配透明窗口底才不会露出方形底色；
 *    undecorated 窗口没有系统阴影，视觉深度由 [Surface] 的 elevation 自绘。
 *  - 拖动量是**像素**（pointerInput 回调），窗口状态是 **Dp**——所有 delta 必须经
 *    [LocalDensity] 转 Dp 再参与计算。
 *  - `resizable = false`：关闭 AWT 尺寸行为，调整大小完全由 [WindowResizeHandles]
 *    的透明手柄承担（undecorated 下系统也不提供边缘 resize）。
 *
 * 尺寸约束（min/max）只在 resize 路径生效；初始尺寸由调用方保证落在区间内
 * （超出时取 min/max 与初值的交集）。
 */
@Composable
fun ObjectDetailDialog(
    title: String,
    onDismiss: () -> Unit,
    initialWidth: Dp = 720.dp,
    initialHeight: Dp = 500.dp,
    minWidth: Dp = 400.dp,
    minHeight: Dp = 300.dp,
    maxWidth: Dp = 1400.dp,
    maxHeight: Dp = 1000.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // 初始尺寸与 min/max 求交集：调用方配置失配时不至于开局越界。
    val startWidth = initialWidth.coerceIn(minWidth, maxWidth)
    val startHeight = initialHeight.coerceIn(minHeight, maxHeight)
    val windowState = rememberDialogState(
        size = DpSize(startWidth, startHeight),
        position = WindowPosition(Alignment.Center),
    )

    DialogWindow(
        onCloseRequest = onDismiss,
        state = windowState,
        title = title,
        // undecorated：去系统标题栏；transparent：16dp 圆角外透出底色而非方形角；
        // resizable = false：尺寸完全由下面的自定义手柄接管。
        undecorated = true,
        transparent = true,
        resizable = false,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 1. 自定义标题栏（拖动移动窗口）
                    DraggableTitleBar(
                        title = title,
                        windowState = windowState,
                        density = density,
                    )

                    // 2. 内容区（业务模块填充）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        content()
                    }

                    // 3. 底部关闭按钮
                    BottomBar(onClose = onDismiss)
                }

                // 4. 边缘拖动调整大小（透明手柄覆盖 8 个方向）
                WindowResizeHandles(
                    windowState = windowState,
                    density = density,
                    minWidth = minWidth,
                    minHeight = minHeight,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                )
            }
        }
    }
}

/**
 * 可拖动的标题栏（48dp）：拖动改变窗口位置。
 * 拖动量 px → [LocalDensity] 转 Dp —— 窗口状态全是 Dp 单位。
 */
@Composable
private fun DraggableTitleBar(
    title: String,
    windowState: DialogState,
    density: Density,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        // 拖动量是像素：转 Dp 后再改窗口状态（窗口状态全是 Dp）。
                        val dx = with(density) { dragAmount.x.toDp() }
                        val dy = with(density) { dragAmount.y.toDp() }
                        windowState.position = WindowPosition(
                            windowState.position.x + dx,
                            windowState.position.y + dy,
                        )
                    }
                }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

/** 底部关闭栏（56dp）：右下角关闭按钮（i18n：common_close）。 */
@Composable
private fun BottomBar(onClose: () -> Unit) {
    Column {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onClose) {
                Text(stringResource("common_close"))
            }
        }
    }
}

/**
 * 窗口边缘拖动调整大小：8 个透明手柄（左右下上 4 边 + 4 角），
 * 拖动时同步改 `windowState.size`（受 min/max 钳制）与 `windowState.position`
 * （左/上方向的边拖动要反向补偿窗口位置，视觉上「边缘跟手」）。
 */
@Composable
private fun WindowResizeHandles(
    windowState: DialogState,
    density: Density,
    minWidth: Dp,
    minHeight: Dp,
    maxWidth: Dp,
    maxHeight: Dp,
) {
    val handleSize = 6.dp
    val cornerSize = 14.dp

    Box(modifier = Modifier.fillMaxSize()) {
        // 左边：向右拖（delta.x > 0）= 窗口变窄、位置右移
        ResizeHandle(
            alignment = Alignment.CenterStart,
            width = handleSize,
            height = null,
            cursor = resizeCursor(Cursor.W_RESIZE_CURSOR),
        ) { deltaX, deltaY ->
            val newWidth = (windowState.size.width - deltaX).coerceIn(minWidth, maxWidth)
            val newX = windowState.position.x + (windowState.size.width - newWidth)
            windowState.size = DpSize(newWidth, windowState.size.height)
            windowState.position = WindowPosition(newX, windowState.position.y)
        }

        // 右边
        ResizeHandle(
            alignment = Alignment.CenterEnd,
            width = handleSize,
            height = null,
            cursor = resizeCursor(Cursor.E_RESIZE_CURSOR),
        ) { deltaX, deltaY ->
            val newWidth = (windowState.size.width + deltaX).coerceIn(minWidth, maxWidth)
            windowState.size = DpSize(newWidth, windowState.size.height)
        }

        // 上边
        ResizeHandle(
            alignment = Alignment.TopCenter,
            width = null,
            height = handleSize,
            cursor = resizeCursor(Cursor.N_RESIZE_CURSOR),
        ) { deltaX, deltaY ->
            val newHeight = (windowState.size.height - deltaY).coerceIn(minHeight, maxHeight)
            val newY = windowState.position.y + (windowState.size.height - newHeight)
            windowState.size = DpSize(windowState.size.width, newHeight)
            windowState.position = WindowPosition(windowState.position.x, newY)
        }

        // 下边
        ResizeHandle(
            alignment = Alignment.BottomCenter,
            width = null,
            height = handleSize,
            cursor = resizeCursor(Cursor.S_RESIZE_CURSOR),
        ) { deltaX, deltaY ->
            val newHeight = (windowState.size.height + deltaY).coerceIn(minHeight, maxHeight)
            windowState.size = DpSize(windowState.size.width, newHeight)
        }

        // 左上角
        ResizeHandle(
            alignment = Alignment.TopStart,
            width = cornerSize,
            height = cornerSize,
            cursor = resizeCursor(Cursor.NW_RESIZE_CURSOR),
        ) { deltaX, deltaY ->
            val newWidth = (windowState.size.width - deltaX).coerceIn(minWidth, maxWidth)
            val newHeight = (windowState.size.height - deltaY).coerceIn(minHeight, maxHeight)
            val newX = windowState.position.x + (windowState.size.width - newWidth)
            val newY = windowState.position.y + (windowState.size.height - newHeight)
            windowState.size = DpSize(newWidth, newHeight)
            windowState.position = WindowPosition(newX, newY)
        }

        // 右上角
        ResizeHandle(
            alignment = Alignment.TopEnd,
            width = cornerSize,
            height = cornerSize,
            cursor = resizeCursor(Cursor.NE_RESIZE_CURSOR),
        ) { deltaX, deltaY ->
            val newWidth = (windowState.size.width + deltaX).coerceIn(minWidth, maxWidth)
            val newHeight = (windowState.size.height - deltaY).coerceIn(minHeight, maxHeight)
            val newY = windowState.position.y + (windowState.size.height - newHeight)
            windowState.size = DpSize(newWidth, newHeight)
            windowState.position = WindowPosition(windowState.position.x, newY)
        }

        // 左下角
        ResizeHandle(
            alignment = Alignment.BottomStart,
            width = cornerSize,
            height = cornerSize,
            cursor = resizeCursor(Cursor.SW_RESIZE_CURSOR),
        ) { deltaX, deltaY ->
            val newWidth = (windowState.size.width - deltaX).coerceIn(minWidth, maxWidth)
            val newHeight = (windowState.size.height + deltaY).coerceIn(minHeight, maxHeight)
            val newX = windowState.position.x + (windowState.size.width - newWidth)
            windowState.size = DpSize(newWidth, newHeight)
            windowState.position = WindowPosition(newX, windowState.position.y)
        }

        // 右下角
        ResizeHandle(
            alignment = Alignment.BottomEnd,
            width = cornerSize,
            height = cornerSize,
            cursor = resizeCursor(Cursor.SE_RESIZE_CURSOR),
        ) { deltaX, deltaY ->
            val newWidth = (windowState.size.width + deltaX).coerceIn(minWidth, maxWidth)
            val newHeight = (windowState.size.height + deltaY).coerceIn(minHeight, maxHeight)
            windowState.size = DpSize(newWidth, newHeight)
        }
    }
}

/** 单个透明手柄：绝对定位（[alignment]）+ 方向光标 + 拖动回调（delta 已转 Dp）。 */
@Composable
private fun BoxScope.ResizeHandle(
    alignment: Alignment,
    width: Dp?,
    height: Dp?,
    cursor: PointerIcon,
    onDrag: (Dp, Dp) -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .align(alignment)
            .then(if (width != null) Modifier.width(width) else Modifier)
            .then(if (height != null) Modifier.height(height) else Modifier)
            .then(
                // 边手柄沿边铺满：上下边横向填满、左右边纵向填满；角手柄固定尺寸。
                when {
                    width != null && height == null -> Modifier.fillMaxHeight()
                    width == null && height != null -> Modifier.fillMaxWidth()
                    else -> Modifier
                },
            )
            .pointerHoverIcon(cursor)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // 拖动量是像素：转 Dp 后回调（窗口状态全是 Dp）。
                    val dx = with(density) { dragAmount.x.toDp() }
                    val dy = with(density) { dragAmount.y.toDp() }
                    onDrag(dx, dy)
                }
            },
    )
}

/** 方向 resize 光标（java.awt.Cursor 常量 → compose PointerIcon，桌面端）。 */
private fun resizeCursor(cursorKind: Int): PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(cursorKind))
