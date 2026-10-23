package com.mqclient.rocketmq.ui.topology

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.graph.TopologyData
import com.mqclient.graph.TopologyNodeType
import com.mqclient.mqui.state.LoadState
import com.mqclient.rocketmq.RocketMqEvents
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.ui.ensureRocketMqConnecting
import kotlinx.coroutines.launch
import com.mqclient.rocketmq.ui.RocketMqBrokerPropertiesDialog
import com.mqclient.rocketmq.ui.RocketMqTopicPropertiesDialog
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.rocketmq.ui.rememberRocketMqConnectionStatus
import com.mqclient.topology.TopologyCanvas
import com.mqclient.uicommon.command.ToolbarButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * RocketMQ 拓扑页（导航项 `topology`，Phase 2 注册）：Topic → Broker 的路由聚合
 * 图，与 kafka 的拓扑面板同一装配模式（State + GraphBuilder + ColumnLayoutProvider +
 * TopologyCanvas）。顶部「显示系统主题」开关控制 %RETRY% / %DLQ% / SCHEDULE_TOPIC
 * 等是否进图；topic 节点 badge 为读写队列数（慢扫驱动刷新），无写权限的节点
 * WARNING 橙。
 */
@Composable
fun RocketMqTopologyPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { RocketMqTopologyState(connectionId) }
    val scope = rememberCoroutineScope()
    val connectionStatus = rememberRocketMqConnectionStatus(connectionId)
    LaunchedEffect(connectionId) { state.load() }
    LaunchedEffect(connectionId, connectionStatus) { state.onStatusChanged(connectionStatus) }
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RocketMqEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onObjectsScanned()
        }
    }

    var showSystemTopics by remember { mutableStateOf(false) }
    var propsTopic by remember { mutableStateOf<String?>(null) }
    var propsBroker by remember { mutableStateOf<String?>(null) }
    var data by remember(connectionId) { mutableStateOf<TopologyData?>(null) }

    // badge 文案模板（builder 在组合外执行，模板在此解析、builder 里替换参数）。
    val routeTemplate = stringResource("rocketmq_ui_topology_route_badge")
    val strings = remember(routeTemplate) {
        RocketMqTopologyStrings(routeBadge = { read, write -> routeTemplate.format(read, write) })
    }

    // 重建图（纯内存计算）：快照 / 开关任一变化都重算。
    // 空图不落地：刷新空结果不覆盖旧图（与 kafka / IBM MQ 拓扑同一语义）。
    LaunchedEffect(state.loadState, showSystemTopics, strings) {
        val snapshot = (state.loadState as? LoadState.Success)?.data ?: return@LaunchedEffect
        val next = buildKuiverFromSnapshot(
            topics = snapshot.topics,
            brokerAddresses = snapshot.brokers.associate { it.name to it.address },
            strings = strings,
            showSystemTopics = showSystemTopics,
        )
        if (next.kuiver.nodes.isNotEmpty()) {
            data = next
        } else if (data == null) {
            data = next // 首帧就空：落空图让下方渲染「无对象」文案
        }
    }

    when (state.loadState) {
        is LoadState.Idle, is LoadState.Loading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (connectionStatus == ConnectionStatus.Connecting) {
                        stringResource("rocketmq_ui_connecting")
                    } else {
                        stringResource("rocketmq_ui_topology_loading")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is LoadState.Error -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = (state.loadState as LoadState.Error).message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    SecondaryButton(
                        onClick = { scope.launch { state.load() } },
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text(stringResource("rocketmq_ui_refresh"))
                    }
                }
            }
        }
        is LoadState.Success -> {
            val current = data
            val snapshot = (state.loadState as? LoadState.Success)?.data
            if (current == null || current.kuiver.nodes.isEmpty()) {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource("rocketmq_ui_topology_empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return
            }
            Column(modifier.fillMaxSize()) {
                // 顶部开关行：系统主题是否进图。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolbarButton(
                        icon = if (showSystemTopics) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        text = stringResource("rocketmq_ui_topology_toggle_system"),
                        isActive = showSystemTopics,
                        onClick = { showSystemTopics = !showSystemTopics },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                TopologyCanvas(
                    data = current,
                    layoutProvider = rocketMqColumnLayoutProvider,
                    // 节点可拖拽：位置仅会话内有效（kuiver manualPositions，内存态）。
                    nodeDragEnabled = true,
                    // 手动扫描：一轮对象慢扫，扫完经 OBJECTS_SCANNED 事件回到 onObjectsScanned。
                    onRescan = { RocketMqModule.scanNow(connectionId) },
                    // 双击节点：TOPIC（id = 主题原名）打开主题属性；BROKER（`b:` 前缀）
                    // 打开 Broker 属性。节点的拖拽由 kuiver 原生手势的 touch slop
                    // （距离阈值）隔离，只有未超阈值的点击才会进 onNodeClick，本回调
                    // 只消费点击结果，不需要再自行判定拖拽。
                    onNodeDoubleClick = { nodeId ->
                        if (current.nodeTypes[nodeId] == TopologyNodeType.BROKER) {
                            propsTopic = null
                            propsBroker = nodeId.removePrefix("b:")
                        } else {
                            propsBroker = null
                            propsTopic = nodeId
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    fallbackType = TopologyNodeType.TOPIC,
                    // 布局 provider 在布局阶段读模块级类型查找表：写入与 relayout 由
                    // TopologyCanvas 保证主线程相邻（时序不变量见 TopologyCanvas 文档）。
                    onBeforeLayout = { rocketMqTopologyNodeTypes = it.nodeTypes },
                )
            }
        }
    }

    propsTopic?.let { name ->
        RocketMqTopicPropertiesDialog(
            connectionId = connectionId,
            topicName = name,
            onDismiss = { propsTopic = null },
        )
    }

    propsBroker?.let { brokerName ->
        RocketMqBrokerPropertiesDialog(
            connectionId = connectionId,
            brokerName = brokerName,
            onDismiss = { propsBroker = null },
        )
    }
}
