package com.mqclient.starrocks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.metric.MetricCard
import com.mqclient.starrocks.cluster.ClusterNodeRole
import com.mqclient.starrocks.cluster.ClusterSnapshot
import com.mqclient.starrocks.cluster.StarRocksClusterService
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch

/**
 * 「集群概览」只读面板。
 *
 * 数据来自 Service 层的 [StarRocksClusterService.loadSnapshot]（与拓扑页同一份快照：
 * FE / BE / CN / Catalog / Routine Load 一次拉完，逐块降级）。指标分两行：
 *
 *  1. 集群规模：节点总数 / 存活节点数 / 版本号；
 *  2. 集群资源：内存（优先「已用 / 总量」，拿不到 PROC 时退化成平均使用率）、
 *     存储（存活 BE 的容量求和）、运行中的导入任务数（没有 Routine Load 就不出卡）。
 *
 * 节点表每一行可点：打开与拓扑双击同一个属性对话框。
 * 取数失败（如普通用户看不到 BACKENDS）走**降级提示**而不是红框 —— 只有所有查询
 * 都失败、连 FE 都拿不到时才落到错误态。
 */
@Composable
internal fun StarRocksOverviewPanel(connectionId: String, modifier: Modifier = Modifier) {
    var snapshot by remember { mutableStateOf<ClusterSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val nodeDetail = rememberStarRocksNodeDetailController(connectionId)

    suspend fun load() {
        loading = true
        error = null
        try {
            snapshot = StarRocksClusterService.loadSnapshot(connectionId)
        } catch (e: Throwable) {
            error = e.userMessage()
            snapshot = null
        } finally {
            loading = false
        }
    }

    LaunchedEffect(connectionId) { load() }

    val nodes = snapshot?.nodes.orEmpty()
    val aliveCount = snapshot?.aliveCount ?: 0
    val version = snapshot?.version ?: "-"

    Column(modifier.fillMaxSize().padding(12.dp)) {
        // ── 工具栏 ──
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource("olap.starrocks.overview.title"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { scope.launch { load() } }, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource("olap.starrocks.catalog.refresh"))
            }
        }

        // ── 指标卡（第一行：集群规模）──
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                icon = Icons.Filled.Dns,
                label = stringResource("olap.starrocks.overview.total_nodes"),
                value = nodes.size.toString(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            MetricCard(
                icon = Icons.Filled.CheckCircle,
                label = stringResource("olap.starrocks.overview.alive_nodes"),
                value = aliveCount.toString(),
                valueColor = if (nodes.isNotEmpty() && aliveCount == nodes.size) {
                    aliveSemanticColor()
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            MetricCard(
                icon = Icons.Filled.Info,
                label = stringResource("olap.starrocks.overview.version"),
                value = version,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        // ── 指标卡（第二行：集群资源 + 导入任务）──
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 内存：优先「已用 / 总量」（SHOW PROC '/backends' 才给总量）；拿不到就
            // 退化成平均使用率，并在 footer 说明总量为什么没有 —— 不拿 0 假装有数据。
            val memUsed = snapshot?.memUsedBytes
            val memLimit = snapshot?.memLimitBytes
            if (memUsed != null && memLimit != null && memLimit > 0) {
                MetricCard(
                    icon = Icons.Filled.Memory,
                    label = stringResource("olap.starrocks.overview.cluster_memory"),
                    value = percentOf(memUsed, memLimit),
                    footer = "${formatBytes(memUsed)} / ${formatBytes(memLimit)}",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            } else {
                snapshot?.memUsedPercentAvg?.let { average ->
                    MetricCard(
                        icon = Icons.Filled.Memory,
                        label = stringResource("olap.starrocks.overview.mem_used_avg"),
                        value = formatPercent(average),
                        footer = stringResource("olap.starrocks.overview.mem_total_unavailable"),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }

            // 存储：存活 BE 的容量求和（Service 层已把「1.234 GB」解析成字节）。
            val diskTotal = snapshot?.diskTotalBytes
            val diskUsed = snapshot?.diskUsedBytes
            if (diskTotal != null && diskUsed != null && diskTotal > 0) {
                MetricCard(
                    icon = Icons.Filled.Storage,
                    label = stringResource("olap.starrocks.overview.cluster_storage"),
                    value = percentOf(diskUsed, diskTotal),
                    footer = "${formatBytes(diskUsed)} / ${formatBytes(diskTotal)}",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }

            // 导入任务：没有 Routine Load 就不出卡（空集群上恒 0 不是"健康"指标）。
            val loads = snapshot?.routineLoads.orEmpty()
            if (loads.isNotEmpty()) {
                MetricCard(
                    icon = Icons.Filled.Sync,
                    label = stringResource("olap.starrocks.overview.routine_loads"),
                    value = loads.count { it.isRunning }.toString(),
                    footer = stringResource("olap.starrocks.overview.routine_loads_footer")
                        .withArg(loads.size),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        // ── 降级提示：灰字一条（不是红框）──
        snapshot?.degradations?.takeIf { it.isNotEmpty() }?.let { degradations ->
            Text(
                stringResource("olap.starrocks.overview.degraded")
                    .withArg(degradations.joinToString("、") { it.part.name }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ── 节点表 / 空状态 ──
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(
                        stringResource("olap.starrocks.catalog.loading"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource("olap.starrocks.catalog.error_prefix") + error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            nodes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource("olap.starrocks.overview.empty"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(Modifier.fillMaxSize().padding(top = 12.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        OverviewHeaderCell(
                            stringResource("olap.starrocks.overview.col_role"),
                            Modifier.weight(1f),
                        )
                        OverviewHeaderCell(
                            stringResource("olap.starrocks.overview.col_node"),
                            Modifier.weight(1.4f),
                        )
                        OverviewHeaderCell(
                            stringResource("olap.starrocks.overview.col_port"),
                            Modifier.weight(1f),
                        )
                        OverviewHeaderCell(
                            stringResource("olap.starrocks.overview.col_alive"),
                            Modifier.weight(0.7f),
                        )
                    }
                }
                items(nodes, key = { it.nodeId }) { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { nodeDetail.open(node, scope) }
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            node.role.rowLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            node.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1.4f),
                        )
                        Text(
                            node.ports.firstOrNull()?.port ?: "-",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(
                                if (node.alive) "olap.starrocks.overview.alive"
                                else "olap.starrocks.overview.dead",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (node.alive) aliveSemanticColor() else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(0.7f),
                        )
                    }
                }
            }
        }
    }

    // 对话框宿主：与拓扑页共用同一个对话框（行点击与节点双击同一入口）。
    nodeDetail.state?.let { state ->
        StarRocksNodeDetailDialog(state = state, onDismiss = nodeDetail::close)
    }
}

@Composable
private fun OverviewHeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier,
    )
}

/** 表格行里的角色短标签（比对话框里的全称短，给表格留宽度）。 */
@Composable
private fun ClusterNodeRole.rowLabel(): String = when (this) {
    ClusterNodeRole.FE_LEADER -> stringResource("olap.starrocks.node.role_leader")
    ClusterNodeRole.FE_FOLLOWER -> stringResource("olap.starrocks.node.role_follower")
    ClusterNodeRole.FE_OBSERVER -> stringResource("olap.starrocks.node.role_observer")
    ClusterNodeRole.BE -> stringResource("olap.starrocks.node.role_be")
    ClusterNodeRole.CN -> stringResource("olap.starrocks.node.role_cn")
    ClusterNodeRole.CATALOG -> stringResource("olap.starrocks.node.role_catalog")
}
