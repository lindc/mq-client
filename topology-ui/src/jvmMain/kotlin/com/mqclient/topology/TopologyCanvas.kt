package com.mqclient.topology

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dk.kuiver.SelectionMode
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.rememberKuiverViewerState
import com.dk.kuiver.renderer.KuiverInteractionCallbacks
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.dk.kuiver.ui.EdgeStyle
import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyData
import com.mqclient.graph.TopologyNodeType
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.file.saveFileBytes
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 拓扑图通用画布：kuiver viewer 组装 + 选中链路高亮 + 高亮边流动动画 + 双击检测 +
 * 右上角缩放控件 + 底部详情卡。原为 IBM MQ 面板私有的 `MqTopologyCanvas`，泛化后
 * 各中间件拓扑面板共用；面板只提供数据（[TopologyData]）、布局（[layoutProvider]）
 * 与业务行为（双击回调 / 重扫回调），交互视觉全部由此封装。
 *
 * 参数说明：
 *  - [displayName]：节点 id → 卡片显示名（MQ 的 REMOTE_QMGR 虚拟节点要剥 `rqmgr:` 前缀，
 *    Kafka 等大多数场景传恒等即可）；
 *  - [fallbackType]：类型表缺失时的兜底类型（builder 保证齐全，防御性取值）；
 *  - [onNodeDoubleClick]：双击节点回调（kuiver 0.4.2 无原生双击，内部用
 *    [NodeDoubleClick] 检测）；画布空白点击会自动打断，业务侧无需处理；
 *  - [layoutProvider]：带类型语义的布局（[TopologyLayoutProvider]）。类型表由本
 *    组件按图捕获进 provider 闭包，面板**不需要**再维护任何模块级查找表；
 *  - [snackbarHostState]：非空时在画布底部中央挂 SnackbarHost（双击无属性节点等
 *    业务提示由面板自己 show）。
 *
 * 图数据变化经 updateKuiver + relayout 换入（updateKuiver 不触发布局，必须跟一次
 * relayout）；viewer state 只建一次，缩放 / 平移在重建间保留。布局落定且画布已测量
 * 后执行一次「适应视图」；[focusName]（列表页焦点带入）在首次出图后选中。
 * 画布超出视口时由 [TopologyScrollbars] 提供覆盖式滚动条（拖 thumb 平移）。
 */
