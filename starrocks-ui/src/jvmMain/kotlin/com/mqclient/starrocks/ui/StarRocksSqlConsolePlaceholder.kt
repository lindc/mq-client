package com.mqclient.starrocks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.olap.base.query.ResultColumn
import com.mqclient.starrocks.StarRocksModule
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.i18n.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 「SQL 控制台」导航项的内容页（S3 MVP）。
 *
 * 结构与后续真实实现保持一致：顶部 Tab 栏（每个 Tab 一条查询会话，右侧 `+`
 * 新建）+ 工具栏（执行按钮，`Ctrl+Enter` 快捷键）+ 多行 SQL 编辑器 +
 * 结果区（Idle / Loading / Error / Success 四态）。
 *
 * 查询执行走 [StarRocksModule.executeQuery]（当前共享会话上的流式
 * `Flow<ResultBatch>`），UI 只收集批次累积渲染，不认识 JDBC。
 *
 * ## 后续迭代挂点（刻意留白）
 *
 * - Tab 标题现在按「查询 N」顺序编号，真实实现应改为可编辑的会话名并持久化；
 * - SQL 编辑器（语法高亮 / 补全）与结果网格（导出 / 分页）是独立的大块工作。
 */
@Composable
internal fun StarRocksSqlConsolePlaceholder(modifier: Modifier = Modifier, connectionId: String = "") {
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
                            // TODO: 支持多 Tab 独立维护各自的 SQL 和结果状态（MVP 单共享态）
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

        ConsoleBody(Modifier.fillMaxSize(), connectionId)
    }
}

/** 单个 Tab 的内容：工具栏 + SQL 编辑器 + 结果区（MVP 各 Tab 共享一份状态）。 */
@Composable
private fun ConsoleBody(modifier: Modifier = Modifier, connectionId: String) {
    var sql by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<ConsoleResultState>(ConsoleResultState.Idle) }
    val scope = rememberCoroutineScope()

    fun runSql() {
        val statement = sql.trim()
        if (statement.isEmpty()) return
        // 连接中 / 执行中一律忽略重复触发（防狂点并发建连）。
        if (state == ConsoleResultState.Running || state == ConsoleResultState.Connecting) return
        scope.launch {
            // 懒加载连接：未连接先自动建会话（成功后 Session 存进 ConnectionManager，
            // 后续「执行」直接复用，不再重复连接）。
            if (!StarRocksModule.isAlive()) {
                state = ConsoleResultState.Connecting
                val connected = StarRocksModule.connectById(connectionId)
                if (connected.isFailure) {
                    state = ConsoleResultState.Failed(
                        stringResource("olap.starrocks.console.connect_failed")
                            .withArg(connected.exceptionOrNull()?.userMessage() ?: "unknown"),
                    )
                    return@launch
                }
            }
            state = ConsoleResultState.Running
            state = try {
                val columns = mutableListOf<ResultColumn>()
                val rows = mutableListOf<List<Any?>>()
                var updateCount: Long? = null
                var durationMs = 0L
                val start = System.currentTimeMillis()
                StarRocksModule.executeQuery(statement).collect { batch ->
                    if (batch.columns.isNotEmpty()) {
                        columns.clear()
                        columns.addAll(batch.columns)
                    }
                    rows.addAll(batch.rows)
                    if (batch.updateCount != null) updateCount = batch.updateCount
                    durationMs = System.currentTimeMillis() - start
                }
                when {
                    // DML / DDL：受影响行数（列 schema 为空）。
                    columns.isEmpty() && updateCount != null -> ConsoleResultState.Affected(
                        updateCount ?: 0L,
                        durationMs,
                    )
                    else -> ConsoleResultState.Done(columns.toList(), rows.toList(), durationMs)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // `userMessage()` 在展示时点查表翻译（service 层 errorKey 异常的本地化通路）。
                ConsoleResultState.Failed(error.userMessage())
            }
        }
    }

    Column(modifier) {
        // ── 工具栏：执行按钮（Ctrl+Enter） ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(
                onClick = ::runSql,
                // 连接中 / 执行中禁用：防狂点导致并发建连 / 重复查询。
                enabled = state != ConsoleResultState.Running && state != ConsoleResultState.Connecting,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource("olap.starrocks.console.run"))
            }
            Text(
                stringResource("olap.starrocks.console.run"),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // ── SQL 编辑器：多行等宽，占剩余高度 35% ──
        OutlinedTextField(
            value = sql,
            onValueChange = { sql = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f)
                .padding(horizontal = 8.dp)
                .onPreviewKeyEvent { event ->
                    // Ctrl+Enter 执行（编辑器持焦点的快捷路径）。
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.Enter &&
                        event.isCtrlPressed &&
                        run { runSql(); true }
                },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text(stringResource("olap.starrocks.console.sql_placeholder")) },
        )

        // ── 结果区：剩余全部高度 ──
        ResultArea(
            state = state,
            modifier = Modifier.fillMaxWidth().weight(0.65f).padding(8.dp),
        )
    }
}

