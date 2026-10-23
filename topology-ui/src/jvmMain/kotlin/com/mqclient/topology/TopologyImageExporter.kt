package com.mqclient.topology

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.ui.EdgePath
import com.dk.kuiver.ui.EdgePathFactory
import com.dk.kuiver.ui.EdgeStyle
import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyNodeType
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 拓扑图离屏导出：不截屏幕，按**图的逻辑包围盒**重画一遍节点与连线。
 *
 * 为什么不是「截当前视口」：
 *  - 视口截图只包含可见区域，放大 / 平移后屏幕外的节点全部丢失；
 *  - UI 为了适应窗口通常把图缩得很小，截图文字直接糊掉。
 *
 * 实现方式：Compose Desktop 的 [ImageComposeScene]（Skia 光栅离屏场景）。
 * 它把同一套 `TopologyNodeCard` / 边绘制组合到一张离屏 surface 上，而不是手写
 * Canvas 图形 —— 节点卡片是 Composable（文字、Material 图标、Surface 描边），
 * 裸 `ImageBitmap` + `Canvas` 无法绘制 Composable；`ImageComposeScene` 是
 * Compose Desktop 官方支持的无窗口渲染入口，且能把场景 [Density] 设为输出倍率，
 * 文字按 2x 字宽重新栅格化（不是先画 1x 再拉伸，所以不模糊）。
 *
 * 坐标/倍率：
 *  - kuiver 的节点位置与尺寸单位都是 dp；场景 density = scale 时，1dp = scale px；
 *  - 图片物理像素 = ceil(逻辑 dp * scale)；
 *  - 绘制前 translate(-minX, -minY)，把全图左上角对齐到图片 (0, 0)；
 *  - 只画节点与连线：工具栏、滚动条、详情卡、选中高亮均不进入导出场景。
 *
 * 图库能力核对（kuiver 0.4.2）：`KuiverViewerState` / `KuiverViewer` 没有
 * renderToBitmap / export 一类 API，因此走上面的通用离屏渲染；边的几何与样式
 * 复用图库公开的 [EdgePathFactory]、[EdgeStyle]，与屏幕看到的一致。
 */

/** 导出场景的固定倍率：逻辑 dp → 2 物理像素（1x/2x 由调用方按需传 scale）。 */
const val TOPOLOGY_EXPORT_DEFAULT_SCALE = 2f

/** 单边像素上限：超出时自动降低 scale，避免超大图直接 OOM。 */
const val TOPOLOGY_EXPORT_MAX_SIDE_PX = 8000

/**
 * 总像素上限（约 96 MB 的 ARGB 缓冲）：仅限制单边 8000 不够，8000×8000 仍是
 * 64M 像素、两个缓冲就 512 MB。超大图优先保「完整」而不是保「2x」。
 */
private const val MAX_EXPORT_PIXELS = 24_000_000f

/** 节点包围盒外再留一圈，避免卡片 / 自环边紧贴图片边缘。 */
private val EXPORT_PADDING = 48.dp

/** 与 kuiver `EdgeRenderer` 的 EDGE_PADDING 同值（图库内部为裸 px，不是 dp）。 */
private const val KUIVER_EDGE_PADDING_PX = 4f

/** kuiver 绘制边线时的固定线透明度（见其 `EdgeDrawingDefaults.LINE_ALPHA`）。 */
private const val KUIVER_EDGE_LINE_ALPHA = 0.8f

/** 导出时可以带入的主题快照（非 composable 的离屏场景需要显式 MaterialTheme）。 */
@Immutable
data class TopologyExportTheme(
    val colorScheme: ColorScheme,
    val typography: Typography,
    val shapes: Shapes,
)

/** 一次导出的结果：PNG 字节 + 实际像素尺寸 + 实际生效倍率（可能因上限被降低）。 */
class TopologyExportImage(
    val pngBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val scale: Float,
)

