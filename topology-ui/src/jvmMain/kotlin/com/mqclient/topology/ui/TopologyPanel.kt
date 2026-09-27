package com.mqclient.topology.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStore
import com.mqclient.core.topology.TopologyProviderRegistry
import kotlinx.coroutines.flow.filter
import com.mqclient.graph.topology.EdgeStyle
import com.mqclient.graph.topology.NodeColor
import com.mqclient.graph.topology.NodeStatus
import com.mqclient.graph.topology.ObjectRef
import com.mqclient.graph.topology.TopologyGraph
import com.mqclient.graph.topology.TopologyNode

/**
 * 通用拓扑图渲染面板（与中间件无关）。
 *
 * 调用关系：**宿主路由 → 模块适配器 `contentFor("topology")` 委托 → 共享 [TopologyPanel] →
 * 经 [TopologyProviderRegistry] 按连接的 serviceId 取 provider → `buildGraph` 出通用
 * [TopologyGraph] → 分层布局（Canvas 连线 + 节点卡）后，图标 / 颜色经 [TopologyStyleRegistry]
 * 按 `(moduleId, type)` 解析 → 点击节点选中并触发 [onNodeClick]（[ObjectRef]）→ 宿主据此跳回对象面板。**
 *
 * 本面板只依赖通用契约（TopologyProvider / graph-core 类型）与各中间件的样式注册表，
 * **不直接引用任何具体中间件 ui**，从而新增一种中间件无需改动本文件。
 *
 * [rootName] / [rootType] 为空时，provider 自行决定根（IBM MQ 走「全量局部链路」模式，
 * 返回整张单机图）；给定则按该对象 BFS 展开 [depth] 层。
 */
