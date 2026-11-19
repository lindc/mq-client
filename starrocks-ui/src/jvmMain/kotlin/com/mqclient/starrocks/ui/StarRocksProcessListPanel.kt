package com.mqclient.starrocks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.DangerButton
import com.mqclient.uicommon.LocalSnackbarHostState
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 当前会话（`SHOW FULL PROCESSLIST` 的一行）。 */
internal data class ProcessInfo(
    val id: String,
    val user: String,
    val host: String,
    val db: String,
    val command: String,
    val state: String,
    val timeSeconds: Long,
    val info: String,
    /**
     * StarRocks 的 queryId（UUID）：优先 query_id 列，其次 [CURRENT_QUERIES_SQL]
     * 的连接 id → QueryId 映射，都没有时回落连接 id（Profile 会得到友好报错）。
     */
    val queryId: String,
)

// 会话列表数据源：StarRocks 的 information_schema 里**没有** processlist 表
// （3.4.0 实测报 Unknown table），MySQL 风格的 `SHOW FULL PROCESSLIST` 在 FE 上
// 原生支持；带 FULL 是为了 Info 列返回完整 SQL（不带会截断到 100 字符）。
internal const val PROCESSLIST_SQL = "SHOW FULL PROCESSLIST;"

// 运行中查询的「连接 id → QueryId」映射数据源：processlist（3.4 实测）不带
// query_id 列，而 Profile 语句需要的是 UUID —— `SHOW PROC '/current_queries'`
// 的 QueryId / ConnectionId 两列正好补上这个缺口。
internal const val CURRENT_QUERIES_SQL = "SHOW PROC '/current_queries';"

// 本会话最近一次查询的 QueryId：Profile 只认 UUID，而 processlist / current_queries
// 都只覆盖「正在运行」的查询 —— SQL 控制台与面板共用同一会话（单槽串行），
// `LAST_QUERY_ID()` 取到的正是用户上一条控制台查询（须在面板自己的查询**之前**取，
// 否则会被本次 load 的 SHOW/PROC 语句覆盖）。
internal const val OWN_LAST_QUERY_SQL =
    "SELECT CONNECTION_ID() AS conn_id, LAST_QUERY_ID() AS last_qid;"

/** 慢查询阈值（秒）：超过则耗时列标红。 */
internal const val SLOW_QUERY_SECONDS = 10L

/** 自动刷新间隔。 */
private const val AUTO_REFRESH_MS = 5_000L

/**
 * 按**列名**解析 processlist（StarRocks 与 MySQL 的列名 / 顺序可能有差异）。
 * 缺列用空串 / 0 兜底，绝不因形状不同崩面板。
 */
internal fun parseProcessList(
    result: StarRocksQueryRows,
    queryIdsByConnection: Map<String, String> = emptyMap(),
): List<ProcessInfo> {
    val cols = result.columns.map { it.lowercase() }
    fun indexOfAny(vararg names: String): Int? =
        names.firstNotNullOfOrNull { n -> cols.indexOf(n.lowercase()).takeIf { it >= 0 } }

    val idIdx = indexOfAny("id", "process_id", "connection_id")
    // query_id 列（UUID）只有部分来源提供 —— `SHOW FULL PROCESSLIST`（3.4 实测）
    // 就没有这一列；缺失时回落连接 id，保证「查看 Profile」链路在没有真实环境时
    // 也能点通。列名匹配统一在 lowercase 后进行（cols 已归一），无需列大小写变体。
    val queryIdIdx = indexOfAny("query_id", "queryid")
    val userIdx = indexOfAny("user")
    val hostIdx = indexOfAny("host")
    val dbIdx = indexOfAny("db", "database")
    val commandIdx = indexOfAny("command")
    val timeIdx = indexOfAny("time")
    val stateIdx = indexOfAny("state")
    val infoIdx = indexOfAny("info", "sql", "query")

    fun cell(row: List<Any?>, idx: Int?): String =
        idx?.let { row.getOrNull(it)?.toString() }.orEmpty().ifBlank { "-" }

    return result.rows.mapNotNull { row ->
        val id = cell(row, idIdx)
        // id 必须是数字：既是 processlist 的事实，也是 Kill 语句的注入防线。
        if (id.toLongOrNull() == null) return@mapNotNull null
        ProcessInfo(
            id = id,
            user = cell(row, userIdx),
            host = cell(row, hostIdx),
            db = cell(row, dbIdx),
            command = cell(row, commandIdx),
            state = cell(row, stateIdx),
            timeSeconds = timeIdx?.let { row.getOrNull(it)?.toString()?.toLongOrNull() } ?: 0L,
            info = infoIdx?.let { row.getOrNull(it)?.toString() }.orEmpty().ifBlank { "" },
            queryId = cell(row, queryIdIdx).takeUnless { it == "-" }
                ?: queryIdsByConnection[id] ?: id,
        )
    }
}

