package com.mqclient.starrocks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import com.mqclient.core.ServiceException
import com.mqclient.core.db.OlapQueryRepository
import com.mqclient.core.log.LogLevel
import com.mqclient.starrocks.StarRocksModule
import com.mqclient.starrocks.log.StarRocksLog
import com.mqclient.uicommon.LocalSnackbarHostState
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「SQL 控制台」导航项的内容页：流式渲染 + 多语句执行。
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
 * `Flow<ResultBatch>`）；UI **边收集并渲染** —— 行落在 [ConsoleGrid.rows]
 * （Compose 快照 List），`LazyColumn` 虚拟化只组装可见行。
 *
 * ## 文件组织
 *
 * 控制台的代码按「状态 / 文本 / 渲染 / 历史」切开，本文件只留入口与执行状态机：
 *  - 本文件 —— [StarRocksSqlConsolePanel] 入口、Tab 容器、[ConsoleBody] 执行流程；
 *  - [StarRocksSqlConsoleState] —— [ConsolePhase] / [ConsoleGrid] / 截断信号等状态模型；
 *  - [StarRocksSqlText] —— 选区取源、分号切分、TSV 导出等纯文本函数（已有单测）；
 *  - [StarRocksSqlConsoleResult] —— 结果区 / 结果表格 / 状态横幅等渲染；
 *  - [StarRocksSqlConsoleHistory] —— 执行历史面板（只读审计表）。
 */
