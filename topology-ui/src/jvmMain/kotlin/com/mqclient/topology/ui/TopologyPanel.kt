package com.mqclient.topology.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.KuiverViewerState
import com.dk.kuiver.SelectionMode
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import com.dk.kuiver.model.buildKuiver
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.model.layout.LayoutProvider
import com.dk.kuiver.rememberKuiverViewerState
import kotlin.comparisons.compareBy
import com.dk.kuiver.renderer.KuiverInteractionCallbacks
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.dk.kuiver.ui.EdgeStyle
import com.dk.kuiver.ui.EdgeShape
import com.mqclient.core.ConnectionStore
import com.mqclient.core.topology.TopologyProviderRegistry
import kotlinx.coroutines.flow.filter
import com.mqclient.graph.topology.EdgeStyle as GraphEdgeStyle
import com.mqclient.graph.topology.NodeColor
import com.mqclient.graph.topology.NodeStatus
import com.mqclient.graph.topology.ObjectRef
import com.mqclient.graph.topology.TopologyGraph
import com.mqclient.graph.topology.TopologyNode
import com.mqclient.core.topology.TopologyFocusHolder
import kotlin.collections.ArrayDeque
import kotlin.math.abs

/**
 * 通用拓扑图渲染面板（与中间件无关）。
 *
 * 调用关系：**宿主路由 → 模块适配器 `contentFor("topology")` 委托 → 共享 [TopologyPanel] →
 * 经 [TopologyProviderRegistry] 按连接的 serviceId 取 provider → `buildGraph` 出通用
 * [TopologyGraph] → 映射成 kuiver 图模型（[KuiverNode]/[KuiverEdge]）交给 `KuiverViewer`
 * 渲染（自定义聚类分层布局 / 缩放 / 平移 / 节点拖拽均由 kuiver 承担）→ 图标 / 颜色经
 * [TopologyStyleRegistry] 按 `(moduleId, type)` 解析 → 点击节点选中并触发 [onNodeClick]
 * （[ObjectRef]）→ 宿主据此跳回对象面板。**
 *
 * 本面板只依赖通用契约（TopologyProvider / graph-core 类型）与各中间件的样式注册表，
 * **不直接引用任何具体中间件 ui**，从而新增一种中间件无需改动本文件。
 *
 * [rootName] / [rootType] 为空时，provider 自行决定根（IBM MQ 走「全量局部链路」模式，
 * 返回整张单机图）；给定则按该对象 BFS 展开 [depth] 层。这些外部参数仅作为初始值，
 * 面板内可通过「聚焦 / 跳数」控件覆盖。
 */
