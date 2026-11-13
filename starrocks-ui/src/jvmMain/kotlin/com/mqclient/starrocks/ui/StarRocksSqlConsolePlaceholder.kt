package com.mqclient.starrocks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.core.db.OlapQueryRecord
import com.mqclient.core.db.OlapQueryRepository
import com.mqclient.olap.base.query.ResultColumn
import com.mqclient.starrocks.StarRocksModule
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.i18n.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「SQL 控制台」导航项的内容页（S3：流式渲染 + 多语句执行）。
 *
 * 交互模式移植自 mq-ui 的命令控制台（CommandConsoleViewModel）：
 * - 编辑器用 [TextFieldValue] 持有光标 / 选中态 —— **有选中只执行选中，否则全文**；
 * - 文本按分号切分多语句，**逐条顺序执行**，任一条失败即停止（错误进结果区，
 *   之前的表格清空）；`try-catch-finally` 贯穿全程，`finally` 复位执行态 ——
 *   任何一次报错后修改 SQL 都能立即再次执行，不会卡死；
 * - 底层中断清理见 `MysqlJdbcConnection.executeQuery` 的 KDoc（流式残留的
 *   Statement / 连接由 service 层强制关闭，经懒加载路径自动重连）。
 *
 * 查询执行走 [StarRocksModule.executeQuery]（当前共享会话上的流式
 * `Flow<ResultBatch>`）；UI **边收集边渲染** —— 行落在 [ConsoleGrid.rows]
 * （Compose 快照 List），`LazyColumn` 虚拟化只组装可见行。
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

/**
 * 结果网格的可观察状态：**流式收集的落点**。
 *
 * `columns` / `rows` 用 Compose 快照 List —— 协程里每收一批就 append，
 * `LazyColumn` 随之增量重组（边收边渲染，不等全部收完）。
 */
private class ConsoleGrid {
    val columns = mutableStateListOf<ResultColumn>()
    val rows = mutableStateListOf<List<Any?>>()

    /** 是否正在流式拉取（结果区顶部显示加载横幅）。 */
    var streaming by mutableStateOf(false)

    /** 本轮是否已发起过执行（区分「初始」与「空结果集」）。 */
    var hasRun by mutableStateOf(false)

    /** 超过 [MAX_ROWS] 截断（防 OOM）：停止 append 并取消后续拉取。 */
    var truncated by mutableStateOf(false)

    /** 最近一条 DML / DDL 的受影响行数（SELECT 流为 null）。 */
    var updateCount by mutableStateOf<Long?>(null)

    /** 真实墙钟耗时（全部语句累计）。 */
    var durationMs by mutableLongStateOf(0L)

    /** 执行失败的用户文案（`userMessage()` 翻译后的）；非空 = 本轮以失败告终。 */
    var error by mutableStateOf<String?>(null)

    /** 本轮语句统计：成功 / 失败条数（多条执行的状态栏）。 */
    var successCount by mutableIntStateOf(0)
    var failedCount by mutableIntStateOf(0)

    fun reset() {
        columns.clear()
        rows.clear()
        streaming = false
        hasRun = false
        truncated = false
        updateCount = null
        durationMs = 0L
        error = null
        successCount = 0
        failedCount = 0
    }
}

/** OOM 保护：超过该行数停止 append 并取消拉取（数据仍在服务端，客户端不再累积）。 */
private const val MAX_ROWS = 50_000

/** 草稿防抖窗口（停止输入后毫秒数）。 */
private const val DRAFT_DEBOUNCE_MS = 1_000L

/** 截断信号：继承 [CancellationException] 以结束当前收集协程，但不算错误。 */
private class StopStream : CancellationException("result set truncated at $MAX_ROWS rows")