@Composable
fun TopologyPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
    rootName: String = "",
    rootType: String = "",
    depth: Int = 2,
    /** 节点点击回调；不传则只做本地选中高亮 + 底部详情卡。宿主可据此跳回对象面板。 */
    onNodeClick: ((ObjectRef) -> Unit)? = null,
) {
    val density = LocalDensity.current
    var graph by remember(connectionId) { mutableStateOf<TopologyGraph?>(null) }
    var message by remember(connectionId) { mutableStateOf<String?>(null) }
    // 扫描尚未产出过数据前，保持「连接 + 扫描中」加载态，而不是提前显示空态
    // （与对象列表页一致：未连接时 scanObjects no-op，等隐式连接后的扫描任务翻出数据）。
    var scannedOnce by remember(connectionId) { mutableStateOf(false) }

    /** 取 provider 并重建图；返回是否成功拿到 provider。 */
    suspend fun load(): Boolean {
        val moduleId = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.serviceId ?: return false
        val provider = TopologyProviderRegistry[moduleId] ?: return false
        return runCatching { provider.buildGraph(connectionId, rootName, rootType, depth) }
            .onSuccess { graph = it }
            .onFailure { message = it.message ?: "拓扑构建失败" }
            .isSuccess
    }

    LaunchedEffect(connectionId, rootName, rootType, depth) {
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

            val layout = remember(g, density) {
                with(density) { computeLayout(g, CARD_W.dp.toPx(), CARD_H.dp.toPx(), H_GAP.dp.toPx(), V_GAP.dp.toPx()) }
            }
            val byName = remember(g) { g.nodes.associateBy { it.name } }
            // 主题色在组合作用域里取一次（Color 值），再作为普通参数传进非组合 helper，
            // 避免在非组合的 remember 闭包里调用 @Composable 的取色函数。
            val scheme = MaterialTheme.colorScheme
            val drawEdges = remember(layout, byName, moduleId, scheme) {
                layout.edgeLines.map { line ->
                    val fromNode = byName[line.fromName]!!
                    val toNode = byName[line.toName]!!
                    DrawEdge(
                        fx = line.fx, fy = line.fy, tx = line.tx, ty = line.ty,
                        color = resolveEdgeColor(line, fromNode, toNode, moduleId, scheme),
                        dashed = line.dashed,
                    )
                }
            }

            Box(modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                ) {
                    with(density) {
                        Box(Modifier.size(layout.width.toDp(), layout.height.toDp())) {
                            Canvas(Modifier.matchParentSize()) {
                                val stroke = Stroke(width = 2.dp.toPx())
                                for (e in drawEdges) {
                                    drawLine(
                                        color = e.color,
                                        start = Offset(e.fx, e.fy),
                                        end = Offset(e.tx, e.ty),
                                        strokeWidth = stroke.width,
                                        pathEffect = if (e.dashed) {
                                            PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                            for (pn in layout.nodes) {
                                NodeCard(
                                    node = pn.node,
                                    left = pn.left,
                                    top = pn.top,
                                    moduleId = moduleId,
                                    selected = selected?.name == pn.node.name,
                                    onClick = {
                                        selected = pn.node
                                        pn.node.objectRef?.let { onNodeClick?.invoke(it) }
                                    },
                                )
                            }
                        }
                    }
                }

                selected?.let { node ->
                    NodeDetailCard(
                        node = node,
                        moduleId = moduleId,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                    )
                }
            }
        }
    }
}

/** 单个节点的卡片（绝对定位在画布坐标上）。 */
@Composable
private fun NodeCard(
    node: TopologyNode,
    left: Int,
    top: Int,
    moduleId: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val style = TopologyStyleRegistry.styleFor(moduleId, node.type) ?: TopologyStyleRegistry.fallback
    val accent = nodeAccent(node, style, MaterialTheme.colorScheme)
    val statusColor = node.status?.let { statusColor(it) }

    Box(
        Modifier
            .offset { IntOffset(left, top) }
            .width(CARD_W.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else accent.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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

/** 选中节点的详情卡（底部浮层）。 */
@Composable
private fun NodeDetailCard(node: TopologyNode, moduleId: String, modifier: Modifier = Modifier) {
    val style = TopologyStyleRegistry.styleFor(moduleId, node.type) ?: TopologyStyleRegistry.fallback
    Surface(
        modifier = modifier.width(280.dp),
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
                    modifier = Modifier.padding(start = 8.dp),
                )
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

// ===== 布局计算（纯函数，像素坐标） =====

private val CARD_W = 180
private val CARD_H = 64
private val H_GAP = 80
private val V_GAP = 24

private data class PositionedNode(val node: TopologyNode, val left: Int, val top: Int)
private data class EdgeLine(
    val fx: Float, val fy: Float, val tx: Float, val ty: Float,
    val dashed: Boolean, val fromName: String, val toName: String,
)
private data class LayoutResult(
    val nodes: List<PositionedNode>,
    val edgeLines: List<EdgeLine>,
    val width: Int,
    val height: Int,
)

/**
 * 分层布局：把无向图按连通分量 BFS 分层，每层一列（x 由层号决定），列内竖直堆叠并整体居中。
 * 适合「单 QMgr 局部链路」这类小而浅的图；规模不大，故用朴素 O(V+E) 即可。
 */
private fun computeLayout(
    graph: TopologyGraph,
    cardW: Float, cardH: Float, hGap: Float, vGap: Float,
): LayoutResult {
    val byName = graph.nodes.associateBy { it.name }
    val adj = mutableMapOf<String, MutableList<String>>()
    for (e in graph.edges) {
        if (e.from !in byName || e.to !in byName) continue
        adj.getOrPut(e.from) { mutableListOf() }.add(e.to)
        adj.getOrPut(e.to) { mutableListOf() }.add(e.from)
    }

    val visited = mutableSetOf<String>()
    val columns = sortedMapOf<Int, MutableList<String>>()
    var maxColCount = 0

    for (start in graph.nodes.map { it.name }) {
        if (start in visited) continue
        // 取连通分量
        val comp = mutableListOf<String>()
        val q = ArrayDeque<String>().apply { add(start) }
        visited.add(start)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            comp.add(n)
            for (nb in adj[n] ?: emptyList()) if (nb !in visited) {
                visited.add(nb); q.add(nb)
            }
        }
        // 分量根：优先 qmgr，否则度数最高，否则第一个（决定分层起点）
        val root = comp.firstOrNull { byName[it]?.type == "qmgr" }
            ?: comp.maxByOrNull { adj[it]?.size ?: 0 }
            ?: comp.first()
        // 从根 BFS 分层
        val q2 = ArrayDeque<Pair<String, Int>>().apply { add(root to 0) }
        val seen = mutableSetOf(root)
        while (q2.isNotEmpty()) {
            val (n, layer) = q2.removeFirst()
            columns.getOrPut(layer) { mutableListOf() }.add(n)
            maxColCount = maxOf(maxColCount, columns[layer]!!.size)
            for (nb in adj[n] ?: emptyList()) if (nb !in seen) {
                seen.add(nb); q2.add(nb to layer + 1)
            }
        }
    }

    val colHeight = maxColCount * cardH + (maxColCount - 1).coerceAtLeast(0) * vGap
    val positioned = mutableListOf<PositionedNode>()
    val center = mutableMapOf<String, Pair<Float, Float>>()
    for ((layer, list) in columns) {
        val x = layer * (cardW + hGap)
        val colH = list.size * cardH + (list.size - 1).coerceAtLeast(0) * vGap
        val yStart = ((colHeight - colH) / 2).coerceAtLeast(0f)
        list.forEachIndexed { i, name ->
            val top = yStart + i * (cardH + vGap)
            positioned.add(PositionedNode(byName[name]!!, x.toInt(), top.toInt()))
            center[name] = (x + cardW / 2f) to (top + cardH / 2f)
        }
    }
    val width = (columns.keys.maxOrNull() ?: 0) * (cardW + hGap) + cardW
    val height = colHeight

    val edgeLines = mutableListOf<EdgeLine>()
    for (e in graph.edges) {
        val f = center[e.from] ?: continue
        val t = center[e.to] ?: continue
        edgeLines.add(
            EdgeLine(
                fx = f.first, fy = f.second, tx = t.first, ty = t.second,
                dashed = e.style == EdgeStyle.DASHED, fromName = e.from, toName = e.to,
            ),
        )
    }
    return LayoutResult(positioned, edgeLines, width.toInt(), height.toInt())
}

// ===== 颜色解析（主题色在组合作用域取出后作为 Color 值传入，保持 helper 非组合） =====

private data class DrawEdge(
    val fx: Float, val fy: Float, val tx: Float, val ty: Float,
    val color: Color, val dashed: Boolean,
)

/** 节点强调色：语义 [NodeColor]（非 NORMAL）优先用主题色，否则用该类型的注册强调色。 */
private fun nodeAccent(node: TopologyNode, style: TopologyStyle, scheme: androidx.compose.material3.ColorScheme): Color = when (node.color) {
    NodeColor.PRIMARY -> scheme.primary
    NodeColor.WARNING -> scheme.error.copy(alpha = 0.8f)
    NodeColor.ERROR -> scheme.error
    NodeColor.MUTED -> scheme.outline
    else -> style.accent
}

/** 边颜色：两端节点带语义色时跟随 from 端，否则中性（outlineVariant）。 */
private fun resolveEdgeColor(
    line: EdgeLine,
    fromNode: TopologyNode,
    toNode: TopologyNode,
    moduleId: String,
    scheme: androidx.compose.material3.ColorScheme,
): Color {
    return when {
        fromNode.color != NodeColor.NORMAL ->
            nodeAccent(fromNode, TopologyStyleRegistry.styleFor(moduleId, fromNode.type) ?: TopologyStyleRegistry.fallback, scheme)
        toNode.color != NodeColor.NORMAL ->
            nodeAccent(toNode, TopologyStyleRegistry.styleFor(moduleId, toNode.type) ?: TopologyStyleRegistry.fallback, scheme)
        else -> scheme.outlineVariant
    }
}

/** 状态点的固定语义色（状态属于客观事实，不随主题翻转）。 */
private fun statusColor(status: NodeStatus): Color = when (status) {
    NodeStatus.RUNNING -> Color(0xFF2E7D32)
    NodeStatus.STOPPED -> Color(0xFFC62828)
    NodeStatus.RETRYING -> Color(0xFFED6C02)
    NodeStatus.STARTING, NodeStatus.STOPPING -> Color(0xFFF9A825)
    else -> Color(0xFF757575)
}
