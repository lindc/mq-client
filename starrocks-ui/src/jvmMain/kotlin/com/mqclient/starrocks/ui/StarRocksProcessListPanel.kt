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
import androidx.compose.material.icons.filled.Refresh
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
import com.mqclient.core.log.LogLevel
import com.mqclient.starrocks.log.StarRocksLog
import com.mqclient.uicommon.AppTooltip
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 最近查询（`SHOW PROFILELIST` 的一行）：用户能看到**已执行完**的慢查询，
 * QueryId 天然有效 —— 「查看 Profile」不再依赖任何会话映射。
 */
internal data class ProcessInfo(
    val queryId: String,
    val startTime: String,
    /** 原样耗时字符串（如 `3s16ms`）。 */
    val timeText: String,
    /** 耗时换算成秒（慢查询判定用）。 */
    val timeSeconds: Long,
    val state: String,
    val statement: String,
)

/** 慢查询阈值（秒）：超过则耗时列标红。 */
internal const val SLOW_QUERY_SECONDS = 10L

/** 自动刷新间隔。 */
private const val AUTO_REFRESH_MS = 5_000L

/** 按列名解析 SHOW PROFILELIST → 列表行（QueryId 缺失的行丢弃，绝不崩面板）。 */
internal fun parseProcessList(result: StarRocksQueryRows): List<ProcessInfo> {
    val cols = result.columns.map { it.lowercase() }
    fun idx(name: String): Int = cols.indexOf(name)

    val qidIdx = idx("queryid")
    val startIdx = idx("starttime")
    val timeIdx = idx("time")
    val stateIdx = idx("state")
    val stmtIdx = idx("statement")

    fun cell(row: List<Any?>, i: Int): String = row.getOrNull(i)?.toString().orEmpty().ifBlank { "-" }

    return result.rows.mapNotNull { row ->
        val queryId = cell(row, qidIdx)
        if (queryId == "-") return@mapNotNull null
        val timeText = cell(row, timeIdx).takeIf { it != "-" } ?: ""
        ProcessInfo(
            queryId = queryId,
            startTime = cell(row, startIdx),
            timeText = timeText,
            timeSeconds = timeToNanos(timeText) / 1_000_000_000L,
            state = cell(row, stateIdx),
            statement = cell(row, stmtIdx).takeIf { it != "-" } ?: "",
        )
    }
}

/**
 * 「会话与慢查询」监控面板（只读）。数据源为 `SHOW PROFILELIST`：
 * 展示**最近的查询记录**（含已结束的慢查询），每行可直接查看 Profile ——
 * QueryId 来自列表本身，不再依赖任何会话映射。
 *
 * - 取数复用 [queryRows] → `executeQuery`；`withContext(Dispatchers.IO)` 保证
 *   网络请求不压在 UI 调度器上；自动刷新挂在 [LaunchedEffect] 上，页面关闭
 *   随组合销毁自动 cancel，无泄漏定时器。
 * - 慢查询（time > 10s）耗时列与行底色标红。
 * - 系统 / 元数据语句（SHOW / SET / USE / EXPLAIN…）不产生 Profile 树，
 *   「查看 Profile」按钮置灰并悬浮提示（[isSystemStatement]）。
 */
@Composable
internal fun StarRocksProcessListPanel(connectionId: String, modifier: Modifier = Modifier) {
    var sessions by remember { mutableStateOf<List<ProcessInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoRefresh by remember { mutableStateOf(false) }
    var profileTarget by remember { mutableStateOf<ProfileDetailTarget?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        error = null
        val start = System.currentTimeMillis()
        try {
            val result = withContext(Dispatchers.IO) { queryRows(connectionId, PROFILELIST_SQL) }
            sessions = parseProcessList(result)
            StarRocksLog.profile(
                "processlist rows=${sessions.size} rawRows=${result.rows.size} " +
                    "cost=${System.currentTimeMillis() - start}ms",
            )
        } catch (e: Throwable) {
            // 面板直接展示错误文案：WARN + 栈（自动刷新关闭前每次失败都会留一条）。
            StarRocksLog.profile(
                LogLevel.WARN,
                "processlist load failed connection=$connectionId " +
                    "cost=${System.currentTimeMillis() - start}ms error=${e.message}",
                e,
            )
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

            else -> ProcessTable(
                sessions,
                onProfile = { profileTarget = ProfileDetailTarget(it.queryId, it.statement) },
            )
        }
    }

    // ── Profile 详情弹窗（指标 / Top 算子 / 完整查看器 / AI 联动） ──
    profileTarget?.let { target ->
        ProfileDetailDialog(
            connectionId = connectionId,
            target = target,
            onDismiss = { profileTarget = null },
        )
    }
}

/** 查询记录表格：固定列宽 + 整表横向滚动，纵向 LazyColumn 虚拟化。 */
@Composable
private fun ProcessTable(
    sessions: List<ProcessInfo>,
    onProfile: (ProcessInfo) -> Unit,
) {
    val horizontal = rememberScrollState()
    Row(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
        LazyColumn {
            item { ProcessRow(header = true, session = null, onProfile = {}) }
            items(sessions, key = { it.queryId }) { session ->
                ProcessRow(header = false, session = session, onProfile = onProfile)
            }
        }
    }
}

@Composable
private fun ProcessRow(
    header: Boolean,
    session: ProcessInfo?,
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
            ProcessCell(stringResource("olap.starrocks.processlist.col_id"), 200.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_start_time"), 150.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_time"), 90.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_state"), 90.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_sql"), 320.dp, header = true)
            ProcessCell(stringResource("olap.starrocks.processlist.col_profile"), 110.dp, header = true)
            return@Row
        }

        val s = session ?: return@Row
        // QueryId 全文走列内省略：点击复制 / Profile 传参用的都是完整 UUID，绝不截断。
        ProcessCell(s.queryId, 200.dp, monospace = true)
        ProcessCell(s.startTime, 150.dp)
        // 慢查询：耗时列标红（整行底色同步淡红）。
        ProcessCell(s.timeText, 90.dp, color = if (slow) Color.Red else Color.Unspecified)
        ProcessCell(s.state, 90.dp)
        ProcessCell(s.statement.ifBlank { "-" }, 320.dp, monospace = true)
        // 「查看 Profile」：系统 / 元数据语句（SHOW / SET / USE / EXPLAIN…）不产生
        // Profile 树 —— 按钮置灰 + 悬浮提示；Profile 传参用列表自带的完整 QueryId。
        Box(Modifier.width(110.dp), contentAlignment = Alignment.Center) {
            if (isSystemStatement(s.statement)) {
                AppTooltip(
                    tooltip = {
                        Text(
                            stringResource("olap.starrocks.processlist.profile_eligible_hint"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                ) {
                    TextButton(onClick = {}, enabled = false) {
                        Text(
                            stringResource("olap.starrocks.processlist.profile"),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            } else {
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