/**
 * `SHOW PROC '/current_queries'` → 连接 id → 真实 QueryId（UUID）。
 *
 * 列名按 lowercase 匹配（ConnectionId / QueryId，3.4.0 实测）；列缺失或形状
 * 不符返回空 map，不影响会话列表本身 —— 只是「查看 Profile」退回连接 id。
 * ConnectionId 必须是数字（与 processlist 的 id 同源，防脏数据进 Kill/Profile 链）。
 */
internal fun parseQueryIdsByConnection(result: StarRocksQueryRows): Map<String, String> {
    val cols = result.columns.map { it.lowercase() }
    val connIdx = cols.indexOf("connectionid").takeIf { it >= 0 } ?: return emptyMap()
    val qidIdx = cols.indexOf("queryid").takeIf { it >= 0 } ?: return emptyMap()
    return buildMap {
        result.rows.forEach { row ->
            val conn = row.getOrNull(connIdx)?.toString()?.trim().orEmpty()
            val qid = row.getOrNull(qidIdx)?.toString()?.trim().orEmpty()
            if (conn.toLongOrNull() != null && qid.isNotEmpty()) put(conn, qid)
        }
    }
}

/** [OWN_LAST_QUERY_SQL] → (本会话连接 id, 最近一次查询的 QueryId)；形状不符返回 null。 */
internal fun parseOwnLastQuery(result: StarRocksQueryRows): Pair<String, String>? {
    val cols = result.columns.map { it.lowercase() }
    val connIdx = cols.indexOf("conn_id").takeIf { it >= 0 } ?: return null
    val qidIdx = cols.indexOf("last_qid").takeIf { it >= 0 } ?: return null
    val row = result.rows.firstOrNull() ?: return null
    val conn = row.getOrNull(connIdx)?.toString()?.trim().orEmpty()
    val qid = row.getOrNull(qidIdx)?.toString()?.trim().orEmpty()
    if (conn.toLongOrNull() == null || qid.isEmpty()) return null
    return conn to qid
}

/**
 * Kill 语句：id 先校验为纯数字（**防 SQL 注入**），再生成两种语法。
 * StarRocks 与 MySQL 对 `KILL QUERY` 的支持不同，逐条降级尝试。
 */
internal fun killStatements(id: String): List<String>? {
    val numeric = id.trim().toLongOrNull() ?: return null
    return listOf("KILL QUERY $numeric;", "KILL $numeric;")
}

// Profile 取数与解析（profileStatements / fetchQueryProfile / parseProfileSummary /
// formatProfileText）已迁到 StarRocksProfileSupport.kt；弹窗在 StarRocksProfileDialogs.kt。

/**
 * 「会话与慢查询」只读监控面板（唯一的写操作是 Kill）。
 *
 * - 取数复用 [queryRows] → `executeQuery`；`withContext(Dispatchers.IO)` 保证
 *   网络请求不压在 UI 调度器上；定时刷新挂在 [LaunchedEffect] 上，页面关闭
 *   随组合销毁自动 cancel，无泄漏定时器。
 * - 慢查询（time > 10s）耗时列与行底色标红；每行末尾红色「终止」按钮。
 * - Kill 前有 [AlertDialog] 二次确认，成功后 Snackbar 提示并立刻刷新。
 */
