package com.mqclient.ibmmq.ui.topology

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dk.kuiver.SelectionMode
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.rememberKuiverViewerState
import com.dk.kuiver.renderer.KuiverInteractionCallbacks
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.CoreModule
import com.mqclient.core.topology.TopologyFocusHolder
import com.mqclient.ibmmq.IbmMQConfig
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.event.IbmMQEvents
import com.mqclient.ibmmq.ui.properties.MqObjectPropertiesDialog
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogState
import com.mqclient.ibmmq.ui.properties.toMqObjectType
import com.mqclient.ibmmq.ibmMQConfigFromOptions
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.launch

/**
 * IBM MQ 专属拓扑面板（固定列布局版）。
 *
 * 列序按 MQ 消息流向固定（alias→topic→sub→remote→xmitq→channel→远程QMGR→alias→local），
 * 含 REMOTE_QMGR 虚拟节点与环（BACK 边）标橙。宿主路由
 * （[com.mqclient.ibmmq.ui.IbmMqUI].contentFor("topology")）指向本面板。
 *
 * 数据自给，**缓存优先**：
 *  1. 进入面板只读慢扫缓存出图，不触发扫描（后台既有的定时扫描照常经事件刷新图）；
 *  2. 缓存为空才继续：已连接 → 触发一次对象慢扫；未连接 → [IbmMQModule.connectAsync]
 *     隐式连接（与侧栏点击连接同一入口），CONNECTED 事件到达后再扫；
 *  3. OBJECTS_SCANNED → 从缓存重建图。
 * 加载态保持到**首个非空图**（与列表页「不提交空态」同语义）；之后扫描空结果不覆盖旧图。
 * 强制刷新走右上角「重新扫描」按钮。
 *
 * 焦点：列表页「在拓扑图中查看」经 [TopologyFocusHolder] 带入对象名，出图后选中该节点
 * （图内高亮 + 底部详情卡）——全量固定列图本身一屏可见，无需再按跳数裁剪。
 */
