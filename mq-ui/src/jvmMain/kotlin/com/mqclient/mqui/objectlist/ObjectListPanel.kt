package com.mqclient.mqui.objectlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ContextMenuItem

/**
 * 对象列表主 Panel（对外唯一入口）：顶部工具栏 Slot（可选）+ 卡片网格，
 * 支持加载态与空态。
 *
 * 面板不感知任何具体 MQ：卡片数据（[ObjectCardData]）由各 MQ 模块转换，
 * 右键菜单（[ContextMenuItem]）由 [contextMenuProvider] 提供，工具栏内容
 * 由 [toolbarContent] Slot 注入。
 */
@Composable
fun ObjectListPanel(
    items: List<ObjectCardData>,
    onCardClick: (ObjectCardData) -> Unit,
    onCardDoubleClick: (ObjectCardData) -> Unit,
    contextMenuProvider: (ObjectCardData) -> List<ContextMenuItem>,
    modifier: Modifier = Modifier,
    /** Slot：顶部工具栏（搜索 / 筛选 / 刷新等，各 MQ 模块自定义）；不提供则不显示这一行。 */
    toolbarContent: (@Composable () -> Unit)? = null,
    /** Slot：空状态；不提供则用默认 [ObjectListEmptyState]。 */
    emptyContent: (@Composable () -> Unit)? = null,
    /** 加载中：主内容区换成居中进度圈。 */
    loading: Boolean = false,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (toolbarContent != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                toolbarContent()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                items.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    emptyContent?.invoke() ?: ObjectListEmptyState()
                }

                else -> ObjectCardGrid(
                    items = items,
                    onCardClick = onCardClick,
                    onCardDoubleClick = onCardDoubleClick,
                    contextMenuProvider = contextMenuProvider,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