@Composable
internal fun StarRocksProcessListPanel(connectionId: String, modifier: Modifier = Modifier) {
    var sessions by remember { mutableStateOf<List<ProcessInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoRefresh by remember { mutableStateOf(false) }
    var killTarget by remember { mutableStateOf<ProcessInfo?>(null) }
    var profileTarget by remember { mutableStateOf<ProfileDetailTarget?>(null) }
    var profileListOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current
    // 单连接并发防护已下沉到 StarRocksModule.executeQuery 的串行锁：
    // 手动刷新 / 自动刷新 / Kill / Profile 并发时在此自动排队，面板无需自行加锁。

    // 文案在组合期解析（协程里用捕获值，避免在非组合上下文查表）。
    val killedText = stringResource("olap.starrocks.processlist.killed")
    val killFailedTemplate = stringResource("olap.starrocks.processlist.kill_failed")

    suspend fun load() {
        loading = true
        error = null
        try {
            // 必须最先取：面板自己的查询会覆盖 LAST_QUERY_ID()，取晚了就只剩上一轮 load。
            val ownLast = withContext(Dispatchers.IO) {
                runCatching { parseOwnLastQuery(queryRows(connectionId, OWN_LAST_QUERY_SQL)) }.getOrNull()
            }
            val result = withContext(Dispatchers.IO) { queryRows(connectionId, PROCESSLIST_SQL) }
            // query_id 映射取不到只影响「查看 Profile」（退回连接 id），不影响会话列表。
            val queryIds = withContext(Dispatchers.IO) {
                val map = runCatching { parseQueryIdsByConnection(queryRows(connectionId, CURRENT_QUERIES_SQL)) }
                    .getOrDefault(emptyMap())
                    .toMutableMap()
                ownLast?.let { (conn, qid) -> map.putIfAbsent(conn, qid) }
                map
            }
            sessions = parseProcessList(result, queryIds)
        } catch (e: Throwable) {
            error = e.userMessage()
            sessions = emptyList()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(connectionId) { load() }

    // 自动刷新：LaunchedEffect 的协程随页面销毁自动取消（不需要手动 cancel Job）。
    LaunchedEffect(autoRefresh, connectionId) {
        if (!autoRefresh) return@LaunchedEffect
        while (true) {
            delay(AUTO_REFRESH_MS)
            load()
        }
    }

    fun confirmKill(target: ProcessInfo) {
        killTarget = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                for (sql in killStatements(target.id).orEmpty()) {
                    if (runCatching { queryRows(connectionId, sql) }.isSuccess) return@withContext true
                }
                false
            }
            if (ok) {
                snackbar.showSnackbar(killedText)
                load() // 成功后立刻刷新，让用户看到会话已消失
            } else {
                // 操作结果走 Snackbar：写全局 error 会整屏盖掉会话表。
                snackbar.showSnackbar(killFailedTemplate.withArg(target.id))
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        // ── 工具栏：标题 + 自动刷新开关 + 手动刷新 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource("olap.starrocks.processlist.title"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource("olap.starrocks.processlist.auto_refresh"),
                style = MaterialTheme.typography.labelMedium,
            )
            Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it })
            // 最近查询（Profile）：SHOW PROFILELIST 的已结束查询直达 Profile 详情。
            IconButton(onClick = { profileListOpen = true }) {
                Icon(Icons.Filled.History, contentDescription = stringResource("olap.starrocks.profilelist.title"))
            }
            IconButton(onClick = { scope.launch { load() } }, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource("olap.starrocks.catalog.refresh"))
            }
        }

        when {
            loading && sessions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(
                        stringResource("olap.starrocks.processlist.loading"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource("olap.starrocks.processlist.error_prefix") + error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }

            sessions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource("olap.starrocks.processlist.empty"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> ProcessTable(
                sessions,
                onKill = { killTarget = it },
                onProfile = { profileTarget = ProfileDetailTarget(it.queryId, it.info) },
            )
        }
    }

    // ── Profile 闭环弹窗：最近查询列表 + 详情（指标 / Top 算子 / 原始文本 / AI 联动） ──
    if (profileListOpen) {
        ProfileListDialog(
            connectionId = connectionId,
            onOpenProfile = { item ->
                profileListOpen = false
                profileTarget = ProfileDetailTarget(item.queryId, item.statement)
            },
            onDismiss = { profileListOpen = false },
        )
    }
    profileTarget?.let { target ->
        ProfileDetailDialog(
            connectionId = connectionId,
            target = target,
            onDismiss = { profileTarget = null },
        )
    }

    // ── Kill 二次确认（危险操作拦截） ──
    killTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text(stringResource("olap.starrocks.processlist.kill_title")) },
            text = {
                Column {
                    Text(
                        stringResource("olap.starrocks.processlist.kill_message")
                            .withArg(target.timeSeconds, target.id),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (target.info.isNotBlank()) {
                        Text(
                            target.info.take(200),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                DangerButton(onClick = { confirmKill(target) }) {
                    Text(stringResource("olap.starrocks.processlist.kill"))
                }
            },
            dismissButton = {
                SecondaryButton(onClick = { killTarget = null }) {
                    Text(stringResource("common_cancel"))
                }
            },
        )
    }
}

/** 会话表格：固定列宽 + 整表横向滚动，纵向 LazyColumn 虚拟化。 */
@Composable
private fun ProcessTable(
    sessions: List<ProcessInfo>,
    onKill: (ProcessInfo) -> Unit,
    onProfile: (ProcessInfo) -> Unit,
) {
    val horizontal = rememberScrollState()
    Row(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
        LazyColumn {
            item { ProcessRow(header = true, session = null, onKill = {}, onProfile = {}) }
            items(sessions, key = { it.id }) { session ->
                ProcessRow(header = false, session = session, onKill = onKill, onProfile = onProfile)
            }
        }
    }
}

@Composable
private fun ProcessRow(
    header: Boolean,
    session: ProcessInfo?,
    onKill: (ProcessInfo) -> Unit,
    onProfile: (ProcessInfo) -> Unit,
) {
    val slow = session?.let { it.timeSeconds > SLOW_QUERY_SECONDS } ?: false
    val rowColor = when {
        header -> MaterialTheme.colorScheme.surfaceVariant
        slow -> Color.Red.copy(alpha = 0.08f)   // 慢查询行淡红底
        else -> Color.Transparent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(rowColor)
            .padding(vertical = 6.dp),
    ) {
        if (header) {
            ProcessCell(stringResource("olap.starrocks.processlist.col_id"), 90.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_user"), 120.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_host"), 150.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_db"), 120.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_state"), 110.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_time"), 90.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_sql"), 320.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_action"), 90.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_profile"), 110.dp, header = true)
            return@Row
        }

        val s = session ?: return@Row
        ProcessCell(s.id, 90.dp)
        ProcessCell(s.user, 120.dp)
        ProcessCell(s.host, 150.dp)
        ProcessCell(s.db, 120.dp)
        ProcessCell(s.state, 110.dp)
        // 慢查询：耗时列标红（整行底色同步淡红）。
        ProcessCell(s.timeSeconds.toString(), 90.dp, color = if (slow) Color.Red else Color.Unspecified)
        ProcessCell(s.info.ifBlank { "-" }, 320.dp, monospace = true)
        Box(Modifier.width(90.dp), contentAlignment = Alignment.Center) {
            TextButton(onClick = { onKill(s) }) {
                Text(
                    stringResource("olap.starrocks.processlist.kill"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        // 「查看 Profile」：只拉取 + 文本展示，不做树形解析。
        Box(Modifier.width(110.dp), contentAlignment = Alignment.Center) {
            TextButton(onClick = { onProfile(s) }) {
                Text(
                    stringResource("olap.starrocks.processlist.profile"),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ProcessCell(
    text: String,
    width: Dp,
    header: Boolean = false,
    color: Color = Color.Unspecified,
    monospace: Boolean = false,
) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        fontFamily = if (monospace) FontFamily.Monospace else null,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(horizontal = 8.dp),
    )
}