/** 结果区五态（Idle / Connecting / Running / Failed / 成功两类）。 */
private sealed interface ConsoleResultState {
    data object Idle : ConsoleResultState

    /** 懒加载自动连接进行中（区别于 SQL 执行）。 */
    data object Connecting : ConsoleResultState
    data object Running : ConsoleResultState
    data class Failed(val message: String) : ConsoleResultState

    /** SELECT 完成（行可空 —— 空结果集时显示提示）。 */
    data class Done(
        val columns: List<ResultColumn>,
        val rows: List<List<Any?>>,
        val durationMs: Long,
    ) : ConsoleResultState

    /** DML / DDL 完成：受影响行数。 */
    data class Affected(val count: Long, val durationMs: Long) : ConsoleResultState
}

@Composable
private fun ResultArea(state: ConsoleResultState, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (state) {
            ConsoleResultState.Idle -> Text(
                stringResource("olap.starrocks.console.idle_hint"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 懒加载自动连接中：给用户明确的进度反馈，避免以为程序卡死。
            ConsoleResultState.Connecting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    stringResource("olap.starrocks.console.connecting"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            ConsoleResultState.Running -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    stringResource("olap.starrocks.console.running"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            is ConsoleResultState.Failed -> Text(
                stringResource("olap.starrocks.console.error_prefix") + state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )

            is ConsoleResultState.Affected -> Text(
                stringResource("olap.starrocks.console.affected_rows")
                    .withArg(state.count, state.durationMs),
                style = MaterialTheme.typography.bodyLarge,
            )

            is ConsoleResultState.Done ->
                if (state.rows.isEmpty()) {
                    Text(
                        stringResource("olap.starrocks.console.empty_result").withArg(state.durationMs),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ResultTable(state, Modifier.fillMaxSize())
                }
        }
    }
}

// TODO: 结果表格支持虚拟滚动和列宽拖拽（MVP：固定列宽 + 横向滚动）

/** 基础结果表格：固定宽度单元格 + 整表横向滚动，行纵向走 [LazyColumn]。 */
@Composable
private fun ResultTable(state: ConsoleResultState.Done, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            stringResource("olap.starrocks.console.row_count")
                .withArg(state.rows.size, state.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val horizontal = rememberScrollState()
        // 外层横向滚动（列多不挤爆），内层纵向 LazyColumn；
        // 表头与数据行共用同一横向偏移，列自然对齐。
        Row(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
            Column {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                    state.columns.forEach { column ->
                        TableCell(text = column.name, header = true)
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(state.rows) { row ->
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

/** 单元格：固定宽度、单行省略、等宽数字区；表头用 label 样式区分。 */
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

private val HEADER_CELL_WIDTH = 160.dp
