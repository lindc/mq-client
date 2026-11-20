package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStore
import com.mqclient.graph.TopologyData
import com.mqclient.starrocks.cluster.ClusterSnapshot
import com.mqclient.starrocks.cluster.StarRocksClusterService
import com.mqclient.topology.TopologyCanvas
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch

/** 没有 FE 数据时的占位名：取 JDBC 连接的 host（连的就是某个 FE）。 */
private fun frontendName(connectionId: String): String =
    ConnectionStore.connections.value
        .firstOrNull { it.id == connectionId }
        ?.host
        ?.takeIf { it.isNotBlank() }
        ?: "FE"

/**
 * 「拓扑结构」面板：三列集群图 —— 外部 Catalog → FE（主 / 跟随 / 观察）→ BE / CN。
 *
 * 装配模式与 RocketMQ / Kafka 拓扑页一致：数据（[buildStarRocksTopology] 产出的
 * [TopologyData]）+ 布局（[starRocksClusterLayout]）+ [TopologyCanvas] 渲染。拖拽 /
 * 缩放 / 适应视图 / 滚动条全部由画布内置。
 *
 * 取数走 Service 层的 [StarRocksClusterService.loadSnapshot]（逐块降级：没权限看
 * BACKENDS 就只画 FE，不报错）。双击节点 → [StarRocksNodeDetailController] 打开
 * 属性对话框（快照秒开 + 后台刷新该节点）。
 */
@Composable
internal fun StarRocksTopologyPanel(connectionId: String, modifier: Modifier = Modifier) {
    // 状态按 connectionId 分键（宿主在同一个组合槽位里切换连接，见 OverviewPanel 同款说明）。
    var snapshot by remember(connectionId) { mutableStateOf<ClusterSnapshot?>(null) }
    var data by remember(connectionId) { mutableStateOf<TopologyData?>(null) }
    var loading by remember(connectionId) { mutableStateOf(false) }
    var error by remember(connectionId) { mutableStateOf<String?>(null) }
    val scope = rememberConnectionScope(connectionId)
    val nodeDetail = rememberStarRocksNodeDetailController(connectionId)

    suspend fun load() {
        loading = true
        error = null
        try {
            val graphResult = runPanelLoad(onError = { error = it.userMessage() }) {
                val fresh = StarRocksClusterService.loadSnapshot(connectionId)
                val graph = buildStarRocksTopology(fresh, fallbackFeName = frontendName(connectionId))
                // 只有占位 FE（两个查询都失败）时不画空图，交给空状态。
                fresh to graph.takeIf { fresh.nodes.isNotEmpty() }
            }
            snapshot = graphResult?.first
            data = graphResult?.second
        } finally {
            // 取消也要复位 spinner（同 OverviewPanel）。
            loading = false
        }
    }

    LaunchedEffect(connectionId) { load() }

    Column(modifier.fillMaxSize()) {
        // ── 工具栏：标题 + 节点数 + 刷新 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource("olap.starrocks.topology.title"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            data?.kuiver?.nodes?.size?.takeIf { it > 0 }?.let { count ->
                Text(
                    stringResource("olap.starrocks.topology.node_count").withArg(count),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            IconButton(onClick = { scope.launch { load() } }, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource("olap.starrocks.catalog.refresh"))
            }
        }

        // 降级提示（灰字一条）：普通用户看不到 BACKENDS / PROC 是常态，不在这里报错。
        snapshot?.degradations?.takeIf { it.isNotEmpty() }?.let { degradations ->
            Text(
                stringResource("olap.starrocks.topology.degraded")
                    .withArg(degradations.joinToString("、") { it.part.name }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        val graph = data
        when {
            loading -> EmptyTopology(
                text = stringResource("olap.starrocks.catalog.loading"),
                onRefresh = null,
            )

            error != null -> EmptyTopology(
                text = stringResource("olap.starrocks.topology.failed").withArg(error!!),
                onRefresh = { scope.launch { load() } },
            )

            graph == null || graph.kuiver.nodes.isEmpty() -> EmptyTopology(
                text = stringResource("olap.starrocks.topology.empty"),
                onRefresh = { scope.launch { load() } },
            )

            else -> {
                val nodes = snapshot?.nodes.orEmpty()
                TopologyCanvas(
                    data = graph,
                    style = StarRocksNodeStyle,
                    layoutProvider = starRocksClusterLayout,
                    // 节点拖拽（kuiver 原生手势，位置仅会话内有效）。
                    nodeDragEnabled = true,
                    onRescan = { scope.launch { load() } },
                    // 双击节点 → 属性对话框（先用快照秒开，后台再刷新该节点）。
                    onNodeDoubleClick = { nodeId ->
                        nodes.firstOrNull { it.nodeId == nodeId }?.let { nodeDetail.open(it, scope) }
                    },
                    // 节点 id 形如 `fe:fe-host` / `be:10.0.0.1:9050` / `catalog:hive0`：
                    // 取第一个冒号后的部分即显示名（与各节点 name 的口径一致）。
                    displayName = { nodeId -> nodeId.substringAfter(':', nodeId) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // 对话框宿主（独立窗口，落点不影响视觉）。
    nodeDetail.state?.let { state ->
        StarRocksNodeDetailDialog(state = state, onDismiss = nodeDetail::close)
    }
}

/** 空状态占位图：居中图标 + 文案 + 可选刷新按钮。 */
@Composable
private fun EmptyTopology(text: String, onRefresh: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Hub,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (onRefresh != null) {
                TextButton(onClick = onRefresh, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource("olap.starrocks.catalog.refresh"))
                }
            }
        }
    }
}
