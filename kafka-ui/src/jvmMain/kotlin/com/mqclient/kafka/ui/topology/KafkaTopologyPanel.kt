package com.mqclient.kafka.ui.topology

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
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
import com.mqclient.kafka.KafkaEvents
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.ui.rememberKafkaConnectionStatus
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.command.ToolbarButton
import com.mqclient.topology.TopologyCanvas
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch

/**
 * Kafka 拓扑面板（导航项 `topology` 的 Tab 内容）：Topic → Partition → Broker。
 *
 * 数据口径：进入 Tab 经 AdminClient 直连实时查询（describeCluster + describeTopics，
 * 见 [KafkaTopologyState]），无缓存无定时扫描，右上角「重新扫描」重拉 —— 与 Kafka
 * 既有列表页同口径。连接分支语义与列表页一致：未连接自动隐式连接，失败展示原因 + 重试。
 *
 * 规模控制（聚合 + 按需展开）：默认每个 topic 一个节点（badge = 分区数）、分区折叠；
 * **双击 topic** 展开 / 收回它的分区节点（partition badge = leader broker id）；
 * 内部主题与副本虚线边走顶部开关，默认都关。双击 partition / broker 弹 Snackbar 提示
 * （Kafka v1 无对象属性面板）。
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
    var showReplicaEdges by remember { mutableStateOf(false) }
    var expandedTopic by remember { mutableStateOf<String?>(null) }

    var data by remember(connectionId) { mutableStateOf<TopologyData?>(null) }
    // 副本边集合（from to to）：TopologyCanvas 的 edgeCustomize 据此虚线化。
    var replicaEdges by remember(connectionId) {
        mutableStateOf<Set<Pair<String, String>>>(emptySet())
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val noPropertiesHint = stringResource("kafka_ui_topology_no_properties")
    // 副本边定制色（edgeCustomize 是普通 lambda，组合期先解析好）。
    val replicaEdgeColor = MaterialTheme.colorScheme.outline

    // badge 文案模板（builder 在组合外执行，模板在此解析、builder 里替换参数）。
    val partitionsTemplate = stringResource("kafka_ui_topic_partitions")
    val leaderTemplate = stringResource("kafka_ui_topology_partition_badge")
    val noLeaderBadge = stringResource("kafka_ui_topology_partition_offline")
    val internalLabel = stringResource("kafka_ui_topic_internal")
    val strings = remember(partitionsTemplate, leaderTemplate, noLeaderBadge, internalLabel) {
        KafkaTopologyStrings(
            partitionsBadge = { partitionsTemplate.withArg(it) },
            leaderBadge = { leaderTemplate.withArg(it) },
            noLeaderBadge = noLeaderBadge,
            internalLabel = internalLabel,
        )
    }

    // 重建图（纯内存计算）：快照 / 开关 / 展开目标任一变化都重算。
    // 空图不落地：刷新空结果不覆盖旧图（与 IBM MQ 拓扑同一语义）。
    LaunchedEffect(state.loadState, showInternal, showReplicaEdges, expandedTopic, strings) {
        val snapshot = (state.loadState as? LoadState.Success)?.data ?: return@LaunchedEffect
        // 展开目标被过滤（内部主题关掉等）时自动收回。
        val expansion = snapshot.topics
            .firstOrNull { it.name == expandedTopic && (showInternal || !it.internal) }
            ?.name
        if (expansion != expandedTopic) expandedTopic = expansion
        val next = buildKuiverFromSnapshot(
            snapshot = snapshot,
            strings = strings,
            expandedTopic = expansion,
            showInternalTopics = showInternal,
            showReplicaEdges = showReplicaEdges,
        )
        if (next.kuiver.nodes.isNotEmpty()) {
            replicaEdges = replicaEdgeIds(snapshot, expansion, showInternal)
            data = next
            Log.debug(
                TOPOLOGY_LOG_TAG,
                "topology rebuilt: showInternal=$showInternal replicas=$showReplicaEdges " +
                    "expanded=$expansion nodes=${next.kuiver.nodes.size} edges=${next.kuiver.edges.size}",
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
                    TextButton(
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
                // 顶部开关行：内部主题 / 副本边（与列表页工具栏同一视觉层级）。
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
                    ToolbarButton(
                        icon = Icons.Default.CallSplit,
                        text = stringResource("kafka_ui_topology_toggle_replicas"),
                        isActive = showReplicaEdges,
                        onClick = { showReplicaEdges = !showReplicaEdges },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                TopologyCanvas(
                    data = current,
                    layoutProvider = kafkaColumnLayoutProvider,
                    // 节点可拖拽：位置仅会话内有效（kuiver manualPositions，内存态），
                    // 切走 / 关闭 Tab 随组合销毁恢复默认布局。
                    nodeDragEnabled = true,
                    // 手动扫描：一轮对象慢扫，扫完经 OBJECTS_SCANNED 事件回到 onObjectsScanned。
                    onRescan = { KafkaModule.scanNow(connectionId) },
                    onNodeDoubleClick = { nodeId ->
                        when (current.nodeTypes[nodeId]) {
                            TopologyNodeType.TOPIC ->
                                expandedTopic = if (expandedTopic == nodeId) null else nodeId
                            else -> scope.launch { snackbarHostState.showSnackbar(noPropertiesHint) }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    fallbackType = TopologyNodeType.BROKER,
                    // 布局 provider 在布局阶段读模块级类型查找表：写入与 relayout 由
                    // TopologyCanvas 保证主线程相邻（时序不变量见 TopologyCanvas 文档）。
                    onBeforeLayout = { kafkaTopologyNodeTypes = it.nodeTypes },
                    snackbarHostState = snackbarHostState,
                    // 副本边虚线化（主题 outline 色 + 短虚线），与实线 leader 边区分。
                    edgeCustomize = { edge, base ->
                        if (edge.fromId to edge.toId in replicaEdges) {
                            base.copy(
                                color = replicaEdgeColor,
                                dashed = true,
                                dashLength = 6.dp,
                                gapLength = 6.dp,
                            )
                        } else {
                            base
                        }
                    },
                )
            }
        }
    }
}
