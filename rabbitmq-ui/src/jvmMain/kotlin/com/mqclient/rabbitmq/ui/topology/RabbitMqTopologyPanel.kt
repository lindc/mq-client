package com.mqclient.rabbitmq.ui.topology

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.graph.TopologyData
import com.mqclient.graph.TopologyNodeType
import com.mqclient.uicommon.command.ToolbarButton
import com.mqclient.mqui.state.LoadState
import com.mqclient.rabbitmq.RabbitMqEvents
import com.mqclient.rabbitmq.RabbitMqExchangeSnapshot
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.ui.RabbitMqExchangePropertiesDialog
import com.mqclient.rabbitmq.ui.ensureRabbitMqConnecting
import com.mqclient.rabbitmq.ui.rememberRabbitMqConnectionStatus
import com.mqclient.topology.TopologyCanvas
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch

/**
 * RabbitMQ 拓扑页（导航项 `topology` 的 Tab 内容）：Exchange → Binding → Queue 的
 * 路由链路图，与 kafka / ibm-mq 的拓扑面板同一装配模式（State + LayoutProvider +
 * TopologyCanvas）。顶部「显示系统对象」开关控制默认交换机与 amq.* 是否进图；
 * queue 节点 badge 为当前消息数（快扫驱动刷新），积压无消费者的节点 WARNING 橙。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RabbitMqTopologyPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { RabbitMqTopologyState(connectionId) }
    val scope = rememberCoroutineScope()
    // 进入 Tab 首次加载（组合期一次，含隐式连接兜底）；之后的状态变化走
    // onStatusChanged —— 断开不再被面板静默重连（宿主会收掉断开连接的 Tab）。
    val connectionStatus = rememberRabbitMqConnectionStatus(connectionId)
    LaunchedEffect(connectionId) { state.load() }
    LaunchedEffect(connectionId, connectionStatus) { state.onStatusChanged(connectionStatus) }
    // 扫描事件驱动：慢扫重建图结构 / 快扫刷 badge 计数。
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RabbitMqEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onObjectsScanned()
        }
    }
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RabbitMqEvents.STATUS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onStatusScanned()
        }
    }

    var showSystemObjects by remember { mutableStateOf(false) }

    var data by remember(connectionId) { mutableStateOf<TopologyData?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // badge 文案模板（builder 在组合外执行，模板在此解析、builder 里替换参数）。
    val messagesTemplate = stringResource("rabbitmq_ui_topology_messages_badge")
    val strings = remember(messagesTemplate) {
        RabbitMqTopologyStrings(messagesBadge = { messagesTemplate.withArg(it) })
    }

    // 重建图（纯内存计算）：快照 / 开关任一变化都重算。
    // 空图不落地：刷新空结果不覆盖旧图（与 kafka / IBM MQ 拓扑同一语义）。
    LaunchedEffect(state.loadState, showSystemObjects, strings) {
        val snapshot = (state.loadState as? LoadState.Success)?.data ?: return@LaunchedEffect
        val next = buildKuiverFromSnapshot(
            snapshot = snapshot,
            strings = strings,
            showSystemObjects = showSystemObjects,
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
                        stringResource("rabbitmq_ui_connecting")
                    } else {
                        stringResource("rabbitmq_ui_topology_loading")
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
                    TextButton(
                        onClick = { scope.launch { state.load() } },
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text(stringResource("rabbitmq_ui_refresh"))
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
                        text = stringResource("rabbitmq_ui_topology_empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return
            }
            // 双击 exchange 节点 → 打开属性对话框（含 Bindings 表与删除入口）。
            var propsExchange by remember(connectionId) { mutableStateOf<RabbitMqExchangeSnapshot?>(null) }
            Column(modifier.fillMaxSize()) {
                // 顶部开关行：系统对象（默认交换机 / amq.*）是否进图。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 「显示系统对象」开关：与交换机列表页同一扁平工具栏按钮，
                    // 眼睛图标随状态切换。
                    ToolbarButton(
                        icon = if (showSystemObjects) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        text = stringResource("rabbitmq_ui_exchange_toggle_system"),
                        onClick = { showSystemObjects = !showSystemObjects },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                TopologyCanvas(
                    data = current,
                    layoutProvider = rabbitMqColumnLayoutProvider,
                    // 节点可拖拽：位置仅会话内有效（kuiver manualPositions，内存态）。
                    nodeDragEnabled = true,
                    // 手动扫描：一轮对象慢扫，扫完经 OBJECTS_SCANNED 事件回到 onObjectsScanned。
                    onRescan = { RabbitMqModule.scanNow(connectionId) },
                    onNodeDoubleClick = { nodeId ->
                        when (current.nodeTypes[nodeId]) {
                            TopologyNodeType.EXCHANGE -> {
                                val name = nodeId.removePrefix("x:")
                                snapshot?.exchanges?.firstOrNull { it.name == name }?.let {
                                    propsExchange = it
                                }
                            }
                            // queue 节点的属性对话框未实现，双击出提示。
                            else -> scope.launch {
                                snackbarHostState.showSnackbar(
                                    stringResource("rabbitmq_ui_topology_queue_props_todo"),
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    fallbackType = TopologyNodeType.RABBIT_QUEUE,
                    // 布局 provider 在布局阶段读模块级类型查找表：写入与 relayout 由
                    // TopologyCanvas 保证主线程相邻（时序不变量见 TopologyCanvas 文档）。
                    onBeforeLayout = { rabbitMqTopologyNodeTypes = it.nodeTypes },
                    snackbarHostState = snackbarHostState,
                )
            }

            // 属性对话框（双击 exchange 打开；删除成功后经 OBJECTS_SCANNED 事件刷新图）。
            propsExchange?.let { exchange ->
                RabbitMqExchangePropertiesDialog(
                    connectionId = connectionId,
                    exchangeName = exchange.name,
                    displayName = exchange.name.ifEmpty { stringResource("rabbitmq_ui_exchange_default") },
                    type = exchange.type,
                    durable = exchange.durable,
                    autoDelete = exchange.autoDelete,
                    internal = exchange.internal,
                    onDismiss = { propsExchange = null },
                )
            }
        }
    }
}
