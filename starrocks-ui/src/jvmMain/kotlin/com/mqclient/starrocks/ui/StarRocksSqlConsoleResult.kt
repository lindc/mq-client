package com.mqclient.starrocks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.AppTooltip
import com.mqclient.uicommon.LocalSnackbarHostState
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.table.MixedTypeComparator
import com.mqclient.uicommon.table.SortDirection
import com.mqclient.uicommon.table.SortState
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.itemsIndexed

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
    // 长值详情状态提升到函数顶层：弹窗渲染在 Column 之外（独立窗口，不该被
    // 横向滚动容器裁剪），状态必须两层都能看到。
    var detailCell by remember { mutableStateOf<CellDetail?>(null) }

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

        // 排序状态落在**渲染层**而不是 ConsoleGrid 里：滚动 / 重排都是展示行为，
        // 不该污染结果数据（复制、导出要的是服务端原始顺序）。
        // 按 columns 身份重置：换一次查询（列变了）就回到原始顺序。
        var sort by remember(grid.columns.toList()) { mutableStateOf<SortState?>(null) }
        val visibleRows = remember(grid.rows.toList(), sort) {
            MixedTypeComparator.sortRows(grid.rows.toList(), sort)
        }

        val horizontal = rememberScrollState()
        // 外层横向滚动（列多不挤爆），内层纵向 LazyColumn 虚拟化（只组装可见行）；
        // 表头与数据行共用同一横向偏移，列自然对齐。
        Row(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
            Column {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                    grid.columns.forEachIndexed { index, column ->
                        val direction = sort?.takeIf { it.columnIndex == index }?.direction
                        Row(
                            modifier = Modifier.clickable {
                                sort = sort?.next(index) ?: SortState(index, SortDirection.ASCENDING)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TableCell(text = column.name, header = true)
                            Text(
                                text = when (direction) {
                                    null -> "⇅"
                                    SortDirection.ASCENDING -> "↑"
                                    SortDirection.DESCENDING -> "↓"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (direction != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(visibleRows) { rowIndex, row ->
                        Row {
                            row.forEachIndexed { columnIndex, value ->
                                ValueCell(
                                    value = value,
                                    // 列名从 columns 取；行号用**当前显示顺序**（排序后的位置），
                                    // 与用户眼前的行对得上，而不是原始下标。
                                    columnName = grid.columns.getOrNull(columnIndex)?.name ?: "#$columnIndex",
                                    rowIndex = rowIndex,
                                    onOpenDetail = { detailCell = it },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 详情弹窗放在最外层：它是独立窗口，不该被横向滚动容器裁剪。
    detailCell?.let { cell ->
        CellDetailDialog(cell, onDismiss = { detailCell = null })
    }
}

/** 单元格详情：列名 / 行号 / 完整值。 */
internal data class CellDetail(
    val columnName: String,
    val rowIndex: Int,
    val value: Any?,
)

/**
 * 结果单元格：**长值必须可读**。
 *
 * 单元格固定 160dp、单行省略（`maxLines = 1` + Ellipsis）—— 这是为了列对齐与行高
 * 恒定，但代价是省略号后面的内容在 Compose 里根本没有布局，**连拖选都选不到**。
 * 对一条含 JSON / 长文本 / 堆栈的结果，等于看不到。
 *
 * 两条出路都提供：
 *  - 悬停 tooltip：扫一眼即可，不打断阅读；
 *  - 点击 → 详情弹窗：完整值 + 可选中 + 一键复制（贴着长值真正要用的动作）。
 *
 * 短值也走同一条路径（不做长度判断）：判断"是否被截断"需要测量文本宽度，
 * 而每次滚动都测一遍不划算；统一行为反而更可预期 —— 用户不用猜"这格能不能点"。
 */
@Composable
internal fun ValueCell(
    value: Any?,
    columnName: String,
    rowIndex: Int,
    onOpenDetail: (CellDetail) -> Unit,
) {
    val text = value?.toString() ?: "NULL"
    // 空值与 NULL 都不可点（没有可看的内容）。
    if (value == null) {
        TableCell(text = text, header = false, subdued = true)
        return
    }
    AppTooltip(
        tooltip = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                // 长文本在 tooltip 里换行显示前若干行，够判断"是不是我要的那条"。
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) {
        TableCell(
            text = text,
            header = false,
            onClick = onOpenDetail,
            columnName = columnName,
            rowIndex = rowIndex,
        )
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

/**
 * 单元格：固定宽度、单行省略；表头用 label 样式区分。
 *
 * [subdued] 用于 NULL 占位（次要色），[onClick] 用于打开详情（数据行专用）。
 * 宽度固定是刻意的：列对齐与行高恒定比"尽量显示更多字"更重要 —— 完整值由
 * tooltip / 详情弹窗提供，不靠这一格撑开。
 */
@Composable
private fun TableCell(
    text: String,
    header: Boolean,
    subdued: Boolean = false,
    onClick: ((CellDetail) -> Unit)? = null,
    columnName: String = "",
    rowIndex: Int = 0,
) {
    Box(
        Modifier
            .width(HEADER_CELL_WIDTH)
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick(CellDetail(columnName, rowIndex, text)) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        SelectionContainer {
            Text(
                text,
                style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    header -> MaterialTheme.colorScheme.onSurfaceVariant
                    subdued -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

internal val HEADER_CELL_WIDTH = 160.dp

/**
 * 单元格详情弹窗：完整值 + 可选中 + 一键复制。
 *
 * 为什么用 [ObjectDetailDialog] 而不是 AlertDialog：它与全应用其它详情框
 * （消息详情 / 对象属性）同一外壳（标题栏 / ✕ / ESC / 尺寸档位），用户不需要
 * 为"看一个格子"学一套新的交互。
 */
@Composable
private fun CellDetailDialog(cell: CellDetail, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val fullText = cell.value?.toString() ?: "NULL"

    ObjectDetailDialog(
        title = stringResource("olap.starrocks.console.cell_detail_title")
            .withArg(cell.columnName, cell.rowIndex + 1),
        onDismiss = onDismiss,
        footerActions = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(fullText))
                    scope.launch { snackbar.showSnackbar(stringResource("msg_detail_copied")) }
                },
            ) {
                Text(stringResource("olap.starrocks.console.cell_detail_copy"))
            }
        },
    ) {
        // 等宽 + 可选中：长 JSON / 堆栈按原样看，且能拖选其中一段。
        SelectionContainer {
            Text(
                text = fullText,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        }
    }
}
