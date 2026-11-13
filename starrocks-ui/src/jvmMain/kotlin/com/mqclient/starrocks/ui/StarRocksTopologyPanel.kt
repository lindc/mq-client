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
import com.mqclient.graph.TopologyNodeType
import com.mqclient.topology.TopologyCanvas
import com.mqclient.topology.feBeLayout
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch

/** FE 展示名取 JDBC 连接的 host（连的就是 FE）；找不到回落占位文案。 */
private fun frontendName(connectionId: String): String =
    ConnectionStore.connections.value
        .firstOrNull { it.id == connectionId }
        ?.host
        ?.takeIf { it.isNotBlank() }
        ?: "FE"

/** 节点 id → 卡片显示名：剥掉前缀，BE 显示 IP:端口。 */
private fun topologyDisplayName(nodeId: String): String = when {
    nodeId.startsWith("fe:") -> nodeId.removePrefix("fe:")
    nodeId.startsWith("be:") -> nodeId.removePrefix("be:")
    else -> nodeId
}

/**
 * 「拓扑结构」面板：FE（中心）→ BE（子节点）星形图。
 *
 * 装配模式与 RocketMQ / Kafka 拓扑页一致：数据（[buildStarRocksTopology] 产出的
 * [TopologyData]）+ 布局（[feBeLayout]）+ [TopologyCanvas] 渲染。拖拽由
 * `nodeDragEnabled = true` 打开，缩放 / 平移 / 适应视图 / 滚动条全部由画布内置。
 *
 * 数据获取失败或没有 BE 节点 → 空状态占位图（图标 + 文案 + 刷新按钮），
 * 不渲染空画布。所有取数复用 [loadClusterNodes] → `executeQuery`。
 */
@Composable
internal fun StarRocksTopologyPanel(connectionId: String, modifier: Modifier = Modifier) {
    var data by remember { mutableStateOf<TopologyData?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        error = null
        try {
            val backends = loadClusterNodes(connectionId)
            data = buildStarRocksTopology(frontendName(connectionId), backends)
        } catch (e: Throwable) {
            error = e.userMessage()
            data = null
        } finally {
            loading = false
        }
    }

    LaunchedEffect(connectionId) { load() }

    Column(modifier.fillMaxSize()) {
        // ── 工具栏：标题 + 刷新 ──
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

        val graph = data
        when {
            loading -> EmptyTopology(
                text = stringResource("olap.starrocks.catalog.loading"),
                onRefresh = null,
            )

            // 数据获取失败 / 没有 BE 节点：空状态占位图。
            error != null -> EmptyTopology(
                text = stringResource("olap.starrocks.topology.failed").withArg(error!!),
                onRefresh = { scope.launch { load() } },
            )

            graph == null || graph.kuiver.nodes.isEmpty() -> EmptyTopology(
                text = stringResource("olap.starrocks.topology.empty"),
                onRefresh = { scope.launch { load() } },
            )

            else -> TopologyCanvas(
                data = graph,
                layoutProvider = feBeLayout,
                // 节点拖拽（kuiver 原生手势，位置仅会话内有效）。
                nodeDragEnabled = true,
                onRescan = { scope.launch { load() } },
                // MVP 不绑定属性对话框；双击保留给后续「BE 详情」迭代。
                onNodeDoubleClick = { },
                displayName = ::topologyDisplayName,
                fallbackType = TopologyNodeType.OLAP_FE,
                modifier = Modifier.fillMaxSize(),
            )
        }
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
