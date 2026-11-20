package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mqclient.starrocks.cluster.ClusterNodeInfo
import com.mqclient.starrocks.cluster.StarRocksClusterService
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.table.DataGrid
import com.mqclient.uicommon.table.DataGridColumn
import com.mqclient.uicommon.table.DataGridTextCell
import kotlinx.coroutines.launch

/**
 * BE / CN 节点面板（对齐 FE Web 控制台的 `/backends` 页）。
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
