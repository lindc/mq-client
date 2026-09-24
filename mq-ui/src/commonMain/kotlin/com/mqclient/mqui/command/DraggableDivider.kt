package com.mqclient.mqui.command

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * 命令区 / 输出区之间的可拖动分隔条：整条 8dp 都算命中区，好抓；拖动时高亮。
 *
 * [onDrag] 回调拖动的**像素增量**（向下为正），由调用方换算成比例 —— 换算需要
 * 面板总高，这里不越权。
 */
@Composable
fun DraggableDivider(
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    val resizeCursor = remember { PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .pointerHoverIcon(resizeCursor)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.y)
                }
            }
            .background(
                if (isDragging) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    )
}
