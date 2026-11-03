package com.mqclient.mqui.objectlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 对象卡片网格：[LazyVerticalGrid] 自适应列数（[GridCells.Adaptive] 按
 * [minCardWidth] 计算，放得下几列放几列），窗口变化自动重排；列表虚拟化，
 * 大量对象也不掉帧。`items` 用 `id` 做稳定 key。
 *
 * 卡片宽度限制在 [maxCardWidth] 以内（与「新建连接」对话框的卡片一致）：
 * 列宽超过上限时（宽屏 + 少列）卡片不再被拉宽，在列内居中、两侧留白。
 * 操作按钮随 [ObjectCardData.actions] 内嵌在卡片上，网格不感知操作语义。
 */
@Composable
fun ObjectCardGrid(
    items: List<ObjectCardData>,
    onCardClick: (ObjectCardData) -> Unit,
    onCardDoubleClick: (ObjectCardData) -> Unit,
    modifier: Modifier = Modifier,
    minCardWidth: Dp = 240.dp,
    maxCardWidth: Dp = 280.dp,
    spacing: Dp = 16.dp,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = minCardWidth),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        items(
            items = items,
            key = { it.id },
        ) { item ->
            // Box 包一层让卡片在列内居中：列宽 > maxCardWidth 时两侧留白。
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ObjectCard(
                    data = item,
                    onClick = { onCardClick(item) },
                    onDoubleClick = { onCardDoubleClick(item) },
                    modifier = Modifier
                        .widthIn(max = maxCardWidth)
                        .fillMaxWidth(),
                )
            }
        }
    }
}
