package com.mqclient.kafka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.kafka.KafkaBrokerSnapshot
import com.mqclient.kafka.KafkaEvents
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.ui.rememberKafkaConnectionStatus
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch

/**
 * Kafka 集群概览面板（导航项 `clusterOverview` 的 Tab 内容）：Cluster ID / Controller /
 * Broker 总数 / Topic 总数信息条 + Broker 表（ID / Host / Port / Rack / 角色）。
 *
 * 结构复用 ibm-mq 的 ClusterRelationsPanel 框架（信息提示条 + 固定列表格 + 三态），
 * 内容按 Kafka 语义替换：**无拓扑画布**（broker 扁平结构，星型图信息量低），Broker
 * 表为主。数据**缓存优先、事件驱动**（[KafkaClusterOverviewState]，随对象慢扫每轮
 * 刷新）——与 MQ 集群面板的实时查询刻意不同。
 *
 * 视觉语义：
 *  - Controller 行：绿色徽章（describeCluster 的 isController，KRaft 唯一活跃 controller）；
 *  - 离线行：红色文字 + 淡红底 —— 副本分配引用但不在存活列表的 broker id（推断语义，
 *    describeCluster 只回存活节点）。
 *
 * Broker 行点击选中高亮 + Snackbar 提示（详情页后续版本）。
 */
@Composable
fun KafkaClusterOverviewPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { KafkaClusterOverviewState(connectionId) }
    val connectionStatus = rememberKafkaConnectionStatus(connectionId)
    // 进 Tab 首次加载（含隐式连接兜底）；之后的状态变化走 onStatusChanged。
    LaunchedEffect(connectionId) { state.load() }
    LaunchedEffect(connectionId, connectionStatus) { state.onStatusChanged(connectionStatus) }
    // 扫描事件驱动：慢扫完成 → 读缓存重建（on(scope = this) 随 effect 离开自动取消）。
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(KafkaEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onObjectsScanned()
        }
    }

    when (val snapshot = state.loadState) {
        is LoadState.Idle, is LoadState.Loading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (connectionStatus == ConnectionStatus.Connecting) {
                            stringResource("kafka_ui_connecting")
                        } else {
                            stringResource("kafka_ui_cluster_loading")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is LoadState.Error -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource("kafka_ui_cluster_failed").withArg(snapshot.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { state.retry() }) {
                        Text(stringResource("kafka_ui_refresh"))
                    }
                }
            }
        }
        is LoadState.Success -> ClusterOverviewPane(
            connectionId = connectionId,
            snapshot = snapshot.data,
            modifier = modifier.fillMaxSize(),
        )
    }
}

