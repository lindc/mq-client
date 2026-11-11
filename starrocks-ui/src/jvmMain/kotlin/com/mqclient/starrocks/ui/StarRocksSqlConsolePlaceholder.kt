package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 「SQL 控制台」导航项的 **Tab 布局占位页**（S3：只搭壳，不执行 SQL）。
 *
 * 结构与后续真实实现保持一致：顶部 Tab 栏（每个 Tab 一条查询会话，右侧 `+`
 * 新建），下方内容区。真实编辑器落地时替换 [StarRocksConsoleBody] 即可，
 * Tab 容器 / 新建逻辑预期保留。
 *
 * ## 后续迭代挂点（刻意留白）
 *
 * - Tab 标题现在按「查询 N」顺序编号，真实实现应改为可编辑的会话名并持久化；
 * - 内容区目前只有一行占位文案，SQL 编辑器组件（语法高亮 / 执行 / 结果网格）
 *   是独立的大块工作，另立任务。
 */
@Composable
internal fun StarRocksSqlConsolePlaceholder(modifier: Modifier = Modifier) {
    // TODO: 接入 SQLDelight，监听输入防抖，实现 SQL 草稿自动保存（saved_query 表）
    // TODO: 监听 StarRocksConnectionManager 的关闭事件，清理当前连接下的所有 Tabs，重连时自动恢复

    var tabCount by remember { mutableIntStateOf(1) }
    var selected by remember { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TabRow(
                selectedTabIndex = selected,
                modifier = Modifier.weight(1f),
            ) {
                repeat(tabCount) { index ->
                    Tab(
                        selected = selected == index,
                        onClick = { selected = index },
                        text = {
                            // Tab 标题：占位阶段按「查询 N」顺序编号；真实实现
                            // 改为持久化的会话名（见上方 TODO）。
                            Text(stringResource("olap.starrocks.console.query_tab").withArg(index + 1))
                        },
                    )
                }
            }
            // `+` 新建 Tab：追加到末尾并立即切换过去。
            IconButton(
                onClick = {
                    tabCount += 1
                    selected = tabCount - 1
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource("olap.starrocks.console.new_tab"))
            }
        }

        StarRocksConsoleBody(Modifier.fillMaxSize())
    }
}

/** Tab 内容区：占位文案；真实实现替换为 SQL 编辑器 + 结果网格。 */
@Composable
private fun StarRocksConsoleBody(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            stringResource("olap.starrocks.console.editor_placeholder"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}