@Composable
fun TopologyPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
    rootName: String = "",
    rootType: String = "",
    depth: Int = 1,
    /** 节点点击回调；不传则只做本地选中高亮 + 底部详情卡。宿主可据此跳回对象面板。 */
    onNodeClick: ((ObjectRef) -> Unit)? = null,
) {
    var graph by remember(connectionId) { mutableStateOf<TopologyGraph?>(null) }
    var message by remember(connectionId) { mutableStateOf<String?>(null) }
    // 扫描尚未产出过数据前，保持「连接 + 扫描中」加载态，而不是提前显示空态
    // （与对象列表页一致：未连接时 scanObjects no-op，等隐式连接后的扫描任务翻出数据）。
    var scannedOnce by remember(connectionId) { mutableStateOf(false) }

    // ===== 视图内可控的焦点 / 跳数（覆盖外部 rootName/rootType/depth）=====
    // uiDepth < 0 表示「全部」（全量单机图，等同无 root）。
    // 列表页「在拓扑图中查看」会把焦点暂存进 TopologyFocusHolder，面板打开时取走一次；
    // 这样从无 root 的「拓扑图」入口进入默认只展开 1 跳，避免一上来就全量过密。
    val initialFocus = remember(connectionId) { TopologyFocusHolder.consume(connectionId) }
    var focusName by remember(connectionId) { mutableStateOf(initialFocus?.rootName ?: rootName) }
    var focusType by remember(connectionId) { mutableStateOf(initialFocus?.rootType ?: rootType) }
    var uiDepth by remember(connectionId) {
        mutableStateOf(initialFocus?.let { 1 } ?: if (depth <= 0) -1 else depth)
    }

    /** 取 provider 并重建图；返回是否成功拿到 provider。 */
    suspend fun load(): Boolean {
        val moduleId = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.serviceId ?: return false
        val provider = TopologyProviderRegistry[moduleId] ?: return false
        val effectiveDepth = if (uiDepth < 0) Int.MAX_VALUE else uiDepth
        return runCatching { provider.buildGraph(connectionId, focusName, focusType, effectiveDepth) }
            .onSuccess { graph = it }
            .onFailure { message = it.message ?: "拓扑构建失败" }
            .isSuccess
    }

    LaunchedEffect(connectionId, focusName, focusType, uiDepth) {
        message = null
        graph = null
        scannedOnce = false
        val moduleId = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.serviceId
        val provider = moduleId?.let { TopologyProviderRegistry[it] }
        if (provider == null) {
            message = "该连接暂无拓扑提供方"
            return@LaunchedEffect
        }
        // 触发一次扫描（未连接则 no-op，由连接后的定时扫描任务接管并产出数据）；
        // 扫描完成后经 provider.dataChanged 通知下方重建。
        runCatching { provider.refresh(connectionId) }
        load()
    }

    // 订阅数据变化：扫描完成后重建图并标记已扫描，结束加载态。
    LaunchedEffect(connectionId) {
        val moduleId = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.serviceId ?: return@LaunchedEffect
        val provider = TopologyProviderRegistry[moduleId] ?: return@LaunchedEffect
        provider.dataChanged
            .filter { it == connectionId }
            .collect {
                load()
                scannedOnce = true
            }
    }

    when {
        message != null -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = message ?: "加载失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        graph == null || (graph!!.nodes.isEmpty() && !scannedOnce) -> {
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
        }
        graph!!.nodes.isEmpty() -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "暂无拓扑数据（该连接下没有可展示的业务对象）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            val g = graph!!
            val moduleId = ConnectionStore.connections.value
                .firstOrNull { it.id == connectionId }?.serviceId ?: ""
            var selected by remember { mutableStateOf<TopologyNode?>(null) }

            val byName = remember(g) { g.nodes.associateBy { it.name } }
            // 主题色在组合作用域里取一次（Color 值），再作为普通值捕获进非组合 lambda，
            // 避免在非组合的 remember 闭包里调用 @Composable 的取色函数。
            val scheme = MaterialTheme.colorScheme

            // TopologyGraph → kuiver 图模型（映射逻辑抽成顶层函数便于单测）。
            // 同步把 name→type 暂存给聚类布局 provider 使用。
            val kuiverGraph = remember(g) {
                clusteredTypeLookup = g.nodes.associate { it.name to it.type }
                g.toKuiver()
            }

            // viewer state 不按 g 重建（保留缩放 / 平移 / 手工拖拽位置），
            // 图数据变化经 updateKuiver 换图并按 policy 重排。
            // 布局改用自定义「聚类分层」：同层内按类型聚拢成块、层间按流向排开，
            // 既不画背景框（kuiver 0.4.2 无 cluster API）也能形成类型区域，且边始终按
            // from/to 直连，绝不会断裂。
            val viewerState = rememberKuiverViewerState(
                initialKuiver = Kuiver(),
                layoutConfig = LayoutConfig.Custom(provider = clusteredLayoutProvider),
            )

            // 每次换图后重新「适应视图」（g 变化 → kuiverGraph 变化 → 这里置位）；
            // 用户手动缩放 / 拖拽不会置位 pendingFit，因此不会被自动 fit 覆盖。
            var pendingFit by remember { mutableStateOf(false) }
            // updateKuiver 只换图数据不触发布局（实测字节码），必须跟一次 relayout()
            // 递增 layoutGeneration，让自定义布局真正吃进新图。
            LaunchedEffect(kuiverGraph) {
                viewerState.updateKuiver(kuiverGraph)
                viewerState.relayout()
                pendingFit = true
            }
            // 布局落定（layoutedKuiver 更新）且画布已测量后执行一次 fit；
            // 仅当本次是「换图」触发的 pendingFit，手动拖拽导致 layoutedKuiver 变化时不会误触发。
            LaunchedEffect(viewerState.layoutedKuiver, viewerState.canvasWidth) {
                if (pendingFit && viewerState.canvasWidth > 0.dp &&
                    viewerState.layoutedKuiver.nodes.isNotEmpty()
                ) {
                    viewerState.centerGraph(animated = true)
                    pendingFit = false
                }
            }

            // 边样式：按 from/to 查回原 TopologyEdge（虚线）与两端语义色（颜色）。
            // 注意 graph-core 也有个 EdgeStyle（DASHED 枚举），这里 import 别名区分。
            val edgeMeta = remember(g) { g.edges.associateBy { it.from to it.to } }
            val edgeStyleResolver = remember(edgeMeta, byName, moduleId, scheme) {
                { edge: KuiverEdge ->
                    val from = byName[edge.fromId]
                    val to = byName[edge.toId]
                    val dashed = edgeMeta[edge.fromId to edge.toId]?.style == GraphEdgeStyle.DASHED
                    // 跨列（列差 ≥ 2，即真正「横穿多列」的长边）走正交 S 形路由：两端水平切出 /
                    // 切入，中段贴着源/目标所在的行间隙走，避免直线斜穿中间列的卡片；
                    // 订阅（虚线）长边再额外淡化，降低视觉侵入感（#4）。
                    val fromCol = clusteredColumnLookup[edge.fromId]
                    val toCol = clusteredColumnLookup[edge.toId]
                    val crossSpan = if (fromCol != null && toCol != null) abs(fromCol - toCol) else 0
                    val crossColumn = crossSpan >= 2
                    val baseColor = resolveEdgeColor(from, to, moduleId, scheme)
                    val color = if (dashed && crossColumn) baseColor.copy(alpha = 0.4f) else baseColor
                    EdgeStyle(
                        color = color,
                        strokeWidth = if (crossColumn) 1.2.dp else 1.5.dp,
                        showArrow = true,
                        dashed = dashed,
                        shape = if (crossColumn) EdgeShape.ORTHOGONAL else EdgeShape.STRAIGHT,
                    )
                }
            }

            val callbacks = remember(byName, onNodeClick) {
                KuiverInteractionCallbacks(
                    onNodeClick = { node ->
                        val tn = byName[node.id]
                        if (tn != null) {
                            selected = tn
                            tn.objectRef?.let { onNodeClick?.invoke(it) }
                        }
                    },
                    onCanvasClick = { selected = null },
                )
            }

            Box(modifier.fillMaxSize()) {
                KuiverViewer(
                    state = viewerState,
                    modifier = Modifier.fillMaxSize(),
                    config = KuiverViewerConfig(
                        selectionMode = SelectionMode.SINGLE,
                        nodeDragEnabled = true,
                        // 关闭引擎内置的一次性自动 fit：换图 / 重载的 fit 全部由本面板的
                        // pendingFit 逻辑统一驱动，避免重复或抢在手动缩放之前。
                        fitToContent = false,
                        // 可读性下限：图过大时宁可溢出画布让用户滚动，也不把文字压到看不清。
                        // 从 0.6 提到 0.85，让用户看得更舒服（大图多滚动即可）。
                        minScale = 0.85f,
                        // 适应视图时留 10% 边距。
                        contentPadding = 0.9f,
                    ),
                    callbacks = callbacks,
                    nodeContent = { node ->
                        val tn = byName[node.id]
                        if (tn != null) {
                            NodeCard(
                                node = tn,
                                moduleId = moduleId,
                                selected = isSelected,
                                hovered = isHovered,
                            )
                        }
                    },
                    edgeStyle = edgeStyleResolver,
                )

                // 左上：跳数 / 聚焦控制（过滤与折叠）。
                DepthFocusControls(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    focusActive = focusName.isNotBlank(),
                    uiDepth = uiDepth,
                    onDepthChange = { uiDepth = it },
                    onFocusClear = { focusName = ""; focusType = "" },
                )

                // 右上：视图控制（适应 / 放大 / 缩小 / 重置）。
                ViewControls(
                    state = viewerState,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                )

                selected?.let { node ->
                    NodeDetailCard(
                        node = node,
                        moduleId = moduleId,
                        onFocus = if (focusName != node.name) {
                            { focusName = node.name; focusType = node.type; if (uiDepth < 0) uiDepth = 2 }
                        } else null,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 类型查询表（节点名 → 类型判别符）。布局 provider 只看到 kuiver 的通用节点（无 type 字段），
 * 聚类分组需要类型，故在每次重建 kuiver 图时把当前图的 name→type 暂存到这里供 provider 读取。
 * 单面板桌面场景下足够；provider 在 updateKuiver 之后的布局阶段读取，时序上已是最新。
 */
private var clusteredTypeLookup: Map<String, String> = emptyMap()

/**
 * 节点名 → 所在列（角色列）序号。布局 provider 算完列后写入，供边样式解析器判断一条边是否「跨列」，
 * 从而决定是否走正交路由 / 淡化。与 [clusteredTypeLookup] 同时序（relayout 之后）填充。
 */
private var clusteredColumnLookup: Map<String, Int> = emptyMap()

/**
 * 自定义「按角色分列」布局（[LayoutConfig.Custom] 的 [LayoutProvider]）。
 *
 * 思路：先按有向图最长路径算出每个节点的层级（Kahn 拓扑序，环上节点收尾到尾列），
 * 再按**每个类型的平均层级**决定列序——平均层级小（靠上游）的类型靠左、靠下游的靠右，
 * 于是每种角色各占一列（如 alias / local / xmitq / channel 各一列），边基本只在前一列↔后
 * 一列之间走，交叉大幅减少，呈「管线式」架构图。列内节点按名称排布，并再用重心法做几轮
 * 重排以进一步降低跨列交叉。
 *
 * 列序由图本身的流向推导，**不硬编码任何中间件的类型**，因此保持中间件无关；
 * 若某中间件想要固定的角色顺序（如 Alias→Local→XMITQ→Channel），后续可在样式注册表里
 * 加一个「类型→列序」映射来覆盖。
 * 节点坐标由本函数直接写入，边仍由 kuiver 按 from/to 渲染，分组绝不会切断或指错边。
 */
private val clusteredLayoutProvider: LayoutProvider = labeled@{ kuiver, config ->
    val nodes = kuiver.nodes.values.toList()
    if (nodes.isEmpty()) return@labeled kuiver

    // 有向邻接表。
    val children = mutableMapOf<String, MutableList<String>>()
    val parents = mutableMapOf<String, MutableList<String>>()
    kuiver.edges.forEach { e ->
        children.getOrPut(e.fromId) { mutableListOf() }.add(e.toId)
        parents.getOrPut(e.toId) { mutableListOf() }.add(e.fromId)
    }

    // 最长路径分层：Kahn 拓扑序推进 level，环上残留节点（入度仍 >0）收尾到尾列。
    val level = mutableMapOf<String, Int>()
    val inDeg = mutableMapOf<String, Int>()
    nodes.forEach { level[it.id] = 0; inDeg[it.id] = parents[it.id]?.size ?: 0 }
    val queue = ArrayDeque<String>().apply {
        inDeg.filter { it.value == 0 }.forEach { add(it.key) }
    }
    var maxLevel = 0
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        val next = level.getValue(id) + 1
        children[id]?.forEach { ch ->
            if (next > level.getValue(ch)) level[ch] = next
            if (next > maxLevel) maxLevel = next
            val d = inDeg.getValue(ch) - 1
            inDeg[ch] = d
            if (d == 0) queue.addLast(ch)
        }
    }
    if (nodes.any { (inDeg[it.id] ?: 0) > 0 }) {
        nodes.forEach { if ((inDeg[it.id] ?: 0) > 0) level[it.id] = maxLevel + 1 }
    }

    // 每个类型的平均层级 → 列序（上游靠左、下游靠右），并列时按类型名稳定排序。
    val typeColumn = nodes
        .groupBy { clusteredTypeLookup[it.id] ?: "" }
        .mapValues { (_, ns) -> ns.map { level.getValue(it.id) }.average() }
        .entries
        .sortedWith(compareBy({ it.value }, { it.key }))
        .mapIndexed { idx, e -> e.key to idx }
        .toMap()

    // 按列分组；列内先按名称给出初始顺序。
    val byColumn = nodes
        .groupBy { typeColumn[clusteredTypeLookup[it.id] ?: ""] ?: 0 }
        .toSortedMap()
    val columnCount = (byColumn.keys.maxOrNull() ?: 0) + 1
    val totalWidth = CLUSTER_LEVEL_SPACING * (columnCount - 1)
    val centerX0 = if (config.width > 0.dp) (config.width - totalWidth) / 2f else 0.dp

    // 列内节点列表（可变，供重心法重排）。初始按名称稳定排序。
    val columnOrder = byColumn.mapValues { (_, ns) -> ns.sortedBy { it.id }.toMutableList() }
    val columns = columnOrder.keys.sorted()

    // Sugiyama 重心法：交替「下扫 / 上扫」，每轮只按**相邻列**对端的当前 y 平均值重排本列，
    // 让同列内彼此相连（或被同一对端连接）的节点在纵向上对齐，跨列边尽量水平、不再大斜。
    // 多轮松弛（~10 轮）收敛后，纵向间距被相邻列关系「咬合」，消灭因初始按名排序造成的错位。
    val yOf = mutableMapOf<String, Float>()
    fun recomputeColumn(col: Int) {
        var y = 0f
        columnOrder[col]?.forEach { n ->
            yOf[n.id] = y
            y += ((n.dimensions?.height ?: CARD_H.dp).value) + CLUSTER_NODE_SPACING.value
        }
    }
    columns.forEach { recomputeColumn(it) }
    repeat(10) { iter ->
        val useRight = iter % 2 == 0
        // 下扫：从左到右，按「下游（children）」对端 y 平均排序；上扫反之按「上游（parents）」。
        val order = if (useRight) columns else columns.asReversed()
        order.forEach { col ->
            val list = columnOrder[col] ?: return@forEach
            list.sortBy { node ->
                val nbrs = if (useRight) children[node.id] else parents[node.id]
                val ys = nbrs?.mapNotNull { yOf[it] } ?: emptyList()
                if (ys.isEmpty()) yOf[node.id] ?: 0f else ys.average().toFloat()
            }
            recomputeColumn(col)
        }
    }

    // 列内按最终顺序落 y，列 x 由列序决定。
    val positions = mutableMapOf<String, DpOffset>()
    // 把「节点名 → 列序号」暂存给边样式解析器（判断跨列用），时序上此刻列已最终定型。
    clusteredColumnLookup = columnOrder.flatMap { (col, list) -> list.map { it.id to col } }.toMap()
    columnOrder.forEach { (col, list) ->
        val x = CLUSTER_LEVEL_SPACING * col + centerX0
        var y = 0f
        list.forEach { n ->
            positions[n.id] = DpOffset(x, Dp(y))
            y += ((n.dimensions?.height ?: CARD_H.dp).value) + CLUSTER_NODE_SPACING.value
        }
    }

    val laidNodes = nodes.map { it.copy(position = positions.getValue(it.id)) }
    // 重新分类边（public API），保持与 kuiver 内部一致；位置信息随节点带入。
    val tmp = buildKuiver {
        laidNodes.forEach { addNode(it) }
        kuiver.edges.forEach { addEdge(it) }
    }
    val cls = tmp.classifyAllEdges()
    buildKuiver {
        laidNodes.forEach { addNode(it) }
        cls.forEach { (edge, type) -> addEdge(edge.copy(type = type)) }
    }
}

/** 单个节点的卡片（由 kuiver 定位摆放；选中 / 悬停态来自 KuiverNodeScope）。 */
@Composable
private fun NodeCard(
    node: TopologyNode,
    moduleId: String,
    selected: Boolean,
    hovered: Boolean,
) {
    val style = TopologyStyleRegistry.styleFor(moduleId, node.type) ?: TopologyStyleRegistry.fallback
    val accent = nodeAccent(node, style, MaterialTheme.colorScheme)
    val statusColor = node.status?.let { statusColor(it) }

    Column(
        Modifier
            .width(CARD_W.dp)
            .height(CARD_H.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = when {
                    selected -> MaterialTheme.colorScheme.primary
                    hovered -> accent
                    else -> accent.copy(alpha = 0.6f)
                },
                shape = RoundedCornerShape(10.dp),
            ),
    ) {
        // 类型色条：配合「按类型聚簇」的视觉标识，零边风险。
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(accent),
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = accent.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(
                        imageVector = style.icon,
                        contentDescription = node.type,
                        tint = accent,
                    )
                }
            }
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (statusColor != null) {
                        Box(
                            Modifier
                                .padding(start = 6.dp)
                                .size(8.dp)
                                .background(statusColor, RoundedCornerShape(4.dp)),
                        )
                    }
                }
                val badge = node.badge
                if (badge != null) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 选中节点的详情卡（底部浮层），并提供「聚焦此节点」操作。 */
@Composable
private fun NodeDetailCard(
    node: TopologyNode,
    moduleId: String,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
) {
    val style = TopologyStyleRegistry.styleFor(moduleId, node.type) ?: TopologyStyleRegistry.fallback
    Surface(
        modifier = modifier.width(300.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(
                    imageVector = style.icon,
                    contentDescription = node.type,
                    tint = nodeAccent(node, style, MaterialTheme.colorScheme),
                )
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f, fill = false),
                )
                if (onFocus != null) {
                    androidx.compose.material3.IconButton(
                        onClick = onFocus,
                        modifier = Modifier.size(28.dp),
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Filled.FitScreen,
                            contentDescription = "以此为中心展开",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Text(
                text = "type: ${node.type}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (node.status != null) {
                Text(
                    text = "status: ${node.status}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (node.properties.isNotEmpty()) {
                node.properties.forEach { (k, v) ->
                    Text(
                        text = "$k: $v",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 右上角视图控制：适应视图 / 放大 / 缩小 / 重置。样式对齐项目既有 IconButton + Tooltip。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewControls(
    state: KuiverViewerState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        ControlFab(Icons.Filled.FitScreen, "适应视图") { state.centerGraph(animated = true) }
        ControlFab(Icons.Filled.Add, "放大") { state.zoomIn() }
        ControlFab(Icons.Filled.Remove, "缩小") { state.zoomOut() }
        ControlFab(Icons.Filled.RestartAlt, "重置") {
            state.clearManualPositions()
            state.centerGraph(animated = true)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlFab(icon: ImageVector, desc: String, onClick: () -> Unit) {
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = desc,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = desc, Modifier.size(20.dp))
        }
    }
}

/** 左上角过滤 / 折叠控制：跳数（1/2/3/全部）与「返回全图」。 */
@Composable
private fun DepthFocusControls(
    focusActive: Boolean,
    uiDepth: Int,
    onDepthChange: (Int) -> Unit,
    onFocusClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val options = listOf(1 to "1跳", 2 to "2跳", 3 to "3跳", -1 to "全部")
            options.forEach { (value, label) ->
                val selected = uiDepth == value
                TextButton(
                    onClick = {
                        onDepthChange(value)
                        if (value != -1) onFocusClear() // 选定具体跳数时回到全图视角
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (focusActive) {
                TextButton(
                    onClick = onFocusClear,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    Text(
                        text = "返回全图",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ===== 常量与颜色解析 =====

private const val CARD_W = 180
private const val CARD_H = 64

// 自定义「按角色分列」布局的间距（直接生效，不经 kuiver 内部 clearance 下限）。
private val CLUSTER_LEVEL_SPACING = 340.dp // 列（角色）间距，需 > 卡片宽 180，留足横向空隙
// 同列内节点纵向间距。刻意拉到明显大于卡片高度（64），让相邻列之间留出足够大的行间隙，
// 相邻列的近水平边能从两行卡片**之间**穿过而非压在卡片上（缓解「边穿节点」的观感）。
// 注：这是直线边（kuiver 无路由/折线 API）下唯一可行的缓解手段；跨多列的回边/长边仍可能
// 落在中间列的卡片上，属已知限制（见后续优化 #6）。
private val CLUSTER_NODE_SPACING = 210.dp

/**
 * [TopologyGraph] → kuiver 图模型：节点 id 就是对象名；尺寸给固定卡片尺寸
 * （节点内容插槽里的 [NodeCard] 与之对齐），边按 from/to 直连。
 */
internal fun TopologyGraph.toKuiver(): Kuiver = buildKuiver {
    nodes.forEach { node -> addNode(KuiverNode(node.name, NodeDimensions(CARD_W.dp, CARD_H.dp))) }
    edges.forEach { edge -> addEdge(KuiverEdge(fromId = edge.from, toId = edge.to)) }
}

/** 节点强调色：语义 [NodeColor]（非 NORMAL）优先用主题色，否则用该类型的注册强调色。 */
private fun nodeAccent(node: TopologyNode, style: TopologyStyle, scheme: androidx.compose.material3.ColorScheme): Color = when (node.color) {
    NodeColor.PRIMARY -> scheme.primary
    NodeColor.WARNING -> scheme.error.copy(alpha = 0.8f)
    NodeColor.ERROR -> scheme.error
    NodeColor.MUTED -> scheme.outline
    else -> style.accent
}

/**
 * 边颜色：优先看两端**状态**——命中 RETRYING→琥珀、STOPPED→红（异常链路高亮，
 * 这是用户最该关注的故障面）；否则仍沿用「端点带语义色则跟随」的逻辑，最后回落中性低透明灰。
 *
 * 注意：实线/虚线由 graph-core 的 [GraphEdgeStyle] 决定（消息投递=实线、订阅关系=虚线），
 * 颜色与线型各管各的，互不影响。
 */
private fun resolveEdgeColor(
    fromNode: TopologyNode?,
    toNode: TopologyNode?,
    moduleId: String,
    scheme: androidx.compose.material3.ColorScheme,
): Color {
    val badStatus = listOf(fromNode?.status, toNode?.status).firstOrNull {
        it == NodeStatus.RETRYING || it == NodeStatus.STOPPED
    }
    if (badStatus != null) {
        // 异常链路高亮，透明度拉高以便一眼定位故障通道。
        return statusColor(badStatus).copy(alpha = 0.9f)
    }
    if (fromNode != null && fromNode.color != NodeColor.NORMAL) {
        return nodeAccent(fromNode, TopologyStyleRegistry.styleFor(moduleId, fromNode.type) ?: TopologyStyleRegistry.fallback, scheme)
    }
    if (toNode != null && toNode.color != NodeColor.NORMAL) {
        return nodeAccent(toNode, TopologyStyleRegistry.styleFor(moduleId, toNode.type) ?: TopologyStyleRegistry.fallback, scheme)
    }
    return scheme.outlineVariant.copy(alpha = 0.55f)
}

/** 状态点的固定语义色（状态属于客观事实，不随主题翻转）。 */
private fun statusColor(status: NodeStatus): Color = when (status) {
    NodeStatus.RUNNING -> Color(0xFF2E7D32)
    NodeStatus.STOPPED -> Color(0xFFC62828)
    NodeStatus.RETRYING -> Color(0xFFED6C02)
    NodeStatus.STARTING, NodeStatus.STOPPING -> Color(0xFFF9A825)
    else -> Color(0xFF757575)
}
