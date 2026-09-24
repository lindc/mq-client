package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.command.ToolbarButton
import com.mqclient.mqui.common.CompactSearchField

/** 占位宽度：搜索框与刷新按钮之间的间距。 */
private val ToolbarSpacing = 8.dp

/**
 * MQ 对象列表页**通用工具栏**：顶部刷新进度条（保留旧数据，不整页 Loading）+ 紧凑搜索框
 * （左，占满剩余宽度）+ 刷新按钮（右）。所有列表页共用同一套布局与高度。
 *
 * 通用对象类型无需额外控件时 [trailingContent] 留空；队列页通过其追加「显示系统」开关，
 * 不改变搜索 / 刷新按钮的位置与语义。
 */
@Composable
fun ObjectListToolbar(
    searchText: String,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String,
    refreshText: String,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 刷新进行中：顶部细进度条（保留旧数据，不整页 Loading）；不改变按钮位置。
        if (refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ToolbarSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactSearchField(
                value = searchText,
                onValueChange = onSearchChange,
                placeholder = placeholder,
                modifier = Modifier.weight(1f),
            )
            ToolbarButton(
                icon = Icons.Default.Refresh,
                text = refreshText,
                onClick = onRefresh,
                enabled = !refreshing,
            )
            trailingContent()
        }
    }
}

/**
 * 系统对象判定：IBM MQ 默认系统对象以 `SYSTEM.` 开头（大小写不敏感）。
 * 队列的 `AMQ.*` 临时队列在 [isSystemOrTempQueue] 里额外判断。
 */
fun isSystemObject(name: String): Boolean =
    name.trim().uppercase().startsWith("SYSTEM.")

/**
 * 系统对象显隐切换按钮：所有对象列表页共用，文字由调用方传入 i18n 文案。
 * 状态由页面持有，默认隐藏系统对象，点击后显示。
 */
@Composable
fun SystemObjectToggleButton(
    showSystem: Boolean,
    onToggle: () -> Unit,
    showText: String,
    hideText: String,
) {
    ToolbarButton(
        icon = if (showSystem) Icons.Default.Visibility else Icons.Default.VisibilityOff,
        text = if (showSystem) hideText else showText,
        onClick = onToggle,
    )
}
