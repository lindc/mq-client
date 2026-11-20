package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.core.log.LogLevel
import com.mqclient.mqui.metric.MetricCard
import com.mqclient.starrocks.cluster.ClusterNodeInfo
import com.mqclient.starrocks.cluster.ClusterNodeRole
import com.mqclient.starrocks.cluster.StarRocksClusterService
import com.mqclient.starrocks.log.StarRocksLog
import com.mqclient.starrocks.system.FeLogAuthException
import com.mqclient.starrocks.system.FeLogFile
import com.mqclient.starrocks.system.FeLogUnavailableException
import com.mqclient.starrocks.system.FeConfigEntry
import com.mqclient.starrocks.system.SessionVariable
import com.mqclient.starrocks.system.StarRocksSystemService
import com.mqclient.starrocks.system.haSummary
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.table.DataGrid
import com.mqclient.uicommon.table.DataGridColumn
import com.mqclient.uicommon.table.DataGridTextCell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 对齐 FE Web 控制台（`:8030`）的四个系统页：**backends / ha / variables / logs**。
 *
 * ## 为什么是这四个、为什么这样取数
 *
 * Web 控制台的侧栏就是 system / backends / logs / queries / sessions / variables / ha。
 * 客户端已经有 queries（会话与慢查询），这四个补上剩余的系统视角。取数分两条路：
 *
 *  - **backends / ha / variables 走 SQL**（`SHOW BACKENDS` / `SHOW FRONTENDS` /
 *    `SHOW [GLOBAL] VARIABLES` / `ADMIN SHOW FRONTEND CONFIG`）—— 它们与 Web 页
 *    同源，且复用已认证的 MySQL 会话：不需要额外端口，普通部署只暴露 9030 也能用；
 *  - **logs 只能走 Web 端口**：`SHOW PROC '/log'` 在 3.4.0 不存在，FE 日志文件仅经
 *    `GET /log?file=…`（Basic Auth）暴露，端口由 `SHOW FRONTENDS` 的 HttpPort 发现。
 *
 * ## 复用关系
 *
 * backends / ha 复用 [StarRocksClusterService.loadSnapshot] 与
 * [StarRocksNodeDetailController]（与概览、拓扑是**同一份** `SHOW FRONTENDS` /
 * `SHOW BACKENDS` 结果与同一个属性对话框），不另写一套节点模型 —— 否则同一个
 * 集群在两个页面里显示出不同的角色，就是最典型的"两套解析各错一半"。
 */

/* ============================== backends ============================== */

/**
 * BE / CN 节点面板：容量、负载、分片数与心跳一屏看完。
 *
 * 与「集群概览」的分工：概览回答"集群整体健康吗"，这里回答"**哪一台**有问题"。
 * 因此每台节点都摊开成一行，行点击打开与拓扑双击同一个属性对话框。
 *
 * CN（无状态计算节点）与 BE 合表展示：两者字段几乎一致，分开列会让用户在
 * 两个页面之间反复横跳；角色列区分即可（CN 没有本地盘，容量列显示 `-`）。
 */