/** 单个 Tab 的内容：工具栏 + SQL 编辑器 + 结果区（MVP 各 Tab 共享一份状态）。 */
@Composable
private fun ConsoleBody(modifier: Modifier = Modifier, connectionId: String) {
    // TextFieldValue（而非纯 String）：与 mq-ui CommandConsoleViewModel 同构，
    // 持有光标 / 选中态，「执行选中」依赖它。
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val grid = remember { ConsoleGrid() }
    var connecting by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var draftLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val clipboard = LocalClipboardManager.current

    // 初始化：从 SQLite 读回上次未提交的草稿（关闭应用后重进仍保留）。
    LaunchedEffect(connectionId) {
        val draft = runCatching { OlapQueryRepository.loadDraft(connectionId) }.getOrNull()
        if (!draft.isNullOrEmpty()) textFieldValue = TextFieldValue(draft)
        draftLoaded = true
    }

    // 防抖自动保存：文本变化后 1 秒无新输入才落库（LaunchedEffect 每次
    // key 变化会取消上一个协程 —— 这就是 Compose 版的 debounce）。
    // key 只取 textFieldValue.text：光标 / 选区变化不触发写盘。
    LaunchedEffect(textFieldValue.text, draftLoaded) {
        if (!draftLoaded) return@LaunchedEffect
        delay(DRAFT_DEBOUNCE_MS)
        runCatching { OlapQueryRepository.saveDraft(connectionId, textFieldValue.text) }
    }

    /** 复制结果为 TSV（表头 + 行），列以 Tab 分隔 —— 与主流数据库工具一致。 */
    fun copyResults() {
        if (grid.columns.isEmpty() && grid.rows.isEmpty()) return
        val tsv = buildString {
            appendLine(grid.columns.joinToString("\t") { it.name })
            grid.rows.forEach { row -> appendLine(row.joinToString("\t") { it?.toString() ?: "NULL" }) }
        }
        clipboard.setText(AnnotatedString(tsv))
    }

    /** 清空编辑器与结果（对齐 mq-ui 控制台的 clear）。 */
    fun clearAll() {
        if (grid.streaming || connecting) job?.cancel()
        textFieldValue = TextFieldValue("")
        grid.reset()
    }

    fun runSql() {
        if (connecting || grid.streaming) return
        // 严格基于原生 [TextFieldValue.selection] 取源：有选区只发选区，
        // 无选区发全文（再按分号切多语句）。不做任何 selection 的写回/干预。
        val selected = selectedSource(textFieldValue)
        val sqlToExecute = selected ?: textFieldValue.text
        com.mqclient.core.log.Log.info(
            "olap-starrocks-ui",
            if (selected != null) "execute SELECTION (${selected.length} chars)" else "execute FULL TEXT",
        )
        if (sqlToExecute.isBlank()) return
        val statements = splitStatements(sqlToExecute)
        if (statements.isEmpty()) return

        job = scope.launch {
            var start = 0L
            try {
                // 懒加载连接：未连接先自动建会话（成功后 Session 存进 ConnectionManager，
                // 后续「执行」直接复用，不再重复连接）。
                if (!StarRocksModule.isAlive()) {
                    connecting = true
                    val connected = StarRocksModule.connectById(connectionId)
                    if (connected.isFailure) {
                        grid.reset()
                        grid.hasRun = true
                        grid.error = stringResource("olap.starrocks.console.connect_failed")
                            .withArg(connected.exceptionOrNull()?.userMessage() ?: "unknown")
                        return@launch
                    }
                }

                grid.reset()
                grid.hasRun = true
                start = System.currentTimeMillis()

                // 逐条顺序执行（对齐 mq-ui 控制台的逐条编排）：每条独立
                // try-catch —— 单条失败显示错误并停止后续，已成功的条目保留；
                // 取消经 CancellationException 在条目边界 / collect 内传播。
                for (statement in statements) {
                    try {
                        grid.streaming = true
                        var statementUpdateCount: Long? = null
                        var schemaApplied = false   // 每条语句的首个数据批：换 schema 并清上条结果
                        StarRocksModule.executeQuery(connectionId, statement).collect { batch ->
                            if (batch.updateCount != null) {
                                statementUpdateCount = batch.updateCount
                            } else {
                                if (!schemaApplied && batch.columns.isNotEmpty()) {
                                    grid.columns.clear()
                                    grid.columns.addAll(batch.columns)
                                    grid.rows.clear()   // 新语句：清上一条的结果
                                    schemaApplied = true
                                }
                                if (grid.rows.size < MAX_ROWS) {
                                    val room = MAX_ROWS - grid.rows.size
                                    grid.rows.addAll(if (batch.rows.size > room) batch.rows.take(room) else batch.rows)
                                }
                                if (grid.rows.size >= MAX_ROWS && !batch.lastBatch) {
                                    grid.truncated = true
                                    throw StopStream() // 取消本条后续拉取（finally 复位 streaming）
                                }
                            }
                        }
                        if (statementUpdateCount != null) {
                            // DML / DDL：清掉表格内容，显示受影响行数。
                            grid.columns.clear()
                            grid.rows.clear()
                            grid.updateCount = statementUpdateCount
                        }
                        grid.successCount += 1
                    } catch (stop: StopStream) {
                        // 截断不是错误：保留已收集数据，横幅提示。
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        // 报错即停（错误显示在结果区、之前的表格清空）；`finally`
                        // 已复位执行态 —— 修改 SQL 后可立即再次执行。
                        grid.columns.clear()
                        grid.rows.clear()
                        grid.updateCount = null
                        grid.failedCount += 1
                        grid.error = error.userMessage()
                        return@launch
                    } finally {
                        // 每条语句的 finally：彻底复位拉取态（成功 / 失败 / 取消都走）。
                        grid.streaming = false
                    }
                }
            } catch (cancellation: CancellationException) {
                // Stop / Tab 切换等取消：不算错误，保留已完成的部分。
            } finally {
                // 全程兜底：无论成功 / 失败 / 取消，执行态必须复位（核心修复）。
                connecting = false
                grid.streaming = false
                if (start > 0L) grid.durationMs = System.currentTimeMillis() - start
            }
        }
    }

    Column(modifier) {
        // ── 工具栏：执行 / 停止 / 清空 / 复制（对齐 mq-ui CommandToolbar） ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(
                onClick = ::runSql,
                // 按钮不抢输入框焦点（否则 TextField 失焦）。只控制焦点，
                // 完全不碰 TextFieldValue.selection —— 选区由 Compose 原生管理。
                modifier = Modifier.focusProperties { canFocus = false },
                // 连接中 / 拉取中禁用：防狂点导致并发建连 / 重复查询。
                enabled = !connecting && !grid.streaming,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource("olap.starrocks.console.run"))
            }
            Text(
                stringResource("olap.starrocks.console.run"),
                style = MaterialTheme.typography.labelLarge,
            )

            IconButton(
                onClick = { job?.cancel() },
                modifier = Modifier.focusProperties { canFocus = false },
                enabled = connecting || grid.streaming,
            ) {
                Icon(Icons.Filled.Stop, contentDescription = stringResource("olap.starrocks.console.stop"))
            }
            IconButton(
                onClick = ::clearAll,
                modifier = Modifier.focusProperties { canFocus = false },
                enabled = textFieldValue.text.isNotEmpty() || grid.hasRun,
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource("olap.starrocks.console.clear"))
            }
            IconButton(
                onClick = ::copyResults,
                modifier = Modifier.focusProperties { canFocus = false },
                enabled = grid.rows.isNotEmpty(),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource("olap.starrocks.console.copy"))
            }
            IconButton(
                onClick = { showHistory = true },
                modifier = Modifier.focusProperties { canFocus = false },
            ) {
                Icon(Icons.Filled.History, contentDescription = stringResource("olap.starrocks.console.history"))
            }
        }

        // ── 历史记录面板：最近 50 条，点击把 SQL 填回编辑器 ──
        if (showHistory) {
            HistoryDialog(
                connectionId = connectionId,
                onPick = { sql ->
                    textFieldValue = TextFieldValue(sql)
                    showHistory = false
                },
                onDismiss = { showHistory = false },
            )
        }

        // ── SQL 编辑器：多行等宽，占剩余高度 35% ──
        OutlinedTextField(
            value = textFieldValue,
            // 原生选区完全交给 Compose：直接赋值，绝不修改 it.selection。
            // 之前「失焦时保住旧选区」的自定义逻辑会把上一次的选区写回 state，
            // 造成「高亮在新行、执行却拿到旧行」的错位 —— 一律删除。
            onValueChange = { textFieldValue = it },
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
        ResultArea(grid, connecting, Modifier.fillMaxWidth().weight(0.65f).padding(8.dp))
    }
}

