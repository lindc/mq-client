package com.mqclient.ibmmq.ui.topology

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
import com.mqclient.core.CoreModule
import com.mqclient.core.topology.TopologyFocusHolder
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.event.IbmMQEvents
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
    var data by remember(connectionId) {
        mutableStateOf<Pair<Kuiver, Map<String, TopologyNodeType>>?>(null)
    }
    // 至少完成过一轮慢扫：区分「还在连 / 扫」与「扫完了但确实没有业务对象」。
    var scannedOnce by remember(connectionId) { mutableStateOf(false) }
    // 列表页带入的焦点对象名（consume 一次，防重复应用）。
    val focusName = remember(connectionId) { TopologyFocusHolder.consume(connectionId)?.rootName }

    /** 从慢扫缓存重建；**空图不落地**——保持加载态或保留旧图（刷新保留旧数据的语义）。 */
    fun rebuild() {
        val next = buildKuiverFromCache(connectionId)
        if (next.first.nodes.isNotEmpty()) data = next
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
                    text = "正在连接并扫描……",
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
                text = "暂无拓扑数据（该连接下没有可展示的业务对象）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    MqTopologyCanvas(
        data = current,
        focusName = focusName,
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
    data: Pair<Kuiver, Map<String, TopologyNodeType>>,
    focusName: String?,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (kuiver, nodeTypes) = data
    val scheme = MaterialTheme.colorScheme

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
                    onNodeClick = { node -> select(node.id) },
                    onCanvasClick = { select(null) },
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
                )
            },
            // 贝塞尔曲线边（EdgeShape.CURVED，见 CurvedEdgeContent.kt 的取舍说明）：
            // 正常边主题灰，环（BACK）橙色——固定语义色，不随主题翻转。
            edgeStyle = { edge ->
                topologyEdgeStyle(
                    edge = edge,
                    normalColor = scheme.outlineVariant.copy(alpha = 0.6f),
                    cycleColor = CYCLE_EDGE_COLOR.copy(alpha = 0.8f),
                )
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

        selectedId?.let { id ->
            val type = nodeTypes[id] ?: return@let
            NodeDetailCard(
                name = id.removePrefix(REMOTE_QMGR_PREFIX),
                type = type,
                onDismiss = { selectedId = null },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
            )
        }
    }
}

/** 选中节点的详情浮层（点击画布空白或关闭按钮消失）。 */
@Composable
private fun NodeDetailCard(
    name: String,
    type: TopologyNodeType,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                tint = type.getColor(),
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
                    contentDescription = "关闭",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 环（BACK 边）的固定语义色：橙色（需求指定，异常信号不随主题翻转）。 */
private val CYCLE_EDGE_COLOR = Color(0xFFFF9800)
