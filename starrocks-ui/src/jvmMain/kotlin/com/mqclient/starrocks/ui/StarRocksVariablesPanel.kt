package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mqclient.starrocks.system.FeConfigEntry
import com.mqclient.starrocks.system.SessionVariable
import com.mqclient.starrocks.system.StarRocksSystemService
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.table.DataGrid
import com.mqclient.uicommon.table.DataGridTextCell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 变量与配置面板（对齐 FE Web 控制台的 `/variable` 页）。
 */
/* ============================== variables ============================== */

/**
 * 变量面板：会话变量 / 全局变量 / FE 配置三个 Tab。
 *
 * 对应 Web 的 `/variable` 页（「Variable Info」+「Configure Info」两段）。做成 Tab
 * 而不是三段平铺：286 个会话变量 + 数百个 FE 配置堆在一页，滚动条会失去意义。
 *
 * 搜索框在前端过滤（数据已在内存里，再发一次 `SHOW` 只会更慢）；过滤对三个 Tab 共用，
 * 切换 Tab 不用重输关键词。
 */
@Composable
internal fun StarRocksVariablesPanel(connectionId: String, modifier: Modifier = Modifier) {
    // 全部状态按 connectionId 分键：宿主在同一个组合槽位里切换连接
    // （MainWindow 的 tabContent() 没有 key），不带键的 remember 会把 A 连接的
    // 变量一直显示在 B 连接的页面上 —— 对运维工具来说这是"看着 B 的标题复制 A 的值"。
    var tab by remember(connectionId) { mutableStateOf(0) }
    var sessionVars by remember(connectionId) { mutableStateOf<List<SessionVariable>>(emptyList()) }
    var globalVars by remember(connectionId) { mutableStateOf<List<SessionVariable>>(emptyList()) }
    var feConfig by remember(connectionId) { mutableStateOf<List<FeConfigEntry>>(emptyList()) }
    // 显式"已加载"标记：不能用 isEmpty() 判断 —— 服务端合法返回 0 行时会每次重复拉取。
    var sessionLoaded by remember(connectionId) { mutableStateOf(false) }
    var globalLoaded by remember(connectionId) { mutableStateOf(false) }
    var configLoaded by remember(connectionId) { mutableStateOf(false) }
    var loading by remember(connectionId) { mutableStateOf(false) }
    var error by remember(connectionId) { mutableStateOf<String?>(null) }
    var search by remember(connectionId) { mutableStateOf("") }
    val scope = rememberConnectionScope(connectionId)

    suspend fun load(which: Int) {
        // 已有数据就不重复拉（刷新按钮走 force = true）。
        val alreadyLoaded = when (which) {
            0 -> sessionLoaded
            1 -> globalLoaded
            else -> configLoaded
        }
        loading = true
        error = null
        try {
            when (which) {
                0 -> if (!alreadyLoaded) {
                    sessionVars = withContext(Dispatchers.IO) {
                        StarRocksSystemService.loadVariables(connectionId, StarRocksSystemService.VariableScope.SESSION)
                    }
                    sessionLoaded = true
                }
                1 -> if (!alreadyLoaded) {
                    globalVars = withContext(Dispatchers.IO) {
                        StarRocksSystemService.loadVariables(connectionId, StarRocksSystemService.VariableScope.GLOBAL)
                    }
                    globalLoaded = true
                }
                else -> if (!alreadyLoaded) {
                    feConfig = withContext(Dispatchers.IO) { StarRocksSystemService.loadFeConfig(connectionId) }
                    configLoaded = true
                }
            }
        } catch (c: CancellationException) {
            // 离开页面时协程被取消：取消不是失败，不能把面板打成错误态。
            throw c
        } catch (e: Throwable) {
            // 全局变量与 FE 配置都需要权限：这一格失败只影响本 Tab，不清空其它数据。
            // 只有「用户还停在这个 Tab」时才把错误显示出来 —— 否则一次慢请求的失败
            // 会在用户已经切走的页面上弹红字，看起来像新的 Tab 挂了。
            if (which == tab) error = e.userMessage()
            // 失败时清掉标记：刷新按钮（或下次切回该 Tab）必须能重试。
            when (which) {
                0 -> { sessionVars = emptyList(); sessionLoaded = false }
                1 -> { globalVars = emptyList(); globalLoaded = false }
                else -> { feConfig = emptyList(); configLoaded = false }
            }
        } finally {
            // 必须在 finally 里复位：取消（离开页面 / 切 Tab）也要关掉 spinner，
            // 否则界面上会留下一个永远转的圈。`which == tab` 的判断保留 ——
            // 非当前 Tab 的请求结束不该关掉当前 Tab 的 spinner。
            if (which == tab) loading = false
        }
    }

    LaunchedEffect(connectionId) { load(tab) }

    val query = search.trim()
    val sessionRows = remember(sessionVars, query) {
        if (query.isEmpty()) sessionVars
        else sessionVars.filter { it.name.contains(query, ignoreCase = true) }
    }
    val globalRows = remember(globalVars, query) {
        if (query.isEmpty()) globalVars
        else globalVars.filter { it.name.contains(query, ignoreCase = true) }
    }
    val configRows = remember(feConfig, query) {
        if (query.isEmpty()) feConfig
        else feConfig.filter {
            it.key.contains(query, ignoreCase = true) || it.comment.contains(query, ignoreCase = true)
        }
    }

    SystemPanelScaffold(
        title = stringResource("olap.starrocks.variables.title"),
        subtitle = stringResource("olap.starrocks.variables.subtitle"),
        loading = loading,
        error = error,
        degraded = null,
        onRefresh = {
            // 刷新 = 重新取数：先把当前 Tab 的标记清掉，否则 load() 会判定"已加载"直接返回。
            when (tab) {
                0 -> sessionLoaded = false
                1 -> globalLoaded = false
                else -> configLoaded = false
            }
            scope.launch { load(tab) }
        },
        modifier = modifier,
    ) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0; scope.launch { load(0) } },
                text = { Text(stringResource("olap.starrocks.variables.tab_session")) })
            Tab(selected = tab == 1, onClick = { tab = 1; scope.launch { load(1) } },
                text = { Text(stringResource("olap.starrocks.variables.tab_global")) })
            Tab(selected = tab == 2, onClick = { tab = 2; scope.launch { load(2) } },
                text = { Text(stringResource("olap.starrocks.variables.tab_fe_config")) })
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            placeholder = {
                Text(
                    stringResource("olap.starrocks.variables.search_placeholder"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        when (tab) {
            0 -> VariableGrid(sessionRows)
            1 -> VariableGrid(globalRows)
            else -> FeConfigGrid(configRows)
        }
    }
}

/** 变量两列（名 / 值）。值可能很长，靠省略号 + 行点击复制。 */
@Composable
private fun ColumnScope.VariableGrid(rows: List<SessionVariable>) {
    if (rows.isEmpty()) {
        SystemEmptyHint(stringResource("olap.starrocks.variables.empty"))
        return
    }
    val clipboard = LocalClipboardManager.current
    DataGrid(
        columns = listOf(
            gridCol(stringResource("olap.starrocks.variables.col_name"), 1f) { v ->
                DataGridTextCell(v.name, emphasized = true)
            },
            gridCol(stringResource("olap.starrocks.variables.col_value"), 2f) { v ->
                DataGridTextCell(v.value.ifBlank { "-" }, subdued = v.value.isBlank())
            },
        ),
        rows = rows,
        rowKey = { it.name },
        onRowClick = { clipboard.setText(AnnotatedString(it.value)) },
        modifier = Modifier.weight(1f).fillMaxWidth(),
    )
}

/** FE 配置五列（键 / 值 / 类型 / 是否可在线改 / 注释）。 */
@Composable
private fun ColumnScope.FeConfigGrid(rows: List<FeConfigEntry>) {
    if (rows.isEmpty()) {
        SystemEmptyHint(stringResource("olap.starrocks.variables.empty"))
        return
    }
    DataGrid(
        columns = listOf(
            gridCol(stringResource("olap.starrocks.variables.col_name"), 1.4f) { c ->
                DataGridTextCell(c.key, emphasized = true)
            },
            gridCol(stringResource("olap.starrocks.variables.col_value"), 1.4f) { c ->
                DataGridTextCell(c.value.ifBlank { "-" }, subdued = c.value.isBlank())
            },
            gridCol(stringResource("olap.starrocks.variables.col_type"), 0.7f) { c ->
                DataGridTextCell(c.type.ifBlank { "-" }, subdued = true)
            },
            gridCol(stringResource("olap.starrocks.variables.col_mutable"), 0.8f) { c ->
                // 三态：true / false / 未知（缺列）—— 未知不能显示成 false。
                DataGridTextCell(
                    when (c.isMutable) {
                        true -> stringResource("olap.starrocks.variables.mutable_yes")
                        false -> stringResource("olap.starrocks.variables.mutable_no")
                        null -> "-"
                    },
                    subdued = c.isMutable != true,
                )
            },
            gridCol(stringResource("olap.starrocks.variables.col_comment"), 2f) { c ->
                DataGridTextCell(c.comment.ifBlank { "-" }, subdued = c.comment.isBlank())
            },
        ),
        rows = rows,
        rowKey = { it.key },
        modifier = Modifier.weight(1f).fillMaxWidth(),
    )
}