@Composable
fun TopologyPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    // 队列深度告警阈值（%）：与队列列表页同源（连接配置 queueDepthWarningThreshold，默认 85）。
    // 响应式读取：组合期直接读 StateFlow.value，编辑连接阈值后面板自动刷新。
    val warningThreshold = ConnectionStore.connections.value
        .firstOrNull { it.id == connectionId }
        ?.let { ibmMQConfigFromOptions(it.options).queueDepthWarningThreshold }
        ?: IbmMQConfig.DEFAULT_QUEUE_DEPTH_WARNING_THRESHOLD

    var data by remember(connectionId) { mutableStateOf<MqTopologyData?>(null) }
    // 至少完成过一轮慢扫：区分「还在连 / 扫」与「扫完了但确实没有业务对象」。
    var scannedOnce by remember(connectionId) { mutableStateOf(false) }
    // 列表页带入的焦点对象名：在 LaunchedEffect（副作用上下文）里 consume——
    // 放在 remember 计算块的话，组合被丢弃/重试时焦点会被消费掉却没应用到图上。
    var focusName by remember(connectionId) { mutableStateOf<String?>(null) }

    /** 从慢扫缓存重建；**空图不落地**——保持加载态或保留旧图（刷新保留旧数据的语义）。 */
    fun rebuild() {
        val next = buildKuiverFromCache(connectionId, warningThreshold)
        if (next.kuiver.nodes.isNotEmpty()) data = next
    }

    /** 强制刷新：显式触发一次对象慢扫（扫完经 OBJECTS_SCANNED 驱动重建）。 */
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun rescan() {
        scope.launch {
            runCatching { IbmMQModule.serviceOrNull()?.scanObjects(connectionId) }
        }
    }

    // 订阅先于动作（防丢事件）；on(scope = this) 随 LaunchedEffect 离开自动取消。
    LaunchedEffect(connectionId) {
        // 消费焦点也放进副作用上下文（与数据流同处，时序上早于 rebuild 即可）。
        focusName = TopologyFocusHolder.consume(connectionId)?.rootName
        val bus = CoreModule.eventBusOrNull
        bus?.on(IbmMQEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) {
                scannedOnce = true
                rebuild()
            }
        }
        // 隐式连接建立 → 仅当还没有图（缓存为空）时补一拍慢扫；热缓存不打扰。
        bus?.on(IbmMQEvents.CONNECTED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId && data == null) {
                runCatching { IbmMQModule.serviceOrNull()?.scanObjects(connectionId) }
            }
        }

        // 缓存优先：只读缓存出图；缓存为空才继续连 / 扫。
        rebuild()
        if (data == null) {
            if (IbmMQModule.connectionOrNull(connectionId)?.status == ConnectionStatus.Connected) {
                runCatching { IbmMQModule.serviceOrNull()?.scanObjects(connectionId) }
            } else {
                // 未连接：隐式连接（fire-and-forget），连上后由上方 CONNECTED 分支接管扫描。
                IbmMQModule.connectAsync(connectionId)
            }
        }
    }

    val current = data
    if (current == null && !scannedOnce) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    text = stringResource("topology_loading"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        return
    }
    if (current == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("topology_empty"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    MqTopologyCanvas(
        connectionId = connectionId,
        data = current,
        focusName = focusName,
        onFocusApplied = { focusName = null },
        onRescan = ::rescan,
        modifier = modifier,
    )
}

/**
 * kuiver 画布。viewer state 只建一次（保留缩放 / 平移），图数据变化经
 * updateKuiver + relayout 换入（updateKuiver 不触发布局，必须跟一次 relayout，
 * 且 relayout 前要把最新 nodeTypes 写进 [topologyNodeTypes] 供布局 provider 读取）。
 */
@Composable
private fun MqTopologyCanvas(
    connectionId: String,
    data: MqTopologyData,
    focusName: String?,
    onFocusApplied: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (kuiver, nodeTypes, severities) = data
    val scheme = MaterialTheme.colorScheme

    // 对象属性对话框：**双击节点**打开（kuiver 0.4.2 无 onNodeDoubleClick，用
    // 「400ms 内同节点两击」手动检测）；rqmgr: 前缀虚拟节点无真实对象，跳过。
    var propertiesDialog by remember { mutableStateOf<ObjectPropertiesDialogState?>(null) }
    var lastNodeClick by remember { mutableStateOf<Pair<String, Long>?>(null) }

    val viewerState = rememberKuiverViewerState(
        initialKuiver = Kuiver(),
        layoutConfig = LayoutConfig.Custom(provider = columnLayoutProvider),
    )

    var pendingFit by remember { mutableStateOf(false) }
    LaunchedEffect(kuiver) {
        topologyNodeTypes = nodeTypes
        viewerState.updateKuiver(kuiver)
        viewerState.relayout()
        pendingFit = true
    }

    var selectedId by remember { mutableStateOf<String?>(null) }

    /** 统一选中入口：selectedId 驱动详情卡，同时写 kuiver 自己的选中态（图内高亮的可靠来源）。 */
    fun select(id: String?) {
        selectedId = id
        if (id != null) {
            viewerState.interaction.select(id)
        } else {
            viewerState.interaction.clearSelection()
        }
    }

    // 选中链路高亮：沿有向边做上下游闭包（上游=流入它的，下游=它流向的，BFS 防环）。
    // 闭包内节点原样、相关边加粗提亮；闭包外节点压暗、无关边淡化——凸显消息流向。
    val highlight = remember(selectedId, kuiver) {
        val root = selectedId ?: return@remember null
        val incoming = kuiver.edges.groupBy { it.toId }
        val outgoing = kuiver.edges.groupBy { it.fromId }
        fun closure(step: (String) -> List<String>): Set<String> {
            val seen = mutableSetOf(root)
            val queue = ArrayDeque(listOf(root))
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                for (nb in step(cur)) if (seen.add(nb)) queue += nb
            }
            return seen
        }
        val nodes = closure { id -> outgoing[id]?.map { it.toId }.orEmpty() } +
            closure { id -> incoming[id]?.map { it.fromId }.orEmpty() }
        val edges = kuiver.edges
            .filter { it.fromId in nodes && it.toId in nodes }
            .map { it.fromId to it.toId }
            .toSet()
        Highlight(nodes, edges)
    }

    // 高亮边的流动动画：kuiver 的 dashPathEffect 相位写死为 0（虚线锚在路径起点），
    // 但**循环递增 gapLength 会把后续虚线段整体向终点推移**——位移满一个周期
    // （dash+gap=20dp）后图案无缝归零，观感即"虚线沿消息方向持续流动"。
    // 线性匀速 + Restart 无缝循环。**仅在有选中（highlight != null）时创建**——
    // 无条件常驻会让整个面板空跑每帧动画，白耗 CPU。
    val flowGap = if (highlight != null) {
        rememberInfiniteTransition(label = "edgeFlow").animateFloat(
            initialValue = FLOW_DASH,
            targetValue = FLOW_DASH * 3,
            animationSpec = infiniteRepeatable(tween(FLOW_PERIOD_MS, easing = LinearEasing)),
            label = "flowGap",
        ).value
    } else {
        FLOW_DASH
    }

    // 布局落定且画布已测量后执行一次「适应视图」；手动缩放 / 拖拽不置位 pendingFit。
    // 焦点对象（列表页「在拓扑图中查看」）在首次出图后选中：图内高亮 + 底部详情卡。
    LaunchedEffect(viewerState.layoutedKuiver, viewerState.canvasWidth) {
        if (viewerState.canvasWidth > 0.dp &&
            viewerState.layoutedKuiver.nodes.isNotEmpty()
        ) {
            if (pendingFit) {
                viewerState.centerGraph(animated = true)
                pendingFit = false
            }
            if (focusName != null &&
                viewerState.layoutedKuiver.nodes.containsKey(focusName)
            ) {
                select(focusName)
                onFocusApplied() // 应用一次即清空：防尺寸变化时复活旧选中
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        KuiverViewer(
            state = viewerState,
            modifier = Modifier.fillMaxSize(),
            config = KuiverViewerConfig(
                hoverEnabled = true,
                selectionMode = SelectionMode.NONE,
                // 固定列布局本身就是语义位置，禁拖拽防弄乱列序。
                nodeDragEnabled = false,
                // 滚轮缩放 / 画布平移是 kuiver 内建手势（无开关），这里只夹量程：
                // 0.2x 看全景，2.0x 看细节。
                minScale = 0.2f,
                maxScale = 2.0f,
                // fit 由本面板 pendingFit 统一驱动（首次加载即适应内容），不用引擎内置的一次性 fit。
                fitToContent = false,
                contentPadding = 0.9f,
            ),
            callbacks = remember {
                KuiverInteractionCallbacks(
                    onNodeClick = { node ->
                        val now = System.currentTimeMillis()
                        val last = lastNodeClick
                        if (last != null && last.first == node.id && now - last.second < DOUBLE_CLICK_WINDOW_MS) {
                            // 双击：打开对象属性对话框（rqmgr: 虚拟节点无真实对象，跳过）
                            lastNodeClick = null
                            if (!node.id.startsWith(REMOTE_QMGR_PREFIX)) {
                                nodeTypes[node.id]?.toMqObjectType()?.let { type ->
                                    propertiesDialog = ObjectPropertiesDialogState(
                                        connectionId = connectionId,
                                        objectType = type,
                                        objectName = node.id,
                                    )
                                }
                            }
                        } else {
                            lastNodeClick = node.id to now
                        }
                        select(node.id)
                    },
                    onCanvasClick = {
                        lastNodeClick = null
                        select(null)
                    },
                )
            },
            nodeContent = { node ->
                val type = nodeTypes[node.id] ?: TopologyNodeType.LOCAL_QUEUE
                TopologyNodeCard(
                    name = node.id.removePrefix(REMOTE_QMGR_PREFIX),
                    type = type,
                    isHovered = isHovered,
                    // 选中高亮双保险：kuiver interaction 的 isSelected（内部状态，插槽
                    // 作用域可靠失效）+ 本面板 selectedId（焦点选中的直接来源）。
                    isSelected = isSelected || node.id == selectedId,
                    // 告警覆盖类型色：队列积压→警告橙，通道 STOPPED/RETRYING→红。
                    severity = severities[node.id] ?: NodeSeverity.NORMAL,
                    // 链路高亮模式：闭包外节点压暗，凸显选中线路。
                    dimmed = highlight != null && node.id !in highlight.nodes,
                )
            },
            // 贝塞尔曲线边（EdgeShape.CURVED，见 CurvedEdgeContent.kt 的取舍说明）。
            // 链路高亮模式：闭包内边满透明度加粗 + 虚线流动动画（见 flowGap 注释），
            // 闭包外边淡到近隐——看消息流向。
            edgeStyle = { edge ->
                val inPath = highlight == null || (edge.fromId to edge.toId) in highlight.edges
                val normalAlpha = if (highlight == null) 0.6f else if (inPath) 0.95f else 0.08f
                val cycleAlpha = if (highlight == null) 0.8f else if (inPath) 1f else 0.1f
                val base = topologyEdgeStyle(
                    edge = edge,
                    normalColor = scheme.outlineVariant.copy(alpha = normalAlpha),
                    cycleColor = CYCLE_EDGE_COLOR.copy(alpha = cycleAlpha),
                    strokeWidth = if (highlight != null && inPath) 2.dp else 1.5.dp,
                )
                if (highlight != null && inPath) {
                    base.copy(dashed = true, dashLength = FLOW_DASH.dp, gapLength = flowGap.dp)
                } else {
                    base
                }
            },
        )

        // 右上角控件：重新扫描（强制刷新数据）+ 缩放（放大 / 缩小 / 适应视图 / 重置）。
        TopologyZoomControls(
            onRescan = onRescan,
            onZoomIn = { viewerState.zoomIn() },
            onZoomOut = { viewerState.zoomOut() },
            onFitContent = { viewerState.centerGraph(animated = true) },
            onReset = {
                viewerState.clearManualPositions()
                viewerState.centerGraph(animated = true)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        )

        propertiesDialog?.let { dialogState ->
            MqObjectPropertiesDialog(
                state = dialogState,
                onDismiss = { propertiesDialog = null },
            )
        }

        selectedId?.let { id ->
            val type = nodeTypes[id] ?: return@let
            NodeDetailCard(
                name = id.removePrefix(REMOTE_QMGR_PREFIX),
                type = type,
                severity = severities[id] ?: NodeSeverity.NORMAL,
                onDismiss = { select(null) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
            )
        }
    }
}

/** 选中节点的详情浮层（点击画布空白或关闭按钮消失）；告警节点图标同步换警告色。 */
@Composable
private fun NodeDetailCard(
    name: String,
    type: TopologyNodeType,
    severity: NodeSeverity,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (severity) {
        NodeSeverity.ERROR -> MaterialTheme.colorScheme.error
        NodeSeverity.WARNING -> Color(0xFFED6C02)
        NodeSeverity.NORMAL -> type.getColor()
    }
    Surface(
        modifier = modifier.width(300.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = type.getIcon(),
                contentDescription = type.name,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = type.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource("topology_detail_close"),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 环（BACK 边）的固定语义色：橙色（需求指定，异常信号不随主题翻转）。 */
private val CYCLE_EDGE_COLOR = Color(0xFFFF9800)

/** 节点双击判定窗口（kuiver 0.4.2 无 onNodeDoubleClick 回调）。 */
private const val DOUBLE_CLICK_WINDOW_MS = 400

/** 选中链路高亮集：闭包节点 id + 闭包内边（fromId to id）。 */
private data class Highlight(val nodes: Set<String>, val edges: Set<Pair<String, String>>)

/** 高亮边流动动画：虚线段长（dp）；gap 从它循环递增到 3 倍即位移满一个周期（无缝）。 */
private const val FLOW_DASH = 10f

/** 流动动画一个完整位移周期的时长（ms）。 */
private const val FLOW_PERIOD_MS = 700