/**
 * 渲染整张拓扑图为 PNG。
 *
 * 每次调用都创建独立的 `ImageComposeScene`（自己的 recomposer + Skia surface），
 * 不依赖调用线程上已有的 Compose 树，因此可以放在后台线程（如 `Dispatchers.Default`）
 * 渲染，避免大图阻塞 UI；调用方随后回到自己的线程做 FileKit 保存。
 *
 * @param graph 已经完成布局的图（传 `KuiverViewerState.layoutedKuiver`，含用户拖动
 *   后的手动位置；不要传未布局的原始 `Kuiver`）。
 * @param nodeTypes / severities / badges 与屏幕渲染同源的样式表。
 * @param fallbackType 类型表缺失时的兜底类型。
 * @param theme 调用点的 MaterialTheme 快照（颜色 / 字体 / 形状）。
 * @param displayName 节点 id → 显示名（MQ 的 `rqmgr:` 前缀由调用方剥掉）。
 * @param scale 期望倍率；超过安全上限时自动降低，实际值见结果。
 * @param padding 逻辑包围盒内边距。
 * @param background 背景色；默认纯白（不用界面浅灰），传 null 得到透明背景。
 * @param edgeCustomize 按边定制样式（与 [TopologyCanvas] 的同名参数一致）：
 *   Kafka 副本关系虚线边这类定制必须一起传进来，否则导出图与屏幕不一致。
 * @throws IllegalStateException 图中存在带 `fromAnchor` / `toAnchor` 的边时——
 *   锚点位置由图库在组合期注册（`KuiverAnchor` + internal 的 AnchorRegistry），
 *   离屏场景拿不到。**宁可直接失败，也不静默画出指向错误位置的边。**
 */
@OptIn(ExperimentalComposeUiApi::class)
fun renderTopologyPng(
    graph: Kuiver,
    nodeTypes: Map<String, TopologyNodeType>,
    fallbackType: TopologyNodeType,
    theme: TopologyExportTheme,
    severities: Map<String, NodeSeverity> = emptyMap(),
    badges: Map<String, String> = emptyMap(),
    displayName: (String) -> String = { it },
    scale: Float = TOPOLOGY_EXPORT_DEFAULT_SCALE,
    padding: Dp = EXPORT_PADDING,
    background: Color? = Color.White,
    edgeCustomize: ((KuiverEdge, EdgeStyle) -> EdgeStyle)? = null,
): TopologyExportImage {
    require(graph.nodes.isNotEmpty()) { "topology graph is empty" }
    require(scale > 0f && scale.isFinite()) { "export scale must be positive and finite" }
    requireResolvableEdgeEndpoints(graph)

    val plan = planTopologyExport(graph, padding, scale)
    val bitmap = renderOffscreen(
        graph = graph,
        nodeTypes = nodeTypes,
        severities = severities,
        badges = badges,
        fallbackType = fallbackType,
        theme = theme,
        displayName = displayName,
        plan = plan,
        background = background,
        edgeCustomize = edgeCustomize,
    )

    val output = ByteArrayOutputStream()
    ImageIO.write(bitmap, "png", output)
    return TopologyExportImage(
        pngBytes = output.toByteArray(),
        widthPx = plan.widthPx,
        heightPx = plan.heightPx,
        scale = plan.scale,
    )
}

// ---------------------------------------------------------------------------------------------
// 尺寸计算
// ---------------------------------------------------------------------------------------------

/**
 * 拒绝导出带锚点的图：`KuiverAnchor` 的位置由图库在**组合期**经
 * `onGloballyPositioned` 注册进 internal 的 AnchorRegistry，离屏场景无法读取。
 *
 * 真遇到时的表现：几何端点会落在节点中心与边界的连线上，而不是用户定义的锚点 ——
 * 导出图和屏幕看起来差不多，但连线接点错了，属于「不报错但错」的那类。
 * 所以这里直接失败，由 UI 层把异常文案弹给用户（导出入口已统一 try/catch）。
 */