/**
 * 「执行选中」的取源逻辑（与 mq-ui `CommandConsoleViewModel.execute` 同构）：
 * selection 在反向选择（从后往前拖）时 start > end，必须用 min/max 归一，
 * 否则 substring 会抛 StringIndexOutOfBoundsException。
 * 返回 null = 没有选中文本（调用方回落全文）。
 */
internal fun selectedSource(textFieldValue: TextFieldValue): String? {
    val selection = textFieldValue.selection
    // 无选区（光标只是一个闪烁竖线）→ 返回 null，由调用方执行全文。
    if (selection.start == selection.end) return null
    // 反向选择（从后往前拖）时 start > end，用 min/max 归一防 substring 越界；
    // 除此之外**原样截取**，不改写任何选区状态。
    return textFieldValue.text.substring(selection.min, selection.max)
}

/**
 * 分号切分多语句（MVP：不处理字符串字面量内的分号 —— SQL 字面量里写分号的
 * 场景在本工具的目标查询里极少）。剔除 `--` 行注释与空段，末尾多余分号
 * 自然产生空段被过滤。
 */
internal fun splitStatements(text: String): List<String> =
    text.split(';')
        .map { segment ->
            segment.lines()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .trim()
        }
        .filter { it.isNotEmpty() }

/**
 * 「历史记录」面板：最近 50 条执行事实（SQL / 状态 / 耗时 / 错误信息），
 * 点击任一条把该 SQL 填回编辑器。**只查审计表，不碰结果集**。
 */
