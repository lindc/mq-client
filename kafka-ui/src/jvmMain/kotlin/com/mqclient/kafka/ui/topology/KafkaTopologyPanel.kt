package com.mqclient.kafka.ui.topology

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
import com.mqclient.core.log.Log
import com.mqclient.graph.TopologyData
import com.mqclient.graph.TopologyNodeType
import com.mqclient.kafka.KafkaBrokerSnapshot
import com.mqclient.kafka.KafkaEvents
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.KafkaTopicSnapshot
import com.mqclient.kafka.ui.KafkaBrokerDetailDialog
import com.mqclient.kafka.ui.KafkaTopicPropertiesDialog
import com.mqclient.kafka.ui.rememberKafkaConnectionStatus
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.command.ToolbarButton
import com.mqclient.topology.TopologyCanvas
import com.mqclient.topology.topicBrokerLayout
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch

/**
 * Kafka 拓扑面板（导航项 `topology` 的 Tab 内容）：Topic → Broker 两列路由链路。
 *
 * 数据口径：进入 Tab 经 AdminClient 直连实时查询（describeCluster + describeTopics，
 * 见 [KafkaTopologyState]），无缓存无定时扫描，右上角「重新扫描」重拉 —— 与 Kafka
 * 既有列表页同口径。连接分支语义与列表页一致：未连接自动隐式连接，失败展示原因 + 重试。
 *
 * 结构：**聚合视图**——每个 topic 一个节点（badge = 分区数）；topic → broker 一条边
 * 代表「该 broker 承载此 topic 的 ≥1 个 leader 分区」；内部主题默认不进图（顶部开关）。
 * 分区级明细在主题属性对话框的「分区」Tab 查看（分区展开视图已移除）。
 *
 * **双击分派**（照 ibm-mq 拓扑先例，面板内 setState）：
 *  - TOPIC → 主题属性对话框（概览 / 配置 / 分区 Tab）；
 *  - BROKER → broker 详情对话框（controller 徽标经快照的 controllerId 判定）；
 *  - 未知节点 → Snackbar「无属性」提示。
 * 双击检测用 topology-ui 的 NodeDoubleClick（400ms 同节点窗口），单击选中不受影响，
 * 拖拽由 kuiver touch slop 区分——面板只装配分派逻辑。
 *
 * 画布组装复用 topology-ui 的 [TopologyCanvas]（选中链路高亮、流动动画、缩放控件、
 * 详情卡），本面板只装配数据与业务行为。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KafkaTopologyPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { KafkaTopologyState(connectionId) }
    val scope = rememberCoroutineScope()
    // 进入 Tab 首次加载（组合期一次，含隐式连接兜底）；之后的状态变化走
    // onStatusChanged —— 断开不再被面板静默重连（宿主会收掉断开连接的 Tab）。
    val connectionStatus = rememberKafkaConnectionStatus(connectionId)
    LaunchedEffect(connectionId) { state.load() }
    LaunchedEffect(connectionId, connectionStatus) { state.onStatusChanged(connectionStatus) }
    // 扫描事件驱动：慢扫完成 → 读缓存重建（on(scope = this) 随 effect 离开自动取消）。
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(KafkaEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onObjectsScanned()
        }
    }

    var showInternal by remember { mutableStateOf(false) }

    var data by remember(connectionId) { mutableStateOf<TopologyData?>(null) }

    // 双击打开的属性对话框目标（topic 名 / broker id）。
    var propsTopic by remember(connectionId) { mutableStateOf<String?>(null) }
    var propsBrokerId by remember(connectionId) { mutableStateOf<Int?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val noPropertiesHint = stringResource("kafka_ui_topology_no_properties")

    // badge 文案模板（builder 在组合外执行，模板在此解析、builder 里替换参数）。
    val partitionsTemplate = stringResource("kafka_ui_topic_partitions")
    val internalLabel = stringResource("kafka_ui_topic_internal")
    val strings = remember(partitionsTemplate, internalLabel) {
        KafkaTopologyStrings(
            partitionsBadge = { partitionsTemplate.withArg(it) },
            internalLabel = internalLabel,
        )
    }

    // 重建图（纯内存计算）：快照 / 开关任一变化都重算。
    // 空图不落地：刷新空结果不覆盖旧图（与 IBM MQ 拓扑同一语义）。
    LaunchedEffect(state.loadState, showInternal, strings) {
        val snapshot = (state.loadState as? LoadState.Success)?.data ?: return@LaunchedEffect
        val next = buildKuiverFromSnapshot(
            snapshot = snapshot,
            strings = strings,
            showInternalTopics = showInternal,
        )
        if (next.kuiver.nodes.isNotEmpty()) {
            data = next
            Log.debug(
                TOPOLOGY_LOG_TAG,
                "topology rebuilt: showInternal=$showInternal " +
                    "nodes=${next.kuiver.nodes.size} edges=${next.kuiver.edges.size}",
            )
        } else if (data == null) {
            data = next // 首帧就空：落空图让下方渲染「无对象」文案
        }
    }

    when (state.loadState) {
        is LoadState.Idle, is LoadState.Loading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (connectionStatus == ConnectionStatus.Connecting) {
                        stringResource("kafka_ui_connecting")
                    } else {
                        stringResource("kafka_ui_topology_loading")
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
                        Text(stringResource("kafka_ui_refresh"))
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
                        text = stringResource("kafka_ui_topology_empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return
            }
            Column(modifier.fillMaxSize()) {
                // 顶部开关行：内部主题是否进图。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolbarButton(
                        icon = if (showInternal) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        text = stringResource("kafka_ui_topology_toggle_internal"),
                        isActive = showInternal,
                        onClick = { showInternal = !showInternal },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                TopologyCanvas(
                    data = current,
                    layoutProvider = topicBrokerLayout,
                    // 节点可拖拽：位置仅会话内有效（kuiver manualPositions，内存态），
                    // 切走 / 关闭 Tab 随组合销毁恢复默认布局。
                    nodeDragEnabled = true,
                    // 手动扫描：一轮对象慢扫，扫完经 OBJECTS_SCANNED 事件回到 onObjectsScanned。
                    onRescan = { KafkaModule.scanNow(connectionId) },
                    // 双击分派（照 ibm-mq 拓扑先例，面板内 setState；拖拽由 kuiver
                    // touch slop 区分，无双击误触）。
                    onNodeDoubleClick = { nodeId ->
                        when (current.nodeTypes[nodeId]) {
                            TopologyNodeType.TOPIC -> propsTopic = nodeId
                            TopologyNodeType.BROKER ->
                                propsBrokerId = nodeId.removePrefix(BROKER_ID_PREFIX).toIntOrNull()
                            else -> scope.launch { snackbarHostState.showSnackbar(noPropertiesHint) }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    fallbackType = TopologyNodeType.BROKER,
                    snackbarHostState = snackbarHostState,
                )
            }

            // ---- 双击打开的属性对话框（声明在本 Tab 内容内，随 Tab 销毁）----
            propsTopic?.let { topicName ->
                KafkaTopicPropertiesDialog(
                    connectionId = connectionId,
                    topicName = topicName,
                    onDismiss = { propsTopic = null },
                    // 删除成功 → 触发一轮慢扫，拓扑图经 OBJECTS_SCANNED 事件自动更新。
                    onDeleted = { KafkaModule.scanNow(connectionId) },
                )
            }

            propsBrokerId?.let { brokerId ->
                val service = KafkaModule.serviceOrNull()
                val broker = service?.cachedBrokers(connectionId)?.firstOrNull { it.brokerId == brokerId }
                val topics = service?.cachedTopics(connectionId).orEmpty()
                val isController = snapshot?.controllerId == brokerId
                if (broker != null) {
                    KafkaBrokerDetailDialog(
                        broker = broker,
                        topics = topics,
                        isController = isController,
                        offline = false,
                        onDismiss = { propsBrokerId = null },
                    )
                } else {
                    // broker 已不在缓存（重扫窗口期）：关闭并提示。
                    LaunchedEffect(brokerId) {
                        propsBrokerId = null
                        snackbarHostState.showSnackbar(noPropertiesHint)
                    }
                }
            }
        }
    }
}

/** broker 节点 id 的前缀（与 KafkaTopologyGraphBuilder 的 id 约定同源）。 */
private const val BROKER_ID_PREFIX = "b:"