@Composable
fun TopologyCanvas(
    data: TopologyData,
    layoutProvider: TopologyLayoutProvider,
    onRescan: () -> Unit,
    onNodeDoubleClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    displayName: (String) -> String = { it },
    fallbackType: TopologyNodeType,
    focusName: String? = null,
    onFocusApplied: () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    /**
     * 按边定制样式（如 Kafka 的副本关系虚线边）：在基础曲线边样式之上做修改，
     * 高亮流动动画仍会叠加在定制结果之上。null 表示全部走统一样式（IBM MQ）。
     */
    edgeCustomize: ((edge: KuiverEdge, base: EdgeStyle) -> EdgeStyle)? = null,
    minScale: Float = 0.2f,
    maxScale: Float = 2.0f,
    /** 节点可拖拽（kuiver 原生手势，touch slop 区分点击；位置仅会话内有效，不持久化）。 */
    nodeDragEnabled: Boolean = false,
) {
    val (kuiver, nodeTypes, severities) = data
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes

    // 双击检测器是 remember 的稳定实例，回调里读业务状态必须经 rememberUpdatedState 的
    // **最新**回调——否则图重建后新增节点的双击可能命中旧闭包。
    val currentDoubleClick by rememberUpdatedState(onNodeDoubleClick)
    val nodeDoubleClicked = remember {
        NodeDoubleClick { id -> currentDoubleClick(id) }
    }

    // 类型表随图捕获进 provider 闭包（见 [TopologyLayoutProvider]）：布局在 kuiver 的
    // 后台线程执行、由 resize 等事件重跑，读到的都是**这张图**的不可变快照，不再是
    // 「模块级变量最后一次写入的值」。layoutConfig 按 (图, 类型表) remember，换图时
    // 身份变化会让 kuiver 重新走一次布局。
    val layoutConfig = remember(kuiver, nodeTypes) {
        LayoutConfig.Custom(
            provider = { graph, config -> layoutProvider(graph, config, nodeTypes) },
        )
    }

    val viewerState = rememberKuiverViewerState(
        initialKuiver = Kuiver(),
        layoutConfig = layoutConfig,
    )

    var pendingFit by remember { mutableStateOf(false) }
    LaunchedEffect(kuiver) {
        viewerState.updateKuiver(kuiver)
        viewerState.relayout()
        pendingFit = true
    }

    var selectedId by remember { mutableStateOf<String?>(null) }

    // ---- 「导出为图片」：离屏渲染整图，不截当前视口 ----
    // 导出反馈必须有落点：RocketMQ 等面板没传 snackbarHostState，这里兜底一个本地 host。
    // fallback 无条件 remember，避免 snackbarHostState 由 null ↔ 非 null 时破坏槽位。
    val fallbackHostState = remember { SnackbarHostState() }
    val exportHostState = snackbarHostState ?: fallbackHostState
    val exportScope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val exportSuccessTemplate = stringResource("topology_export_success")
    val exportFailedTemplate = stringResource("topology_export_failed")
    val exportEmpty = stringResource("topology_export_empty")
    val exportFileStamp = remember { DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss") }

    fun exportImage() {
        if (exporting) return
        // 用 layoutedKuiver：布局落定 + 用户拖动后的手动位置都已在里面。
        val graph = viewerState.layoutedKuiver
        if (graph.nodes.isEmpty()) {
            exportScope.launch { exportHostState.showSnackbar(exportEmpty) }
            return
        }
        exporting = true
        exportScope.launch {
            // showSnackbar 会挂起到提示消失；先收尾 exporting 再弹，否则导出按钮会被
            // 一条 4 秒的提示连带禁用 4 秒。
            var message: String? = null
            try {
                // 离屏场景是自包含的（独立 recomposer + Skia surface），放到后台线程渲染，
                // 避免大图把 UI 卡住；渲染完回 Main 再弹 FileKit 保存框。
                val image = withContext(Dispatchers.Default) {
                    renderTopologyPng(
                        graph = graph,
                        nodeTypes = nodeTypes,
                        severities = severities,
                        badges = data.badges,
                        fallbackType = fallbackType,
                        theme = TopologyExportTheme(scheme, typography, shapes),
                        displayName = displayName,
                        scale = TOPOLOGY_EXPORT_DEFAULT_SCALE,
                        background = Color.White,
                    )
                }
                val suggested = "topology-${LocalDateTime.now().format(exportFileStamp)}.png"
                val saved = saveFileBytes(suggested, "png", image.pngBytes)
                if (saved != null) {
                    message = exportSuccessTemplate.withArg(saved, image.widthPx, image.heightPx)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = exportFailedTemplate.withArg(e.message ?: e::class.simpleName ?: "unknown")
            } finally {
                exporting = false
            }
            message?.let { exportHostState.showSnackbar(it) }
        }
    }

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
    // 闭包内节点原样、相关边加粗提亮；闭包外节点压暗、无关边淡化——凸显链路。
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
    // （dash+gap=20dp）后图案无缝归零，观感即"虚线沿链路方向持续流动"。
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
    // 焦点节点（列表页「在拓扑图中查看」）在首次出图后选中：图内高亮 + 底部详情卡。
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
                // 节点拖拽由面板按需启用（Kafka / IBM MQ 拓扑均已开启）：拖动位置走
                // kuiver 的 manualPositions（KEEP_MANUAL，relayout 后保留），纯会话内
                // 内存态，不持久化；重置按钮 clearManualPositions 恢复默认布局。
                nodeDragEnabled = nodeDragEnabled,
                // 滚轮缩放 / 画布平移是 kuiver 内建手势（无开关），这里只夹量程：
                // minScale 看全景，maxScale 看细节。
                minScale = minScale,
                maxScale = maxScale,
                // fit 由本画布 pendingFit 统一驱动（首次加载即适应内容），不用引擎内置的一次性 fit。
                fitToContent = false,
                contentPadding = 0.9f,
            ),
            callbacks = remember {
                KuiverInteractionCallbacks(
                    onNodeClick = { node ->
                        nodeDoubleClicked.onClick(node.id)
                        select(node.id)
                    },
                    onCanvasClick = {
                        nodeDoubleClicked.reset()
                        select(null)
                    },
                )
            },
            nodeContent = { node ->
                val type = nodeTypes[node.id] ?: fallbackType
                // grab 光标提示可拖拽（拖拽启用与否由面板决定，卡片本身可点选，Hand 都成立）；
                // testTag 以节点 id 定位，供 Compose UI 测试（拖拽语义验证）。
                Box(
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .testTag("topology_node:${node.id}"),
                ) {
                    TopologyNodeCard(
                        name = displayName(node.id),
                        type = type,
                        badge = data.badges[node.id],
                        isHovered = isHovered,
                        isDragging = isDragging,
                        // 选中高亮双保险：kuiver interaction 的 isSelected（内部状态，插槽
                        // 作用域可靠失效）+ 本画布 selectedId（焦点选中的直接来源）。
                        isSelected = isSelected || node.id == selectedId,
                        // 告警覆盖类型色。
                        severity = severities[node.id] ?: NodeSeverity.NORMAL,
                        // 链路高亮模式：闭包外节点压暗，凸显选中线路。
                        dimmed = highlight != null && node.id !in highlight.nodes,
                    )
                }
            },
            // 贝塞尔曲线边（EdgeShape.CURVED，见 EdgeStyles.kt 的取舍说明）。
            // 链路高亮模式：闭包内边满透明度加粗 + 虚线流动动画，闭包外边淡到近隐。
            edgeStyle = { edge ->
                val inPath = highlight == null || (edge.fromId to edge.toId) in highlight.edges
                val normalAlpha = if (highlight == null) 0.6f else if (inPath) 0.95f else 0.08f
                val cycleAlpha = if (highlight == null) 0.8f else if (inPath) 1f else 0.1f
                var base = topologyEdgeStyle(
                    edge = edge,
                    normalColor = scheme.outlineVariant.copy(alpha = normalAlpha),
                    cycleColor = CYCLE_EDGE_COLOR.copy(alpha = cycleAlpha),
                    strokeWidth = if (highlight != null && inPath) 2.dp else 1.5.dp,
                )
                edgeCustomize?.let { base = it(edge, base) }
                if (highlight != null && inPath) {
                    base.copy(dashed = true, dashLength = FLOW_DASH.dp, gapLength = flowGap.dp)
                } else {
                    base
                }
            },
        )

        // 覆盖式滚动条：图超出视口时显示，可拖拽平移（见 TopologyScrollbars）。
        TopologyScrollbars(viewerState)

        // 右上角控件：重新扫描（强制刷新数据）+ 缩放（放大 / 缩小 / 适应视图 / 重置）
        // + 导出整图为 PNG（离屏渲染，不受当前缩放 / 平移影响）。
        TopologyZoomControls(
            onRescan = onRescan,
            onZoomIn = { viewerState.zoomIn() },
            onZoomOut = { viewerState.zoomOut() },
            onFitContent = { viewerState.centerGraph(animated = true) },
            onReset = {
                viewerState.clearManualPositions()
                viewerState.centerGraph(animated = true)
            },
            onExport = ::exportImage,
            exportEnabled = !exporting,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        )

        ThemedSnackbarHost(
            hostState = exportHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        selectedId?.let { id ->
            val type = nodeTypes[id] ?: return@let
            TopologyNodeDetailCard(
                name = displayName(id),
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

/** 环（BACK 边）的固定语义色：橙色（需求指定，异常信号不随主题翻转）。 */
internal val CYCLE_EDGE_COLOR = Color(0xFFFF9800)

/** 选中链路高亮集：闭包节点 id + 闭包内边（fromId to id）。 */
private data class Highlight(val nodes: Set<String>, val edges: Set<Pair<String, String>>)

/** 高亮边流动动画：虚线段长（dp）；gap 从它循环递增到 3 倍即位移满一个周期（无缝）。 */
private const val FLOW_DASH = 10f

/** 流动动画一个完整位移周期的时长（ms）。 */
private const val FLOW_PERIOD_MS = 700
