package com.mqclient.starrocks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.core.db.OlapQueryRecord
import com.mqclient.core.db.OlapQueryRepository
import com.mqclient.uicommon.DangerButton
import com.mqclient.uicommon.LocalSnackbarHostState
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.table.formatTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「执行历史」右侧面板：最近 50 条（时间倒序），点击回填 SQL，可一键清空。
 * **只查审计表，不碰结果集**。
 *
 * 形态是叠加在主内容右侧的常驻面板（Box 对齐 CenterEnd），主内容左侧仍可交互；
 * 关闭 / 切 Tab 随组合销毁。
 */
@Composable
internal fun HistoryPanel(
    connectionId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var entries by remember(connectionId) { mutableStateOf<List<OlapQueryRecord>>(emptyList()) }
    var confirmClear by remember(connectionId) { mutableStateOf(false) }
    val scope = rememberConnectionScope(connectionId)
    val snackbar = LocalSnackbarHostState.current
    val clearedText = stringResource("olap.starrocks.console.history_cleared")

    fun reload() {
        scope.launch {
            entries = runCatching { OlapQueryRepository.recent(connectionId, 50) }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(connectionId) { reload() }

    Column(
        modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
    ) {
        // ── 标题栏：标题 + 清空 + 关闭 ──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource("olap.starrocks.console.history_title"),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource("olap.starrocks.console.history_clear"))
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource("common_close"))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource("olap.starrocks.console.history_empty"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entries, key = { "${it.id}:${it.createdAt}" }) { entry ->
                    val ok = entry.status == OlapQueryRecord.STATUS_SUCCESS
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(entry.sqlText) }
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(8.dp),
                    ) {
                        // 头部：状态（成功绿 / 失败红）· 执行时间 · 耗时
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
                                "  ${formatTimestamp(entry.createdAt)}  ${entry.durationMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 内容：SQL 前两行，超出省略
                        val lines = entry.sqlText.lines()
                        val preview = if (lines.size > 2) lines.take(2).joinToString("\n") + "\n…" else entry.sqlText
                        Text(
                            preview,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    // ── 清空历史二次确认（不可逆操作） ──
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource("olap.starrocks.console.history_clear")) },
            text = { Text(stringResource("olap.starrocks.console.history_clear_confirm")) },
            confirmButton = {
                DangerButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { OlapQueryRepository.clear(connectionId) } }
                        entries = emptyList()
                        snackbar.showSnackbar(clearedText)
                    }
                }) { Text(stringResource("common_ok")) }
            },
            dismissButton = {
                SecondaryButton(onClick = { confirmClear = false }) { Text(stringResource("common_cancel")) }
            },
        )
    }
}
