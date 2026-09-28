package com.mqclient.topology.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dk.kuiver.SelectionMode
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import com.dk.kuiver.model.buildKuiver
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.kuiver.rememberKuiverViewerState
import com.dk.kuiver.renderer.KuiverInteractionCallbacks
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.dk.kuiver.ui.EdgeStyle
import com.mqclient.core.ConnectionStore
import com.mqclient.core.topology.TopologyProviderRegistry
import kotlinx.coroutines.flow.filter
import com.mqclient.graph.topology.EdgeStyle as GraphEdgeStyle
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
 * [TopologyGraph] → 映射成 kuiver 图模型（[KuiverNode]/[KuiverEdge]）交给 `KuiverViewer`
 * 渲染（分层布局 / 缩放 / 平移 / 节点拖拽均由 kuiver 承担）→ 图标 / 颜色经
 * [TopologyStyleRegistry] 按 `(moduleId, type)` 解析 → 点击节点选中并触发 [onNodeClick]
 * （[ObjectRef]）→ 宿主据此跳回对象面板。**
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

            val byName = remember(g) { g.nodes.associateBy { it.name } }
            // 主题色在组合作用域里取一次（Color 值），再作为普通值捕获进非组合 lambda，
            // 避免在非组合的 remember 闭包里调用 @Composable 的取色函数。
            val scheme = MaterialTheme.colorScheme

            // TopologyGraph → kuiver 图模型。节点 id 就是对象名；尺寸给固定卡片尺寸，
            // 布局按此排布，节点内容插槽里的 NodeCard 与之对齐。
            val kuiverGraph = remember(g) {
                buildKuiver {
                    g.nodes.forEach { node ->
                        addNode(KuiverNode(node.name, NodeDimensions(CARD_W.dp, CARD_H.dp)))
                    }
                    g.edges.forEach { edge ->
                        addEdge(KuiverEdge(fromId = edge.from, toId = edge.to))
                    }
                }
            }

            // viewer state 不按 g 重建（保留缩放 / 平移 / 手工拖拽位置），
            // 图数据变化经 updateKuiver 换图并按 policy 重排。
            val viewerState = rememberKuiverViewerState(
                initialKuiver = Kuiver(),
                layoutConfig = LayoutConfig.Hierarchical(
                    direction = LayoutDirection.HORIZONTAL,
                    levelSpacing = H_GAP.dp,
                    nodeSpacing = V_GAP.dp,
                ),
            )
            LaunchedEffect(kuiverGraph) { viewerState.updateKuiver(kuiverGraph) }

            // 边样式：按 from/to 查回原 TopologyEdge（虚线）与两端语义色（颜色）。
            // 注意 graph-core 也有个 EdgeStyle（DASHED 枚举），这里 import 别名区分。
            val edgeMeta = remember(g) { g.edges.associateBy { it.from to it.to } }
            val edgeStyleResolver = remember(edgeMeta, byName, moduleId, scheme) {
                { edge: KuiverEdge ->
                    val from = byName[edge.fromId]
                    val to = byName[edge.toId]
                    EdgeStyle(
                        color = resolveEdgeColor(from, to, moduleId, scheme),
                        strokeWidth = 2.dp,
                        showArrow = true,
                        dashed = edgeMeta[edge.fromId to edge.toId]?.style == GraphEdgeStyle.DASHED,
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
                        fitToContent = true,
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

    Box(
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
            )
            .padding(8.dp),
        contentAlignment = Alignment.CenterStart,
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

// ===== 常量与颜色解析 =====

private const val CARD_W = 180
private const val CARD_H = 64
private const val H_GAP = 80
private const val V_GAP = 24

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
    fromNode: TopologyNode?,
    toNode: TopologyNode?,
    moduleId: String,
    scheme: androidx.compose.material3.ColorScheme,
): Color {
    if (fromNode != null && fromNode.color != NodeColor.NORMAL) {
        return nodeAccent(fromNode, TopologyStyleRegistry.styleFor(moduleId, fromNode.type) ?: TopologyStyleRegistry.fallback, scheme)
    }
    if (toNode != null && toNode.color != NodeColor.NORMAL) {
        return nodeAccent(toNode, TopologyStyleRegistry.styleFor(moduleId, toNode.type) ?: TopologyStyleRegistry.fallback, scheme)
    }
    return scheme.outlineVariant
}

/** 状态点的固定语义色（状态属于客观事实，不随主题翻转）。 */
private fun statusColor(status: NodeStatus): Color = when (status) {
    NodeStatus.RUNNING -> Color(0xFF2E7D32)
    NodeStatus.STOPPED -> Color(0xFFC62828)
    NodeStatus.RETRYING -> Color(0xFFED6C02)
    NodeStatus.STARTING, NodeStatus.STOPPING -> Color(0xFFF9A825)
    else -> Color(0xFF757575)
}
