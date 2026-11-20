package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mqclient.core.log.LogLevel
import com.mqclient.starrocks.log.StarRocksLog
import com.mqclient.starrocks.system.FeLogAuthException
import com.mqclient.starrocks.system.FeLogFile
import com.mqclient.starrocks.system.FeLogUnavailableException
import com.mqclient.starrocks.system.StarRocksSystemService
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FE 运行日志面板（对齐 FE Web 控制台的 `/log` 页）。
 */
/* ============================== logs ============================== */

/** 日志自动刷新间隔（日志是"看最新发生了什么"，10 秒一次足够且不至于刷屏）。 */
private const val LOG_AUTO_REFRESH_MS = 10_000L

/**
 * FE 日志面板：`fe.log` / `fe.warn.log` / `fe.audit.log` 尾部若干行。
 *
 * 数据来自 FE Web 的 `/log?file=…`（Basic Auth）——这是 3.4.0 里**唯一**能拿到
 * FE 日志内容的通道（SQL 的 `SHOW PROC '/log'` 不存在）。端口由
 * `SHOW FRONTENDS.HttpPort` 发现，见 [StarRocksSystemService.loadFeLog]。
 *
 * 显示等宽 + 可选中 + 一键复制：排障时的第一动作是把一段栈贴进 issue。
 */
@Composable
internal fun StarRocksLogsPanel(connectionId: String, modifier: Modifier = Modifier) {
    // 状态按 connectionId + file 分键：切连接不能残留上一条连接的日志；
    // 切文件必须立刻清空旧正文，否则新版面会顶着 fe.audit.log 的标题显示 fe.warn.log
    // 的内容（重试窗口内最长可达数十秒，足以让人读错日志）。
    var file by remember(connectionId) { mutableStateOf(FeLogFile.WARN) }
    var text by remember(connectionId, file) { mutableStateOf<String?>(null) }
    var port by remember(connectionId, file) { mutableStateOf<Int?>(null) }
    var loading by remember(connectionId, file) { mutableStateOf(false) }
    var error by remember(connectionId) { mutableStateOf<String?>(null) }
    var autoRefresh by remember(connectionId) { mutableStateOf(false) }
    val scope = rememberConnectionScope(connectionId)
    val clipboard = LocalClipboardManager.current

    suspend fun load() {
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                StarRocksSystemService.loadFeLog(connectionId, file)
            }
            text = result.text
            port = result.port
        } catch (auth: FeLogAuthException) {
            // 401 与"连不上"是两种修法：前者要改连接凭据，后者要检查 8030 是否可达。
            error = stringResource("olap.starrocks.logs.auth_failed")
            StarRocksLog.profile(LogLevel.WARN, "fe log auth failed: ${auth.message}")
            text = null
        } catch (unavailable: FeLogUnavailableException) {
            error = stringResource("olap.starrocks.logs.unavailable")
                .withArg(unavailable.triedPorts.joinToString(", ").ifBlank { "-" })
            StarRocksLog.profile(LogLevel.WARN, "fe log unavailable: ${unavailable.message}")
            text = null
        } catch (c: CancellationException) {
            // 切换文件 / 离开页面：旧请求被取消是正常路径（LaunchedEffect 会重新发起）。
            throw c
        } catch (e: Throwable) {
            error = e.userMessage()
            text = null
        } finally {
            // 取消（切文件 / 关自动刷新）也要复位 spinner —— 否则自动刷新关掉后
            // 界面上会留一个永远转的圈。状态按 (connectionId, file) 分键，写旧状态无害。
            loading = false
        }
    }

    LaunchedEffect(connectionId, file) { load() }

    LaunchedEffect(autoRefresh, connectionId, file) {
        if (!autoRefresh) return@LaunchedEffect
        while (true) {
            delay(LOG_AUTO_REFRESH_MS)
            load()
        }
    }

    SystemPanelScaffold(
        title = stringResource("olap.starrocks.logs.title"),
        subtitle = stringResource("olap.starrocks.logs.subtitle"),
        // 自动刷新期间**不**清屏：10 秒一次的全屏 spinner 会让人没法读日志。
        // 只有「还没有任何内容」时才占位 loading（首次加载 / 切换文件后的空窗）。
        loading = loading && text == null,
        error = error,
        degraded = null,
        onRefresh = { scope.launch { load() } },
        modifier = modifier,
        extraActions = {
            TextButton(onClick = { autoRefresh = !autoRefresh }) {
                Text(
                    stringResource(
                        if (autoRefresh) "olap.starrocks.logs.autorefresh_on"
                        else "olap.starrocks.logs.autorefresh_off",
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(
                onClick = { text?.let { clipboard.setText(AnnotatedString(it)) } },
                enabled = !text.isNullOrBlank(),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource("olap.starrocks.logs.copy"))
            }
        },
    ) {
        // 文件切换 + 实际生效的端口（排障时先看端口对不对）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            FeLogFile.entries.forEach { candidate ->
                val selected = candidate == file
                TextButton(onClick = { file = candidate }) {
                    Text(
                        candidate.fileName,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                port?.let { stringResource("olap.starrocks.logs.port").withArg(it) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        val body = text
        when {
            body == null && !loading -> SystemEmptyHint(stringResource("olap.starrocks.logs.empty"))
            body.isNullOrBlank() -> SystemEmptyHint(stringResource("olap.starrocks.logs.empty"))
            else -> SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                // 滚动容器包在 SelectionContainer 外层：先能滚，再能选 —— 反过来在大文件上
                // 会出现"滚动条拖不动、选中却一直往下"的手感问题。
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}
