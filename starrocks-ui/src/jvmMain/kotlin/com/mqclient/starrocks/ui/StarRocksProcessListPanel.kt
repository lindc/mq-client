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
import androidx.compose.material.icons.Icons
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** 当前会话（information_schema.processlist 的一行）。 */
internal data class ProcessInfo(
    val id: String,
    val user: String,
    val host: String,
    val db: String,
    val command: String,
    val state: String,
    val timeSeconds: Long,
    val info: String,
)

internal const val PROCESSLIST_SQL =
    "SELECT id, user, host, db, command, time, state, info FROM information_schema.processlist;"

/** 慢查询阈值（秒）：超过则耗时列标红。 */
internal const val SLOW_QUERY_SECONDS = 10L

/** 自动刷新间隔。 */
private const val AUTO_REFRESH_MS = 5_000L

/**
 * 按**列名**解析 processlist（StarRocks 与 MySQL 的列名 / 顺序可能有差异）。
 * 缺列用空串 / 0 兜底，绝不因形状不同崩面板。
 */
internal fun parseProcessList(result: StarRocksQueryRows): List<ProcessInfo> {
    val cols = result.columns.map { it.lowercase() }
    fun indexOfAny(vararg names: String): Int? =
        names.firstNotNullOfOrNull { n -> cols.indexOf(n.lowercase()).takeIf { it >= 0 } }

    val idIdx = indexOfAny("id", "process_id", "connection_id")
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
        )
    }
}

/**
 * Kill 语句：id 先校验为纯数字（**防 SQL 注入**），再生成两种语法。
 * StarRocks 与 MySQL 对 `KILL QUERY` 的支持不同，逐条降级尝试。
 */
internal fun killStatements(id: String): List<String>? {
    val numeric = id.trim().toLongOrNull() ?: return null
    return listOf("KILL QUERY $numeric;", "KILL $numeric;")
}

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
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current
    // 单条 JDBC 连接不支持并发查询：自动刷新与手动 / Kill 后刷新重叠时直接跳过，
    // 避免 "Streaming result set is still active" 与请求堆积。
    val loadMutex = remember { Mutex() }

    // 文案在组合期解析（协程里用捕获值，避免在非组合上下文查表）。
    val killedText = stringResource("olap.starrocks.processlist.killed")
    val killFailedTemplate = stringResource("olap.starrocks.processlist.kill_failed")

    suspend fun load() {
        if (!loadMutex.tryLock()) return
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) { queryRows(connectionId, PROCESSLIST_SQL) }
            sessions = parseProcessList(result)
        } catch (e: Throwable) {
            error = e.userMessage()
            sessions = emptyList()
        } finally {
            loading = false
            loadMutex.unlock()
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
                error = killFailedTemplate.withArg(target.id)
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

            else -> ProcessTable(sessions, onKill = { killTarget = it })
        }
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
private fun ProcessTable(sessions: List<ProcessInfo>, onKill: (ProcessInfo) -> Unit) {
    val horizontal = rememberScrollState()
    Row(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
        LazyColumn {
            item { ProcessRow(header = true, session = null, onKill = {}) }
            items(sessions, key = { it.id }) { session ->
                ProcessRow(header = false, session = session, onKill = onKill)
            }
        }
    }
}

@Composable
private fun ProcessRow(header: Boolean, session: ProcessInfo?, onKill: (ProcessInfo) -> Unit) {
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