internal fun requireResolvableEdgeEndpoints(graph: Kuiver) {
    val anchored = graph.edges.filter { it.fromAnchor != null || it.toAnchor != null }
    if (anchored.isEmpty()) return
    val sample = anchored.first()
    throw IllegalStateException(
        "该拓扑使用了自定义连接点（KuiverAnchor），导出暂不支持：" +
            "${sample.fromId} → ${sample.toId}（共 ${anchored.size} 条边带锚点）",
    )
}

/** 离屏导出的尺寸计划：逻辑原点、逻辑尺寸、有效倍率、物理像素尺寸。 */
internal data class TopologyExportPlan(
    val originX: Float,
    val originY: Float,
    val widthDp: Float,
    val heightDp: Float,
    val widthPx: Int,
    val heightPx: Int,
    val scale: Float,
)

/**
 * 计算全图逻辑包围盒 + 安全倍率。
 *
 * 包围盒以**节点卡片**为基础（中心 ± 尺寸/2），并额外纳入连线几何：BACK 边是
 * 外抛的贝塞尔曲线、自环边在节点上方拱起，只按节点算会裁掉这些部分。
 */
internal fun planTopologyExport(
    graph: Kuiver,
    padding: Dp = EXPORT_PADDING,
    requestedScale: Float = TOPOLOGY_EXPORT_DEFAULT_SCALE,
): TopologyExportPlan {
    require(graph.nodes.isNotEmpty()) { "topology graph is empty" }

    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY

    fun include(x: Float, y: Float) {
        minX = min(minX, x)
        minY = min(minY, y)
        maxX = max(maxX, x)
        maxY = max(maxY, y)
    }

    graph.nodes.values.forEach { node ->
        val halfW = node.width().value / 2f
        val halfH = node.height().value / 2f
        include(node.position.x.value - halfW, node.position.y.value - halfH)
        include(node.position.x.value + halfW, node.position.y.value + halfH)
    }

    // 连线几何：屏幕渲染时贝塞尔曲线可能外抛到节点包围盒之外（尤其是长 BACK 边）。
    graph.edges.forEach { edge ->
        val from = graph.nodes[edge.fromId] ?: return@forEach
        val to = graph.nodes[edge.toId] ?: return@forEach
        edgeGeometrySamples(edge, from, to).forEach { include(it.x, it.y) }
    }

    val pad = padding.value
    val originX = minX - pad
    val originY = minY - pad
    val widthDp = max(1f, maxX + pad - originX)
    val heightDp = max(1f, maxY + pad - originY)

    // 先按单边上限收，再按总像素上限收；ceil 后再夹一次，保证绝不越界。
    val bySide = TOPOLOGY_EXPORT_MAX_SIDE_PX / max(widthDp, heightDp)
    val area = widthDp * heightDp
    val byPixels = if (area.isFinite() && area > 0f) sqrt(MAX_EXPORT_PIXELS / area) else 0f
    val effectiveScale = min(requestedScale, min(bySide, byPixels)).coerceAtLeast(1e-4f)

    val widthPx = ceil(widthDp * effectiveScale).toInt()
        .coerceIn(1, TOPOLOGY_EXPORT_MAX_SIDE_PX)
    val heightPx = ceil(heightDp * effectiveScale).toInt()
        .coerceIn(1, TOPOLOGY_EXPORT_MAX_SIDE_PX)

    return TopologyExportPlan(
        originX = originX,
        originY = originY,
        widthDp = widthDp,
        heightDp = heightDp,
        widthPx = widthPx,
        heightPx = heightPx,
        scale = effectiveScale,
    )
}