@Composable
internal fun StarRocksSqlConsolePanel(modifier: Modifier = Modifier, connectionId: String = "") {
    // TODO: 监听 StarRocksConnectionManager 的关闭事件，清理当前连接下的所有 Tabs，重连时自动恢复

    // 状态按 connectionId 分键：切连接后不能留着上一条连接的编辑器内容与结果集
    // （宿主在同一个组合槽位里渲染各面板，不带键的 remember 会跨连接存活）。
    var tabCount by remember(connectionId) { mutableIntStateOf(1) }
    var selected by remember(connectionId) { mutableIntStateOf(0) }

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
                            // TODO: 支持多 Tab 独立维护各自的 SQL 和结果状态（目前各 Tab 共享一份）
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

/** 单个 Tab 的内容：工具栏 + SQL 编辑器 + 结果区（目前各 Tab 共享一份状态）。 */
@Composable
private fun ConsoleBody(modifier: Modifier = Modifier, connectionId: String) {
    // TextFieldValue（而非纯 String）：与 mq-ui CommandConsoleViewModel 同构，
    // 持有光标 / 选中态，「执行选中」依赖它。
    // 同上：全部按 connectionId 分键。编辑器草稿尤其重要 —— 否则在 A 连接写的 SQL
    // 会出现在 B 连接的编辑器里，一键执行就打到了错的库上。
    var textFieldValue by remember(connectionId) { mutableStateOf(TextFieldValue("")) }
    val grid = remember(connectionId) { ConsoleGrid() }
    var phase by remember(connectionId) { mutableStateOf(ConsolePhase.Idle) }
    var showHistory by remember(connectionId) { mutableStateOf(false) }
    var diagnosisSql by remember(connectionId) { mutableStateOf<String?>(null) }
    var draftLoaded by remember(connectionId) { mutableStateOf(false) }
    // CSV 导出：-1 = 空闲；>=0 = 导出中（已写入行数，进度横幅用）。
    var exportingRows by remember(connectionId) { mutableLongStateOf(-1L) }
    // 「查看本次 Profile」：取数中 + 取到 (queryId, 原始文本) 后弹大窗查看器。
    var profileFetching by remember(connectionId) { mutableStateOf(false) }
    var profileViewer by remember(connectionId) { mutableStateOf<Pair<String, String>?>(null) }
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberConnectionScope(connectionId)
    var job by remember(connectionId) { mutableStateOf<Job?>(null) }
    val clipboard = LocalClipboardManager.current
    // 导出文案在组合期解析（协程里用捕获值，避免在非组合上下文查表）。
    val exportSuccessText = stringResource("olap.starrocks.console.export_success")
    val exportFailedText = stringResource("olap.starrocks.console.export_failed")

    // 初始化 + 数据目录联动：先消费「双击表名」在 Tab 打开前挂起的 SQL，
    // 没有才回落草稿；随后常驻订阅后续请求（Tab 已打开时的实时联动）。
    LaunchedEffect(connectionId) {
        val requested = StarRocksConsoleSqlHolder.consume(connectionId)
        if (requested != null) {
            textFieldValue = TextFieldValue(requested)
        } else {
            // 从 SQLite 读回上次未提交的草稿（关闭应用后重进仍保留）。
            val draft = runCatching { OlapQueryRepository.loadDraft(connectionId) }.getOrNull()
            if (!draft.isNullOrEmpty()) textFieldValue = TextFieldValue(draft)
        }
        draftLoaded = true
        StarRocksConsoleSqlHolder.requests.collect { request ->
            if (request.connectionId == connectionId) {
                textFieldValue = TextFieldValue(request.sql)
            }
        }
    }

    // 防抖自动保存：文本变化后 1 秒无新输入才落库（LaunchedEffect 每次
    // key 变化会取消上一个协程 —— 这就是 Compose 版的 debounce）。
    // key 只取 textFieldValue.text：光标 / 选区变化不触发写盘。
    LaunchedEffect(textFieldValue.text, draftLoaded) {
        if (!draftLoaded) return@LaunchedEffect
        delay(DRAFT_DEBOUNCE_MS)
        runCatching { OlapQueryRepository.saveDraft(connectionId, textFieldValue.text) }
    }

    /** 复制结果为 TSV（表头 + 行），列以 Tab 分隔 —— 与主流数据库工具一致。
     *  拼接挪到 IO（5 万行 × 多列的字符串在 EDT 上拼会卡顿）。 */
    fun copyResults() {
        if (grid.columns.isEmpty() && grid.rows.isEmpty()) return
        scope.launch {
            val tsv = withContext(Dispatchers.IO) { buildTsv(grid.columns, grid.rows) }
            clipboard.setText(AnnotatedString(tsv))
        }
    }

    /**
     * 导出 CSV：AWT FileDialog 选路径 → **重新执行当前 SQL** 流式写入
     * （每批即写即 flush，不把 UI 快照列表二次拼接）。全程 IO，完成 / 失败
     * 用 Snackbar 提示；导出期间按钮禁用、进度横幅实时显示已写入行数。
     */
    fun exportCsv() {
        // 与查询执行互斥：单槽连接不支持并发，导出期间不允许再跑 runSql。
        if (exportingRows >= 0L || phase == ConsolePhase.Connecting || phase == ConsolePhase.Executing) return
        val source = selectedSource(textFieldValue) ?: textFieldValue.text
        val statement = splitStatements(source).firstOrNull() ?: source.trim()
        if (statement.isBlank()) return
        scope.launch {
            // AWT FileDialog 是模态阻塞调用，必须挂 EDT（Compose Main 即 Swing EDT）。
            val path = pickCsvSavePath() ?: return@launch
            exportingRows = 0
            try {
                val rows = withContext(Dispatchers.IO) {
                    streamCsvExport(connectionId, statement, path) { written -> exportingRows = written }
                }
                snackbar.showSnackbar(exportSuccessText.withArg(path, rows))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                snackbar.showSnackbar(exportFailedText.withArg(error.userMessage()))
            } finally {
                exportingRows = -1
            }
        }
    }

    /** 清空编辑器与结果（对齐 mq-ui 控制台的 clear）。 */
    fun clearAll() {
        if (phase == ConsolePhase.Connecting || phase == ConsolePhase.Executing) job?.cancel()
        textFieldValue = TextFieldValue("")
        grid.reset()
        phase = ConsolePhase.Idle
    }

    /**
     * 「查看本次 Profile」：从 `SHOW PROFILELIST` 提取最新一条**有效查询**（跳过
     * SHOW / SET / EXPLAIN 等系统语句）的完整 QueryId → ANALYZE PROFILE → 大窗查看器。
     * 取不到有效查询或 Profile 未采集时，Snackbar 展示带指引的业务异常文案。
     */
    fun viewLastProfile() {
        if (phase != ConsolePhase.Finished || grid.successCount == 0 || profileFetching) return
        profileFetching = true
        scope.launch {
            val start = System.currentTimeMillis()
            var attemptedId: String? = null
            try {
                val target = withContext(Dispatchers.IO) {
                    val raw = queryRows(connectionId, PROFILELIST_SQL)
                    val listed = parseProfileList(raw)
                    // 「找不到 Profile」的第一现场证据：列表到底是不是空的、里面的
                    // QueryId / State / Statement 长什么样。此前这段没有日志，
                    // 只能靠猜「列表为空」还是「ID 没匹配上」。
                    StarRocksLog.profile(
                        "profilelist rows=${listed.size} " +
                            "raw=${StarRocksLog.truncate(describeProfileList(raw, listed))}",
                    )
                    // PROFILELIST 按时间倒序：取最新一条可采集（非系统语句、已结束）的查询。
                    val item = listed
                        .firstOrNull { !isSystemStatement(it.statement) && it.state == "Finished" }
                    if (item == null) {
                        // 区分两种「没有可采集查询」：列表为空 vs 有查询但都被过滤掉。
                        StarRocksLog.profile(
                            LogLevel.WARN,
                            "no collectable query in profilelist rows=${listed.size} " +
                                "candidates=${StarRocksLog.truncate(describeCandidates(listed))}",
                        )
                        throw ServiceException(
                            errorKey = KEY_PROFILE_NOT_COLLECTED,
                            fallbackMessage = PROFILE_NOT_COLLECTED_FALLBACK,
                        )
                    }
                    attemptedId = item.queryId
                    StarRocksLog.profile(
                        "selected queryId=${item.queryId} state=${item.state} " +
                            "statement=${StarRocksLog.truncate(item.statement)}",
                    )
                    item.queryId to fetchQueryProfile(connectionId, item.queryId)
                }
                profileViewer = target
                StarRocksLog.profile(
                    "view done queryId=${target.first} chars=${target.second.length} " +
                        "cost=${System.currentTimeMillis() - start}ms",
                )
                StarRocksLog.perf(
                    action = "view_profile",
                    type = "PROFILE",
                    costMs = System.currentTimeMillis() - start,
                )
            } catch (cancellation: CancellationException) {
                StarRocksLog.profile(
                    LogLevel.DEBUG,
                    "view cancelled after ${System.currentTimeMillis() - start}ms",
                )
                throw cancellation
            } catch (error: Throwable) {
                // 弹给用户的那句「无法获取 Profile」：WARN，并带上耗时便于区分
                // 「查得快但没数据」与「查询本身超时」。
                StarRocksLog.profile(
                    LogLevel.WARN,
                    "view failed cost=${System.currentTimeMillis() - start}ms " +
                        "target=${attemptedId ?: "none"} error=${error.message}",
                )
                snackbar.showSnackbar(error.userMessage())
            } finally {
                profileFetching = false
            }
        }
    }

    fun runSql() {
        // 导出期间不允许再执行（互斥：单槽连接不支持并发查询）。
        if (exportingRows >= 0L) return
        if (phase == ConsolePhase.Connecting || phase == ConsolePhase.Executing) return
        // 严格基于原生 [TextFieldValue.selection] 取源：有选区只发选区，
        // 无选区发全文（再按分号切多语句）。不做任何 selection 的写回/干预。
        val selected = selectedSource(textFieldValue)
        val sqlToExecute = selected ?: textFieldValue.text
        // 用户按下「执行」：记清楚是选区还是全文（长度足够区分误操作），
        // 具体 SQL 由 queryRows / manager 的 [SQL] 行负责，不在这里重复打全文。
        StarRocksLog.sql(
            "console run connection=$connectionId " +
                if (selected != null) "scope=selection chars=${selected.length}" else "scope=full chars=${sqlToExecute.length}",
        )
        if (sqlToExecute.isBlank()) return
        val statements = splitStatements(sqlToExecute)
        if (statements.isEmpty()) return

        job = scope.launch {
            var start = 0L
            try {
                // 懒加载连接：规则与其它面板同源（service 的 ensureSession），
                // 这里只多挂一个「切到 Connecting 阶段」的 UI 回调。
                val connected = com.mqclient.starrocks.query.ensureSession(connectionId) {
                    phase = ConsolePhase.Connecting
                }
                if (connected.isFailure) {
                    grid.reset()
                    grid.hasRun = true
                    grid.error = stringResource("olap.starrocks.console.connect_failed")
                        .withArg(connected.exceptionOrNull()?.userMessage() ?: "unknown")
                    return@launch
                }

                // 连接就绪 → **立即**切到查询阶段（关键修复）：旧实现把
                // connecting 留到整个 launch 结束才复位，导致查询全程显示
                // 「正在建立连接...」。
                phase = ConsolePhase.Executing
                grid.reset()
                grid.hasRun = true
                start = System.currentTimeMillis()

                // 逐条顺序执行（对齐 mq-ui 控制台的逐条编排）：每条独立
                // try-catch —— 单条失败显示错误并停止后续，已成功的条目保留；
                // 取消经 CancellationException 在条目边界 / collect 内传播。
                for (statement in statements) {
                    try {
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
                                    throw StopStream() // 截断：保留已收集数据，横幅由 grid.truncated 表达
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
                    }
                }
            } catch (cancellation: CancellationException) {
                // Stop / Tab 切换等取消：不算错误，保留已完成的部分。
            } finally {
                // 全程兜底：无论成功 / 失败 / 取消，阶段机复位到 Finished，
                // 不残留任何上一轮的 Loading 文案。
                phase = ConsolePhase.Finished
                if (start > 0L) grid.durationMs = System.currentTimeMillis() - start
            }
        }
    }

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
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
                // 连接中 / 执行中禁用：防狂点导致并发建连 / 重复查询。
                enabled = phase != ConsolePhase.Connecting && phase != ConsolePhase.Executing,
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
                enabled = phase == ConsolePhase.Connecting || phase == ConsolePhase.Executing,
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
            // 导出 CSV：重新执行当前 SQL 流式写文件；导出中禁用（进度见下方横幅）。
            IconButton(
                onClick = ::exportCsv,
                modifier = Modifier.focusProperties { canFocus = false },
                enabled = phase != ConsolePhase.Connecting &&
                    phase != ConsolePhase.Executing &&
                    exportingRows < 0L,
            ) {
                Icon(Icons.Filled.Download, contentDescription = stringResource("olap.starrocks.console.export"))
            }
            IconButton(
                onClick = { showHistory = true },
                modifier = Modifier.focusProperties { canFocus = false },
            ) {
                Icon(Icons.Filled.History, contentDescription = stringResource("olap.starrocks.console.history"))
            }
            // 查看本次 Profile：SHOW PROFILELIST 取最新一条可采集查询的 QueryId
            // → ANALYZE PROFILE FROM '<id>' → 大窗查看器；取不到或未采集时 Snackbar
            // 给出带指引的提示。
            IconButton(
                onClick = ::viewLastProfile,
                modifier = Modifier.focusProperties { canFocus = false },
                enabled = phase == ConsolePhase.Finished && grid.successCount > 0 && !profileFetching,
            ) {
                if (profileFetching) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Insights, contentDescription = stringResource("olap.starrocks.console.view_profile"))
                }
            }
            // AI 慢查询诊断：取选中的 SQL（无选区取全文首条），交给诊断弹窗
            // 执行 EXPLAIN + 表结构组装后流式问模型。
            IconButton(
                onClick = {
                    val source = selectedSource(textFieldValue) ?: textFieldValue.text
                    val statement = splitStatements(source).firstOrNull() ?: source.trim()
                    if (statement.isNotBlank()) diagnosisSql = statement
                },
                modifier = Modifier.focusProperties { canFocus = false },
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = stringResource("olap.starrocks.ai.button"))
            }
        }

        // ── 导出进度横幅：结果区顶部实时显示已写入行数 ──
        if (exportingRows >= 0L) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource("olap.starrocks.console.exporting").withArg(exportingRows),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        // ── AI 诊断弹窗（独立 composition，关闭即取消所有协程 / 流式请求） ──
        diagnosisSql?.let { sql ->
            StarRocksAiDiagnosisDialog(
                connectionId = connectionId,
                sql = sql,
                onDismiss = { diagnosisSql = null },
            )
        }

        // ── 本次查询的 Profile 大窗查看器 ──
        profileViewer?.let { (queryId, raw) ->
            StarRocksProfileViewerDialog(
                title = stringResource("olap.starrocks.processlist.profile_title").withArg(queryId),
                raw = raw,
                onDismiss = { profileViewer = null },
            )
        }

        // ── SQL 编辑器：多行等宽，占剩余高度 35% ──
        // SQL 语法高亮：颜色随主题取（关键字=primary、字符串=secondary、数字=tertiary）；
        // remember 带颜色键 —— 主题切换时重建变换。纯视觉转换，不碰光标 / 选区。
        // 注意：remember 的 calculation lambda 不是 @Composable，颜色先取出来再捕获。
        val keywordColor = MaterialTheme.colorScheme.primary
        val stringColor = MaterialTheme.colorScheme.secondary
        val numberColor = MaterialTheme.colorScheme.tertiary
        val sqlTransformation = remember(keywordColor, stringColor, numberColor) {
            SqlHighlightTransformation(
                keywordColor = keywordColor,
                stringColor = stringColor,
                numberColor = numberColor,
            )
        }
        OutlinedTextField(
            value = textFieldValue,
            // 原生选区完全交给 Compose：直接赋值，绝不修改 it.selection。
            // 之前「失焦时保住旧选区」的自定义逻辑会把上一次的选区写回 state，
            // 造成「高亮在新行、执行却拿到旧行」的错位 —— 一律删除。
            onValueChange = { textFieldValue = it },
            visualTransformation = sqlTransformation,
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
        ResultArea(grid, phase, Modifier.fillMaxWidth().weight(0.65f).padding(8.dp))
        }

        // ── 执行历史：右侧常驻面板（overlay），点击条目回填 SQL ──
        if (showHistory) {
            HistoryPanel(
                connectionId = connectionId,
                onPick = { sql -> textFieldValue = TextFieldValue(sql) },
                onDismiss = { showHistory = false },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(420.dp),
            )
        }
    }
}

/** SHOW PROFILELIST 的原始形状摘要：列名 + 前几行（截断后进日志）。 */
private fun describeProfileList(
    raw: StarRocksQueryRows,
    listed: List<ProfileListItem>,
): String = buildString {
    append("columns=").append(raw.columns)
    raw.rows.take(3).forEachIndexed { index, row ->
        append(" | row").append(index).append('=').append(row.map { it?.toString() ?: "NULL" })
    }
    if (listed.isNotEmpty()) {
        append(" | firstId=").append(listed.first().queryId)
        append(" lastId=").append(listed.last().queryId)
    }
}

/** 候选过滤后的摘要：为什么没有一条被选中（state / 是否系统语句）。 */
private fun describeCandidates(listed: List<ProfileListItem>): String =
    listed.take(5).joinToString(" ; ") { item ->
        val system = isSystemStatement(item.statement)
        "id=${item.queryId} state=${item.state} system=$system"
    }