@Composable
private fun HistoryDialog(
    connectionId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var entries by remember { mutableStateOf<List<OlapQueryRecord>>(emptyList()) }
    LaunchedEffect(connectionId) {
        entries = runCatching { OlapQueryRepository.recent(connectionId, 50) }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("olap.starrocks.console.history_title")) },
        text = {
            if (entries.isEmpty()) {
                Text(
                    stringResource("olap.starrocks.console.history_empty"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    Modifier.height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries) { entry ->
                        val ok = entry.status == OlapQueryRecord.STATUS_SUCCESS
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entry.sqlText) }
                                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                                .padding(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(
                                        if (ok) "olap.starrocks.console.history_ok"
                                        else "olap.starrocks.console.history_fail",
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (ok) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    "  ${formatTime(entry.createdAt)}  ${entry.durationMs} ms",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Text(
                                (entry.errorMessage ?: entry.sqlText).take(120).replace('\n', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource("common_close")) }
        },
    )
}

/** 历史条目时间戳（本机时区，jvmMain 可用 java.text）。 */
private fun formatTime(epochMs: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(java.util.Date(epochMs))

@Composable
private fun ResultArea(grid: ConsoleGrid, connecting: Boolean, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            // 懒加载自动连接中。
            connecting -> ProgressHint(stringResource("olap.starrocks.console.connecting"))

            // 执行失败（清空了之前的表格）。
            grid.error != null -> Text(
                stringResource("olap.starrocks.console.error_prefix") + grid.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )

            // 初始：还没执行过。
            !grid.hasRun -> Text(
                stringResource("olap.starrocks.console.idle_hint"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            // SELECT：流式表格（拉取中顶部横幅「正在加载中...」）。
            else -> ResultTable(grid, Modifier.fillMaxSize())
        }
    }
}

// TODO: 结果表格支持列宽拖拽与冻结列（虚拟滚动已由 LazyColumn 承担）

/** 流式结果表格：行落在快照 List，边收边渲染；顶部横幅反映拉取进度。 */
@Composable
private fun ResultTable(grid: ConsoleGrid, modifier: Modifier = Modifier) {
    Column(modifier) {
        // ── 顶部状态横幅：拉取中显示进度；截断显示保护提示 ──
        when {
            grid.streaming -> StreamingBanner(
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

private val HEADER_CELL_WIDTH = 160.dp