// ---------------------------------------------------------------------------------------------
// 离屏场景
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalComposeUiApi::class)
private fun renderOffscreen(
    graph: Kuiver,
    nodeTypes: Map<String, TopologyNodeType>,
    severities: Map<String, NodeSeverity>,
    badges: Map<String, String>,
    fallbackType: TopologyNodeType,
    theme: TopologyExportTheme,
    displayName: (String) -> String,
    plan: TopologyExportPlan,
    background: Color?,
    edgeCustomize: ((KuiverEdge, EdgeStyle) -> EdgeStyle)?,
): BufferedImage {
    // density = 输出倍率：所有 dp 尺寸（卡片、文字、线宽、箭头）按 2x 重新栅格化。
    // 不是先画 1x 再放大，所以文字边缘是清晰的。
    val scene = ImageComposeScene(
        width = plan.widthPx,
        height = plan.heightPx,
        density = Density(plan.scale),
    ) {
        MaterialTheme(
            colorScheme = theme.colorScheme,
            typography = theme.typography,
            shapes = theme.shapes,
        ) {
            TopologyExportContent(
                graph = graph,
                nodeTypes = nodeTypes,
                severities = severities,
                badges = badges,
                fallbackType = fallbackType,
                displayName = displayName,
                plan = plan,
                background = background,
                edgeCustomize = edgeCustomize,
            )
        }
    }
    return try {
        // render() 返回 Skia Image 快照；转 AWT 交给 ImageIO 编码。
        scene.render().toComposeImageBitmap().toAwtImage()
    } finally {
        scene.close()
    }
}

