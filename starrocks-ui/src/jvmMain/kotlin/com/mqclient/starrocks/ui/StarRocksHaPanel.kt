package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.metric.MetricCard
import com.mqclient.starrocks.cluster.ClusterNodeInfo
import com.mqclient.starrocks.cluster.StarRocksClusterService
import com.mqclient.starrocks.system.haSummary
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.table.DataGrid
import com.mqclient.uicommon.table.DataGridColumn
import com.mqclient.uicommon.table.DataGridTextCell
import kotlinx.coroutines.launch

/**
 * 高可用面板（对齐 FE Web 控制台的 `/ha` 页）。
 */
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