@Composable
internal fun StarRocksBackendsPanel(connectionId: String, modifier: Modifier = Modifier) {
    // 按 connectionId 分键：切连接后不能残留上一条连接的 BE 列表。
    var nodes by remember(connectionId) { mutableStateOf<List<ClusterNodeInfo>>(emptyList()) }
    var loading by remember(connectionId) { mutableStateOf(false) }
    var error by remember(connectionId) { mutableStateOf<String?>(null) }
    var degraded by remember(connectionId) { mutableStateOf<String?>(null) }
    val scope = rememberConnectionScope(connectionId)
    val nodeDetail = rememberStarRocksNodeDetailController(connectionId)

    suspend fun load() {
        loading = true
        error = null
        try {
            runPanelLoad(onError = { e -> error = e.userMessage(); nodes = emptyList() }) {
                val snapshot = StarRocksClusterService.loadSnapshot(connectionId)
                nodes = snapshot.nodes.filter { it.role.isCompute }
                // BE 与 CN 是两块独立查询：一块失败时另一块照常显示，提示里说清缺的是哪块。
                degraded = snapshot.degradations
                    .filter { it.part.name in setOf("BACKENDS", "COMPUTE_NODES", "NODE_MEMORY") }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("、") { it.part.name }
            }
        } finally {
            // 取消（切连接 / 离开页面）也要复位 spinner，否则留下一个永远转的圈。
            loading = false
        }
    }

    LaunchedEffect(connectionId) { load() }

    SystemPanelScaffold(
        title = stringResource("olap.starrocks.backends.title"),
        subtitle = stringResource("olap.starrocks.backends.subtitle"),
        loading = loading,
        error = error,
        degraded = degraded,
        onRefresh = { scope.launch { load() } },
        modifier = modifier,
    ) {
        if (nodes.isEmpty()) {
            SystemEmptyHint(stringResource("olap.starrocks.backends.empty"))
        } else {
            DataGrid(
                columns = backendColumns(),
                rows = nodes,
                rowKey = { it.nodeId },
                onRowClick = { nodeDetail.open(it, scope) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }

    nodeDetail.state?.let { state ->
        StarRocksNodeDetailDialog(state = state, onDismiss = nodeDetail::close)
    }
}

/**
 * BE / CN 的列定义。
 *
 * 列数是**克制**的：12 列挤在 60% 宽的面板里，[版本] 与 [最后心跳] 会双双被截成
 * "3.4.0-e945..." / "2026-11-20 1..."，等于白占两列。因此把「已用 / 总量 / 百分比」
 * 合成一格存储列，版本与错误信息留在行点击打开的属性对话框里（那里还有完整端口表）。
 * 宽度不够时牺牲的是省略号，不是信息 —— 信息在对话框里都还在。
 */
@Composable
private fun backendColumns(): List<DataGridColumn<ClusterNodeInfo>> = listOf(
    gridCol(stringResource("olap.starrocks.backends.col_node"), 1.6f) { node ->
        DataGridTextCell(node.name, emphasized = true)
    },
    gridCol(stringResource("olap.starrocks.backends.col_role"), 0.6f) { node ->
        DataGridTextCell(node.role.roleLabel())
    },
    gridCol(stringResource("olap.starrocks.backends.col_alive"), 0.5f) { node ->
        AliveCell(node.alive)
    },
    gridCol(stringResource("olap.starrocks.backends.col_tablets"), 0.6f) { node ->
        DataGridTextCell(node.tabletCount?.toString() ?: "-", subdued = node.tabletCount == null)
    },
    gridCol(stringResource("olap.starrocks.backends.col_storage"), 1.7f) { node ->
        StorageCell(node)
    },
    gridCol(stringResource("olap.starrocks.backends.col_cpu"), 1f) { node ->
        // 核心数与使用率同格：分开两列各占半屏却都只装三位数。
        DataGridTextCell(
            listOfNotNull(
                node.cpuCores?.let { "$it " + stringResource("olap.starrocks.backends.cores_suffix") },
                node.cpuUsedPercent?.let(::formatPercent),
            ).joinToString(" · ").ifBlank { "-" },
            subdued = node.cpuCores == null && node.cpuUsedPercent == null,
        )
    },
    gridCol(stringResource("olap.starrocks.backends.col_mem"), 0.7f) { node ->
        DataGridTextCell(
            node.memUsedPercent?.let(::formatPercent) ?: "-",
            subdued = node.memUsedPercent == null,
        )
    },
    gridCol(stringResource("olap.starrocks.backends.col_queries"), 0.6f) { node ->
        DataGridTextCell(node.runningQueries?.toString() ?: "-", subdued = node.runningQueries == null)
    },
    gridCol(stringResource("olap.starrocks.backends.col_heartbeat"), 1.3f) { node ->
        DataGridTextCell(shortTimestamp(node.lastHeartbeat), subdued = node.lastHeartbeat == null)
    },
)

/** 存储一格：`已用 / 总量 · 百分比`；缺容量时逐段退让，不留假 0。 */
@Composable
private fun StorageCell(node: ClusterNodeInfo) {
    val used = node.diskUsedBytes
    val total = node.diskTotalBytes
    val pct = node.diskUsedPercent
    val text = when {
        used != null && total != null && pct != null -> "${formatBytes(used)} / ${formatBytes(total)} · ${formatPercent(pct)}"
        used != null && total != null -> "${formatBytes(used)} / ${formatBytes(total)}"
        total != null -> formatBytes(total)
        pct != null -> formatPercent(pct)
        else -> "-"
    }
    DataGridTextCell(text, subdued = used == null && total == null && pct == null)
}

/**
 * 时间戳只留 `HH:mm:ss`：表头已写「最后心跳」，日期部分每天都是同一个，
 * 完整时间点行点击后的属性对话框里有。
 */
private fun shortTimestamp(raw: String?): String {
    val text = raw?.trim().orEmpty()
    return when {
        text.isEmpty() -> "-"
        // `2026-11-20 10:37:19` → `10:37:19`
        text.length >= 19 && text[10] == ' ' -> text.substring(11)
        else -> text
    }
}

/* ============================== ha ============================== */

/**
 * 高可用面板：谁在当主、选主是否还有多数派、观察者几台。
 *
 * 三张指标卡 + FE 明细表。quorum 判定来自 BDB JE 的多数派规则
 * （`haSummary` 里算），不是自创阈值 —— 与 Web 的 `/ha` 页「Frontend Role /
 * Electable nodes / Observer nodes」表达的是同一件事。
 */
@Composable
internal fun StarRocksHaPanel(connectionId: String, modifier: Modifier = Modifier) {
    // 同上（BE 面板）：FE 列表与降级提示都必须随连接切换而重置。
    var frontends by remember(connectionId) { mutableStateOf<List<ClusterNodeInfo>>(emptyList()) }
    var loading by remember(connectionId) { mutableStateOf(false) }
    var error by remember(connectionId) { mutableStateOf<String?>(null) }
    var degraded by remember(connectionId) { mutableStateOf<String?>(null) }
    val scope = rememberConnectionScope(connectionId)
    val nodeDetail = rememberStarRocksNodeDetailController(connectionId)

    suspend fun load() {
        loading = true
        error = null
        try {
            runPanelLoad(onError = { e -> error = e.userMessage(); frontends = emptyList() }) {
                val snapshot = StarRocksClusterService.loadSnapshot(connectionId)
                frontends = snapshot.nodes.filter { it.role.isFrontend }
                degraded = snapshot.degradations
                    .firstOrNull { it.part.name == "FRONTENDS" }
                    ?.message
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(connectionId) { load() }

    val summary = remember(frontends) { haSummary(frontends) }

    SystemPanelScaffold(
        title = stringResource("olap.starrocks.ha.title"),
        subtitle = stringResource("olap.starrocks.ha.subtitle"),
        loading = loading,
        error = error,
        degraded = degraded,
        onRefresh = { scope.launch { load() } },
        modifier = modifier,
    ) {
        // ── 选主状态 ──
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                icon = Icons.Filled.Security,
                label = stringResource("olap.starrocks.ha.leader"),
                value = summary.leaderName ?: stringResource("olap.starrocks.ha.no_leader"),
                valueColor = if (summary.leaderName != null) {
                    aliveSemanticColor()
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            MetricCard(
                icon = Icons.Filled.CheckCircle,
                label = stringResource("olap.starrocks.ha.quorum"),
                value = summary.electableAlive.toString() + "/" + summary.electableTotal,
                footer = stringResource(
                    if (summary.quorumHealthy) "olap.starrocks.ha.quorum_ok"
                    else "olap.starrocks.ha.quorum_lost",
                ),
                valueColor = if (summary.quorumHealthy) {
                    aliveSemanticColor()
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            MetricCard(
                icon = Icons.Filled.Storage,
                label = stringResource("olap.starrocks.ha.tolerance"),
                value = summary.toleratedFailures.toString(),
                // 多数派已经丢了的时候，「再挂 N 台就会丢多数派」是假话 —— 换成如实说明。
                footer = stringResource(
                    if (summary.quorumHealthy) "olap.starrocks.ha.tolerance_footer"
                    else "olap.starrocks.ha.tolerance_gone",
                ),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            MetricCard(
                icon = Icons.Filled.Dns,
                label = stringResource("olap.starrocks.ha.observers"),
                value = summary.observerTotal.toString(),
                footer = stringResource("olap.starrocks.ha.observers_footer"),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        if (frontends.isEmpty()) {
            SystemEmptyHint(stringResource("olap.starrocks.ha.empty"))
        } else {
            DataGrid(
                columns = haColumns(),
                rows = frontends,
                rowKey = { it.nodeId },
                onRowClick = { nodeDetail.open(it, scope) },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
            )
        }
    }

    nodeDetail.state?.let { state ->
        StarRocksNodeDetailDialog(state = state, onDismiss = nodeDetail::close)
    }
}

/**
 * FE 明细列：角色 / 存活 / 是否已加入 / 选举日志 / 端口 / 心跳。
 *
 * 「Replayed Journal」是判断 follower 是否跟得上的关键（落后太多就不该被选为主），
 * Web 的 `/ha` 页也把它摆在最前面。
 */
@Composable
private fun haColumns(): List<DataGridColumn<ClusterNodeInfo>> = listOf(
    gridCol(stringResource("olap.starrocks.ha.col_node"), 1.8f) { node ->
        DataGridTextCell(node.name, emphasized = true)
    },
    gridCol(stringResource("olap.starrocks.ha.col_role"), 0.8f) { node ->
        DataGridTextCell(node.role.roleLabel())
    },
    gridCol(stringResource("olap.starrocks.ha.col_alive"), 0.6f) { node ->
        AliveCell(node.alive)
    },
    gridCol(stringResource("olap.starrocks.ha.col_join"), 0.6f) { node ->
        DataGridTextCell(node.extra["join"] ?: "-", subdued = node.extra["join"] == null)
    },
    gridCol(stringResource("olap.starrocks.ha.col_helper"), 0.6f) { node ->
        DataGridTextCell(node.extra["is_helper"] ?: "-", subdued = node.extra["is_helper"] == null)
    },
    gridCol(stringResource("olap.starrocks.ha.col_journal"), 0.9f) { node ->
        DataGridTextCell(node.extra["replayed_journal_id"] ?: "-", subdued = node.extra["replayed_journal_id"] == null)
    },
    gridCol(stringResource("olap.starrocks.ha.col_ports"), 1.6f) { node ->
        DataGridTextCell(
            node.ports.joinToString(" / ") { "${it.label.removeSuffix("_port")}:${it.port}" }.ifBlank { "-" },
            subdued = true,
        )
    },
    gridCol(stringResource("olap.starrocks.ha.col_heartbeat"), 1.2f) { node ->
        DataGridTextCell(node.lastHeartbeat ?: "-", subdued = node.lastHeartbeat == null)
    },
)

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
        }
        // 同理：非当前 Tab 的请求结束不该关掉当前 Tab 的 spinner。
        if (which == tab) loading = false
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
        }
        loading = false
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

/* ============================== 共用零件 ============================== */

/**
 * 四个系统页共用的外壳：标题 / 副标题 / 刷新 / 降级提示 / 加载与错误态。
 *
 * 抽出来的理由与 [DataGrid] 相同：四张页面的工具栏逐字相同，复制四遍之后
 * 「刷新按钮在有些页面是图标、有些是文字」这类漂移一定会发生。
 */
@Composable
private fun SystemPanelScaffold(
    title: String,
    subtitle: String,
    loading: Boolean,
    error: String?,
    degraded: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    extraActions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            extraActions()
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource("olap.starrocks.catalog.refresh"))
            }
        }

        // 降级提示：灰字一条（与概览同口径），不是红框 —— 部分数据缺失不该看起来像故障。
        degraded?.let {
            Text(
                stringResource("olap.starrocks.system.degraded").withArg(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        when {
            loading && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource("olap.starrocks.catalog.error_prefix") + error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            else -> content()
        }
    }
}

/** 空态 / 提示行（与各面板的次要文字同色）。 */
@Composable
private fun ColumnScope.SystemEmptyHint(text: String) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 存活列：绿色 / 红色（语义色集中定义在 [aliveSemanticColor]）。 */
@Composable
private fun AliveCell(alive: Boolean) {
    Text(
        text = stringResource(
            if (alive) "olap.starrocks.overview.alive" else "olap.starrocks.overview.dead",
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = if (alive) aliveSemanticColor() else MaterialTheme.colorScheme.error,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 列定义简写：省掉每处重复的泛型实参（与数据目录的 `gridColumn` 同款）。 */
private fun <T> gridCol(
    title: String,
    weight: Float,
    cell: @Composable (T) -> Unit,
): DataGridColumn<T> = DataGridColumn(title = title, weight = weight, cell = cell)

/**
 * 角色短标签（表格里用）。
 *
 * 不复用 `olap.starrocks.node.role_*`（那是属性对话框的全称，如「BE 存储计算节点」）：
 * 在 60% 宽的面板里全称会被截成「BE 存...」，不如直接给 `BE` / `LEADER` ——
 * 这也是用户在看 `SHOW BACKENDS` 时熟悉的写法。
 */
@Composable
private fun ClusterNodeRole.roleLabel(): String = when (this) {
    ClusterNodeRole.FE_LEADER -> stringResource("olap.starrocks.system.role_leader_short")
    ClusterNodeRole.FE_FOLLOWER -> stringResource("olap.starrocks.system.role_follower_short")
    ClusterNodeRole.FE_OBSERVER -> stringResource("olap.starrocks.system.role_observer_short")
    ClusterNodeRole.BE -> stringResource("olap.starrocks.system.role_be_short")
    ClusterNodeRole.CN -> stringResource("olap.starrocks.system.role_cn_short")
    ClusterNodeRole.CATALOG -> stringResource("olap.starrocks.system.role_catalog_short")
}
