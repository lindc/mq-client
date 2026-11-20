package com.mqclient.starrocks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

@Composable
internal fun ResultArea(grid: ConsoleGrid, phase: ConsolePhase, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (phase) {
            ConsolePhase.Idle -> Text(
                stringResource("olap.starrocks.console.idle_hint"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 只有**真正**在自动建连时才显示这个文案。
            ConsolePhase.Connecting -> ProgressHint(stringResource("olap.starrocks.console.connecting"))

            // 连接就绪、查询进行中：结果还没到就显示居中的「正在执行查询...」，
            // 已有首批数据则边收边渲染表格（顶部横幅同款文案）。
            ConsolePhase.Executing ->
                if (grid.rows.isEmpty() && grid.columns.isEmpty()) {
                    ProgressHint(stringResource("olap.starrocks.console.executing"))
                } else {
                    ResultTable(grid, phase, Modifier.fillMaxSize())
                }

            // 本轮结束：结果 / 错误由 grid 表达，任何 Loading 文案都不再显示。
            ConsolePhase.Finished -> when {
                // 执行失败（清空了之前的表格）。
                grid.error != null -> Text(
                    stringResource("olap.starrocks.console.error_prefix") + grid.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )

                // DML / DDL：受影响行数（列 schema 为空）。
                grid.updateCount != null -> Text(
                    stringResource("olap.starrocks.console.affected_rows")
                        .withArg(grid.updateCount ?: 0L, grid.durationMs),
                    style = MaterialTheme.typography.bodyLarge,
                )

                // 空结果集。
                grid.columns.isEmpty() && grid.rows.isEmpty() -> Text(
                    stringResource("olap.starrocks.console.empty_result").withArg(grid.durationMs),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> ResultTable(grid, phase, Modifier.fillMaxSize())
            }
        }
    }
}

// TODO: 结果表格支持列宽拖拽与冻结列（虚拟滚动已由 LazyColumn 承担）

/** 流式结果表格：行落在快照 List，边收边渲染；顶部横幅反映拉取进度。 */
@Composable
private fun ResultTable(grid: ConsoleGrid, phase: ConsolePhase, modifier: Modifier = Modifier) {
    Column(modifier) {
        // ── 顶部状态横幅：执行中显示进度；截断显示保护提示 ──
        when {
            phase == ConsolePhase.Executing -> StreamingBanner(
                text = stringResource("olap.starrocks.console.stream_loading").withArg(grid.rows.size),
            )
            grid.truncated -> StreamingBanner(
                text = stringResource("olap.starrocks.console.truncated").withArg(MAX_ROWS),
                busy = false,
            )
            else -> Text(
                summaryLine(grid),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        val horizontal = rememberScrollState()
        // 外层横向滚动（列多不挤爆），内层纵向 LazyColumn 虚拟化（只组装可见行）；
        // 表头与数据行共用同一横向偏移，列自然对齐。
        Row(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
            Column {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                    grid.columns.forEach { column ->
                        TableCell(text = column.name, header = true)
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(grid.rows) { row ->
                        Row {
                            row.forEach { value ->
                                TableCell(text = value?.toString() ?: "NULL", header = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 汇总行：行数 + 耗时；多条执行时追加成功 / 失败计数。 */
@Composable
private fun summaryLine(grid: ConsoleGrid): String {
    val base = stringResource("olap.starrocks.console.row_count")
        .withArg(grid.rows.size, grid.durationMs)
    val multi = grid.successCount + grid.failedCount > 1
    return if (multi) {
        base + "  " + stringResource("olap.starrocks.console.summary")
            .withArg(grid.successCount + grid.failedCount, grid.successCount, grid.failedCount)
    } else {
        base
    }
}

/** 顶部进度横幅：小号 spinner + 文案（截断提示复用同一形态，不带 spinner）。 */
@Composable
private fun StreamingBanner(text: String, busy: Boolean = true) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        } else {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 居中的 spinner + 文案（连接中共用形态）。 */
@Composable
private fun ProgressHint(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
    }
}

/** 单元格：固定宽度、单行省略；表头用 label 样式区分。 */
@Composable
private fun TableCell(text: String, header: Boolean) {
    Box(
        Modifier
            .width(HEADER_CELL_WIDTH)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        SelectionContainer {
            Text(
                text,
                style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

internal val HEADER_CELL_WIDTH = 160.dp