/** 就绪态：信息条（标题 + 重扫）→ 集群指标行 → Broker 表。 */
@Composable
private fun ClusterOverviewPane(
    connectionId: String,
    snapshot: KafkaClusterOverviewSnapshot,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val detailHint = stringResource("kafka_ui_cluster_detail_unavailable")
    var selectedId by remember { mutableStateOf<Int?>(null) }

    Box(modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 标题条：左侧标题，右侧重新扫描（与拓扑页同一动作入口）----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("kafka_ui_cluster_title"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { KafkaModule.scanNow(connectionId) }) {
                    Text(stringResource("kafka_ui_refresh"))
                }
            }
            HorizontalDivider()

            if (snapshot.isEmpty) {
                Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource("kafka_ui_cluster_empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // ---- 集群指标行：Cluster ID / Controller / Broker 数 / Topic 数 ----
                ClusterMetricsRow(snapshot)
                HorizontalDivider()
                // ---- Broker 表：行点击选中 + Snackbar 提示 ----
                BrokerTable(
                    snapshot = snapshot,
                    selectedId = selectedId,
                    onSelect = { id ->
                        selectedId = id
                        if (id != null) {
                            scope.launch { snackbarHostState.showSnackbar(detailHint) }
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
        ThemedSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 集群指标行：Cluster ID / Controller / Broker 数 / Topic 数 四列。 */
@Composable
private fun ClusterMetricsRow(snapshot: KafkaClusterOverviewSnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
    ) {
        MetricCell(
            label = stringResource("kafka_ui_cluster_info_cluster_id"),
            value = snapshot.clusterId ?: "-",
            monospace = true,
            modifier = Modifier.weight(1.6f),
        )
        MetricCell(
            label = stringResource("kafka_ui_cluster_info_controller"),
            value = snapshot.controllerId?.toString() ?: "-",
            modifier = Modifier.weight(0.6f),
        )
        MetricCell(
            label = stringResource("kafka_ui_cluster_info_brokers"),
            value = snapshot.brokers.size.toString(),
            modifier = Modifier.weight(0.5f),
        )
        MetricCell(
            label = stringResource("kafka_ui_cluster_info_topics"),
            value = snapshot.topics.size.toString(),
            modifier = Modifier.weight(0.5f),
        )
    }
}

/** 一个指标：小字标签 + 主值（Cluster ID 等长值用等宽字体省略）。 */
@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier, monospace: Boolean = false) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Broker 表：表头 + 行（ID / Host / Port / Rack / 角色），行点击选中联高亮。 */
@Composable
private fun BrokerTable(
    snapshot: KafkaClusterOverviewSnapshot,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offlineIds = snapshot.offlineBrokerIds
    Column(modifier) {
        Text(
            text = stringResource("kafka_ui_cluster_brokers"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            BrokerCell(stringResource("kafka_ui_cluster_col_id"), width = COL_ID, bold = true)
            BrokerCell(stringResource("kafka_ui_cluster_col_host"), Modifier.weight(1f), bold = true)
            BrokerCell(stringResource("kafka_ui_cluster_col_port"), width = COL_PORT, bold = true)
            BrokerCell(stringResource("kafka_ui_cluster_col_rack"), width = COL_RACK, bold = true)
            BrokerCell(stringResource("kafka_ui_cluster_col_role"), width = COL_ROLE, bold = true)
        }
        HorizontalDivider()
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            snapshot.brokers.forEach { broker ->
                val selected = broker.brokerId == selectedId
                val offline = broker.brokerId in offlineIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                offline -> MaterialTheme.colorScheme.error.copy(alpha = 0.06f)
                                selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.surface
                            },
                        )
                        .clickable { onSelect(broker.brokerId) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrokerCell(broker.brokerId.toString(), width = COL_ID, monospace = true)
                    BrokerCell(broker.host, Modifier.weight(1f), monospace = true)
                    BrokerCell(broker.port.toString(), width = COL_PORT, monospace = true)
                    BrokerCell(broker.rack ?: "-", width = COL_RACK, monospace = true)
                    BrokerRoleCell(broker = broker, offline = offline, width = COL_ROLE)
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

/** 角色单元格：Controller 绿色徽章 / 离线红字 / 普通「-」。 */
@Composable
private fun BrokerRoleCell(broker: KafkaBrokerSnapshot, offline: Boolean, width: androidx.compose.ui.unit.Dp) {
    when {
        broker.isController -> Row(modifier = Modifier.width(width)) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = CONTROLLER_GREEN.copy(alpha = 0.14f),
                border = androidx.compose.foundation.BorderStroke(1.dp, CONTROLLER_GREEN.copy(alpha = 0.5f)),
            ) {
                Text(
                    text = stringResource("kafka_ui_cluster_role_controller"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = CONTROLLER_GREEN,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        offline -> BrokerCell(
            text = stringResource("kafka_ui_cluster_offline"),
            width = width,
            color = MaterialTheme.colorScheme.error,
        )
        else -> BrokerCell("-", width = width, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 表格单元格（固定列风格同 MQ MemberTable）：等宽字体 + 单行省略。 */
@Composable
private fun BrokerCell(
    text: String,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp? = null,
    bold: Boolean = false,
    monospace: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
        fontFamily = if (monospace || bold) FontFamily.Monospace else FontFamily.Default,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        color = color,
        modifier = when {
            width != null -> modifier.width(width)
            else -> modifier
        },
    )
}

/** Controller 徽章的固定语义绿：健康标记，不随主题翻转（与告警橙 / 环橙同一约定）。 */
private val CONTROLLER_GREEN = Color(0xFF2E7D32)

/** 表格列宽（Host 弹性占满余量，其余定宽）。 */
private val COL_ID = 60.dp
private val COL_PORT = 80.dp
private val COL_RACK = 90.dp
private val COL_ROLE = 110.dp
