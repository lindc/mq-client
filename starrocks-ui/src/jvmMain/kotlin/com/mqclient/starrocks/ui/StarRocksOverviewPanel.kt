package com.mqclient.starrocks.ui

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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
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
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import kotlinx.coroutines.launch

/**
 * 「集群概览」只读面板：优先 `SHOW BACKENDS`，不支持则 `SHOW FRONTENDS`，
 * 两者都不支持（纯 MySQL）就展示空数据 —— 不报错、不阻断。
 *
 * 指标卡复用 mq-ui 的 [MetricCard]（与 RabbitMQ 概览同款），下方节点表格
 * 存活绿 / 死亡红。所有取数走 [queryRows]（复用 executeQuery）。
 */
@Composable
internal fun StarRocksOverviewPanel(connectionId: String, modifier: Modifier = Modifier) {
    var nodes by remember { mutableStateOf<List<ClusterNode>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        error = null
        try {
            nodes = loadClusterNodes(connectionId)
        } catch (e: Throwable) {
            error = e.userMessage()
            nodes = emptyList()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(connectionId) { load() }

    val aliveCount = nodes.count { it.alive }
    val version = nodes.firstOrNull { it.version != "-" }?.version ?: "-"

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

        // ── 指标卡 ──
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
                valueColor = if (nodes.isNotEmpty() && aliveCount == nodes.size)
                    Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            MetricCard(
                icon = Icons.Filled.Info,
                label = stringResource("olap.starrocks.overview.version"),
                value = version,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        // ── 节点表格 / 空状态 ──
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
                        Text(
                            stringResource("olap.starrocks.overview.col_ip"),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource("olap.starrocks.overview.col_port"),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(0.7f),
                        )
                        Text(
                            stringResource("olap.starrocks.overview.col_alive"),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(0.7f),
                        )
                    }
                }
                items(nodes, key = { "${it.ip}:${it.port}" }) { node ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(node.ip, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(node.port, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.7f))
                        Text(
                            stringResource(
                                if (node.alive) "olap.starrocks.overview.alive"
                                else "olap.starrocks.overview.dead",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (node.alive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(0.7f),
                        )
                    }
                }
            }
        }
    }
}