/** 导出内容：先画边、再画节点；不含工具栏 / 滚动条 / 选中高亮。 */
@Composable
private fun TopologyExportContent(
    graph: Kuiver,
    nodeTypes: Map<String, TopologyNodeType>,
    severities: Map<String, NodeSeverity>,
    badges: Map<String, String>,
    fallbackType: TopologyNodeType,
    displayName: (String) -> String,
    plan: TopologyExportPlan,
    background: Color?,
    edgeCustomize: ((KuiverEdge, EdgeStyle) -> EdgeStyle)?,
) {
    val scheme = MaterialTheme.colorScheme
    val backgroundModifier = if (background != null) Modifier.background(background) else Modifier

    // fillMaxSize 而不是 size(widthDp)，因为像素尺寸向上取整后可能比逻辑尺寸多不到
    // 1px；背景要铺满整张位图，否则右/下边缘会留一条透明缝。
    Box(Modifier.fillMaxSize().then(backgroundModifier)) {
        Canvas(Modifier.fillMaxSize()) {
            // 全图坐标 → 图片坐标：图片左上角 = 包围盒左上角（含 padding）。
            translate(left = -plan.originX.dp.toPx(), top = -plan.originY.dp.toPx()) {
                graph.edges.forEach { edge ->
                    val fromNode = graph.nodes[edge.fromId] ?: return@forEach
                    val toNode = graph.nodes[edge.toId] ?: return@forEach
                    // 与屏幕同一套样式工厂：CURVED + 1.5dp + 箭头，BACK 边橙色。
                    var style = topologyEdgeStyle(
                        edge = edge,
                        normalColor = scheme.outlineVariant,
                        cycleColor = CYCLE_EDGE_COLOR,
                    )
                    // 与屏幕一致：面板的按边定制（如 Kafka 副本虚线边）也要叠加。
                    edgeCustomize?.let { style = it(edge, style) }
                    drawTopologyEdge(edge, fromNode, toNode, style)
                }
            }
        }

        // 节点后画，压在连线之上。卡片定位 = 图坐标左上角 − 包围盒原点。
        graph.nodes.values.forEach { node ->
            val width = node.width()
            val height = node.height()
            val left = node.position.x.value - width.value / 2f - plan.originX
            val top = node.position.y.value - height.value / 2f - plan.originY
            Box(
                Modifier
                    .offset(x = left.dp, y = top.dp)
                    .size(width, height),
            ) {
                TopologyNodeCard(
                    name = displayName(node.id),
                    type = nodeTypes[node.id] ?: fallbackType,
                    badge = badges[node.id],
                    severity = severities[node.id] ?: NodeSeverity.NORMAL,
                    // 导出是静态全图：不表达悬停 / 拖动 / 选中高亮。
                    isHovered = false,
                    isDragging = false,
                    isSelected = false,
                    dimmed = false,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 连线绘制（复刻 kuiver 的 batched edge 绘制，但不含交互态）
// ---------------------------------------------------------------------------------------------

private fun KuiverNode.width(): Dp = dimensions?.width ?: CARD_WIDTH.dp
private fun KuiverNode.height(): Dp = dimensions?.height ?: CARD_HEIGHT.dp

/**
 * 在 DrawScope 中画一条边。当前样式固定为 `EdgeShape.CURVED`（与 [topologyEdgeStyle]
 * 一致），自环边走图库的 self-loop 路径；线的收短 / 箭头位置都由图库公开的
 * [EdgePathFactory] 计算，避免自己重算曲线切线。
 */
private fun DrawScope.drawTopologyEdge(
    edge: KuiverEdge,
    fromNode: KuiverNode,
    toNode: KuiverNode,
    style: EdgeStyle,
) {
    val (from, to) = edgeEndpoints(edge, fromNode, toNode)

    val path: EdgePath = if (edge.fromId == edge.toId) {
        EdgePathFactory.createSelfLoopPath(
            from = from,
            to = to,
            density = this,
            loopRadius = style.loopRadius,
            showArrow = style.showArrow,
            strokeWidth = style.strokeWidth,
        )
    } else {
        EdgePathFactory.createCurvedPath(
            from = from,
            to = to,
            density = this,
            showArrow = style.showArrow,
            strokeWidth = style.strokeWidth,
        )
    }

    val effect = if (style.dashed) {
        PathEffect.dashPathEffect(floatArrayOf(style.dashLength.toPx(), style.gapLength.toPx()))
    } else {
        null
    }
    val lineColor = style.color.copy(alpha = KUIVER_EDGE_LINE_ALPHA)

    when (path) {
        is EdgePath.Straight -> {
            drawLine(
                color = lineColor,
                start = path.from,
                end = path.pathEndpoint,
                strokeWidth = style.strokeWidth.toPx(),
                pathEffect = effect,
                cap = StrokeCap.Round,
            )
            if (style.showArrow && path.edgeLength > 0f) {
                val direction = (path.to - path.from) / path.edgeLength
                style.arrowDrawer.invoke(this, path.to, direction, style.color, style.arrowSize.toPx())
            }
        }

        is EdgePath.Curved -> {
            drawPath(
                path = Path().apply {
                    moveTo(path.from.x, path.from.y)
                    quadraticTo(
                        path.controlPoint.x,
                        path.controlPoint.y,
                        path.pathEndpoint.x,
                        path.pathEndpoint.y,
                    )
                },
                color = lineColor,
                style = Stroke(width = style.strokeWidth.toPx(), pathEffect = effect, cap = StrokeCap.Round),
            )
            drawArrowIfNeeded(path.to, path.controlPoint, style)
        }

        is EdgePath.SelfLoop -> {
            drawPath(
                path = Path().apply {
                    moveTo(path.from.x, path.from.y)
                    quadraticTo(
                        path.controlPoint.x,
                        path.controlPoint.y,
                        path.pathEndpoint.x,
                        path.pathEndpoint.y,
                    )
                },
                color = lineColor,
                style = Stroke(width = style.strokeWidth.toPx(), pathEffect = effect, cap = StrokeCap.Round),
            )
            drawArrowIfNeeded(path.to, path.controlPoint, style)
        }

        is EdgePath.Orthogonal -> {
            drawPath(
                path = Path().apply {
                    moveTo(path.from.x, path.from.y)
                    cubicTo(
                        path.controlPoint1.x, path.controlPoint1.y,
                        path.controlPoint2.x, path.controlPoint2.y,
                        path.pathEndpoint.x, path.pathEndpoint.y,
                    )
                },
                color = lineColor,
                style = Stroke(width = style.strokeWidth.toPx(), pathEffect = effect, cap = StrokeCap.Round),
            )
            drawArrowIfNeeded(path.to, path.controlPoint2, style)
        }

        is EdgePath.RightAngle -> {
            drawPath(
                path = Path().apply {
                    moveTo(path.from.x, path.from.y)
                    path.waypoints.forEach { lineTo(it.x, it.y) }
                    lineTo(path.pathEndpoint.x, path.pathEndpoint.y)
                },
                color = lineColor,
                style = Stroke(width = style.strokeWidth.toPx(), pathEffect = effect, cap = StrokeCap.Round),
            )
            path.arrowDirection?.let {
                style.arrowDrawer.invoke(this, path.to, it, style.color, style.arrowSize.toPx())
            }
        }
    }
}

private fun DrawScope.drawArrowIfNeeded(tip: Offset, controlPoint: Offset, style: EdgeStyle) {
    if (!style.showArrow) return
    val tangent = tip - controlPoint
    val distance = sqrt(tangent.x * tangent.x + tangent.y * tangent.y)
    if (distance <= 0f) return
    style.arrowDrawer.invoke(this, tip, tangent / distance, style.color, style.arrowSize.toPx())
}

/**
 * 复刻 kuiver `EdgeRenderer` 的端点计算：中心点 + 矩形边界求交 + 4px 间隙。
 * 图库的 anchor 注册表是 internal 且依赖屏幕组合，这里不读；当前四个拓扑面板
 * 都没有使用 KuiverAnchor，边全部走几何端点，与屏幕渲染一致。
 */
private fun DrawScope.edgeEndpoints(
    edge: KuiverEdge,
    fromNode: KuiverNode,
    toNode: KuiverNode,
): Pair<Offset, Offset> {
    val fromCenter = Offset(fromNode.position.x.toPx(), fromNode.position.y.toPx())
    val toCenter = Offset(toNode.position.x.toPx(), toNode.position.y.toPx())
    val fromW = fromNode.width().toPx()
    val fromH = fromNode.height().toPx()
    val toW = toNode.width().toPx()
    val toH = toNode.height().toPx()

    if (edge.fromId == edge.toId) {
        return Offset(fromCenter.x - fromW * 0.15f, fromCenter.y - fromH / 2f) to
            Offset(fromCenter.x + fromW * 0.15f, fromCenter.y - fromH / 2f)
    }

    val direction = toCenter - fromCenter
    val distance = sqrt(direction.x * direction.x + direction.y * direction.y)
    if (distance == 0f) return fromCenter to toCenter

    val unit = direction / distance
    val fromRadius = nodeRadius(unit, fromW / 2f, fromH / 2f)
    val toRadius = nodeRadius(unit, toW / 2f, toH / 2f)

    val start = Offset(
        fromCenter.x + unit.x * (fromRadius + KUIVER_EDGE_PADDING_PX),
        fromCenter.y + unit.y * (fromRadius + KUIVER_EDGE_PADDING_PX),
    )
    val end = Offset(
        toCenter.x - unit.x * (toRadius + KUIVER_EDGE_PADDING_PX),
        toCenter.y - unit.y * (toRadius + KUIVER_EDGE_PADDING_PX),
    )
    return start to end
}

private fun nodeRadius(direction: Offset, halfWidth: Float, halfHeight: Float): Float =
    if (abs(direction.x) * halfHeight > abs(direction.y) * halfWidth) {
        halfWidth / abs(direction.x)
    } else {
        halfHeight / abs(direction.y)
    }

/**
 * 连线几何采样（逻辑 dp 空间，Density=1）：用于包围盒。
 * 贝塞尔取 24 段折线，控制点不直接进包围盒（二次曲线的实际外抛只有控制点偏移
 * 的一半，用控制点会把图片撑大近一倍）。
 */
private fun edgeGeometrySamples(
    edge: KuiverEdge,
    fromNode: KuiverNode,
    toNode: KuiverNode,
): List<Offset> {
    val density = Density(1f)
    val from = Offset(fromNode.position.x.value, fromNode.position.y.value)
    val to = Offset(toNode.position.x.value, toNode.position.y.value)

    // 与绘制保持一致：自环、外抛曲线；自环端点接近节点顶部。
    val path: EdgePath = if (edge.fromId == edge.toId) {
        val halfW = fromNode.width().value / 2f
        val halfH = fromNode.height().value / 2f
        EdgePathFactory.createSelfLoopPath(
            from = Offset(from.x - halfW * 0.3f, from.y - halfH),
            to = Offset(from.x + halfW * 0.3f, from.y - halfH),
            density = density,
            loopRadius = 40.dp,
        )
    } else {
        // 端点近似到节点边界（不含 4px 间隙，包围盒另有 48dp padding，足够覆盖）。
        val start = edgeBoundaryPoint(fromNode, to)
        val end = edgeBoundaryPoint(toNode, from)
        EdgePathFactory.createCurvedPath(from = start, to = end, density = density)
    }

    return when (path) {
        is EdgePath.Curved -> sampleQuadratic(
            path.from, path.controlPoint, path.pathEndpoint, SAMPLES,
        ) + listOf(path.to)

        is EdgePath.SelfLoop -> sampleQuadratic(
            path.from, path.controlPoint, path.pathEndpoint, SAMPLES,
        ) + listOf(path.to)

        is EdgePath.Straight -> listOf(path.from, path.to, path.pathEndpoint)

        is EdgePath.Orthogonal -> sampleCubic(
            path.from, path.controlPoint1, path.controlPoint2, path.pathEndpoint, SAMPLES,
        ) + listOf(path.to)

        is EdgePath.RightAngle -> listOf(path.from, path.to, path.pathEndpoint) + path.waypoints
    }
}

private const val SAMPLES = 24

private fun sampleQuadratic(p0: Offset, p1: Offset, p2: Offset, steps: Int): List<Offset> =
    (0..steps).map { i ->
        val t = i.toFloat() / steps
        val u = 1f - t
        Offset(
            u * u * p0.x + 2f * u * t * p1.x + t * t * p2.x,
            u * u * p0.y + 2f * u * t * p1.y + t * t * p2.y,
        )
    }

private fun sampleCubic(
    p0: Offset,
    p1: Offset,
    p2: Offset,
    p3: Offset,
    steps: Int,
): List<Offset> = (0..steps).map { i ->
    val t = i.toFloat() / steps
    val u = 1f - t
    Offset(
        u * u * u * p0.x + 3f * u * u * t * p1.x + 3f * u * t * t * p2.x + t * t * t * p3.x,
        u * u * u * p0.y + 3f * u * u * t * p1.y + 3f * u * t * t * p2.y + t * t * t * p3.y,
    )
}

/** 从节点中心指向 [target] 的射线与节点矩形边界的交点；仅用于包围盒估算。 */
private fun edgeBoundaryPoint(node: KuiverNode, target: Offset): Offset {
    val center = Offset(node.position.x.value, node.position.y.value)
    val halfW = node.width().value / 2f
    val halfH = node.height().value / 2f
    val direction = target - center
    val distance = sqrt(direction.x * direction.x + direction.y * direction.y)
    if (distance == 0f) return center
    val unit = direction / distance
    val radius = if (abs(unit.x) * halfH > abs(unit.y) * halfW) {
        halfW / abs(unit.x)
    } else {
        halfH / abs(unit.y)
    }
    return Offset(center.x + unit.x * radius, center.y + unit.y * radius)
}
