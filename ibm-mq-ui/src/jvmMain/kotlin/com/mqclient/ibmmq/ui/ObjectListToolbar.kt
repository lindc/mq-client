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
import com.mqclient.uicommon.i18n.stringResource

/** 占位宽度：搜索框与刷新按钮之间的间距。 */
private val ToolbarSpacing = 8.dp

/**
 * MQ 对象列表页**通用工具栏**：顶部刷新进度条（保留旧数据，不整页 Loading）+ 紧凑搜索框
 * （左，占满剩余宽度）+ 刷新按钮（右）。所有列表页共用同一套布局与高度。
 *
 * 通用对象类型无需额外控件时 [trailingContent] 留空；「显示/隐藏系统」开关通过
 * [showSystemToggle] / [showSystem] / [onShowSystemChange] 三个参数统一集成（文案来自
 * 通用 i18n key，无需各页重复），需要别的尾部控件时再用 [trailingContent] 追加。
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
    /** 是否显示「显示/隐藏系统」开关（默认隐藏系统对象）。 */
    showSystemToggle: Boolean = false,
    /** 当前是否显示系统对象。 */
    showSystem: Boolean = false,
    /** 点击开关的回调（页面持有状态、负责切换）。 */
    onShowSystemChange: (Boolean) -> Unit = {},
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
            // 「显示/隐藏系统」开关集成进工具栏，避免每个列表页重复同一段 trailingContent。
            if (showSystemToggle) {
                SystemObjectToggleButton(
                    showSystem = showSystem,
                    onToggle = { onShowSystemChange(showSystem) },
                )
            }
            trailingContent()
        }
    }
}

/**
 * 系统对象显隐切换按钮：所有对象列表页共用，文案统一取 i18n key。
 * 状态由页面持有，默认隐藏系统对象，点击后显示。
 */
@Composable
fun SystemObjectToggleButton(
    showSystem: Boolean,
    onToggle: () -> Unit,
) {
    ToolbarButton(
        icon = if (showSystem) Icons.Default.Visibility else Icons.Default.VisibilityOff,
        text = stringResource(if (showSystem) "toolbar_hide_system" else "toolbar_show_system"),
        onClick = onToggle,
    )
}
