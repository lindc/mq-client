package com.mqclient.ibmmq.ui.topology

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.buildKuiverWithClassifiedEdges
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.model.layout.LayoutProvider
import com.mqclient.graph.TopologyNodeType
import com.mqclient.topology.CARD_HEIGHT

/**
 * 当前图的 `nodeId → 类型` 查找表，供 [columnLayoutProvider] 在布局阶段读取。
 *
 * kuiver 的 LayoutProvider 只看得到通用节点（无 type 字段），而固定列序 / alias 链深度
 * 都需要类型语义。与 topology-ui 通用面板的 clusteredTypeLookup 同一思路：桌面单面板
 * 场景下用模块级暂存足够。
 *
 * **线程/时序不变量**：写入（[TopologyPanel] 的 LaunchedEffect 里 `topologyNodeTypes = …`）
 * 与消费（紧随的 `relayout()` → provider 同步执行）必须保持**主线程相邻语句、中间无挂起点**
 * ——Main dispatcher 单线程下其他面板的布局不可能插进这个窗口，故不会串图。若未来把
 * 布局挪到后台或拆散这两句，必须先把本表改为随图传递（如包进 LayoutConfig）。
 */
internal var topologyNodeTypes: Map<String, TopologyNodeType> = emptyMap()

/**
 * 「按角色分列 + 数据流泳道」的自定义布局（[LayoutConfig.Custom] 用）。
 *
 * 列序按 IBM MQ 的消息流向固定（左 → 右）：
 *
 * ```
 * alias(→topic) | topic | sub + alias(→remote) | remote | xmitq | channel | 远程QMGR | alias(→local) | local
 *      1          2            3                    4        5       6         7            8           9
 * ```
 *
 * Alias 链展开：alias 指向 alias 时按深度分列，**越深越往左**（列 = 基础列 + maxAliasDepth
 * - 自身深度），链条从左向右自然展开到最终目标所在的基础列。
 *
 * 同列纵向排序，两级：
 *  1. **泳道聚类**（并查集）：把整张图按连通性分成若干「数据流」（如
 *     `ALIAS.A→TOPIC1→SUB1→LOCAL.Q1` 为一条），同一流的节点在所有列里保持纵向连续——
 *     列与列之间呈水平泳道，边基本只在泳道内部走，不横穿别的泳道的卡片。流的先后按
 *     本列节点「邻居 y 均值」自适应（泳道整体可上下换位）。
 *  2. **重心法松弛**（交替下扫 / 上扫若干轮）：按对端 y 均值微调泳道内次序，
 *     进一步压平跨列连线。
 *
 * 节点坐标由本 provider 直接写入并经 [buildKuiverWithClassifiedEdges] 重建，边类型
 * （FORWARD / BACK / CROSS / SELF_LOOP）随之定档，BACK 边由面板标橙。
 */
val columnLayoutProvider: LayoutProvider = labeled@{ kuiver, config ->
    val nodes = kuiver.nodes.values.toList()
    if (nodes.isEmpty()) return@labeled kuiver
    val types = topologyNodeTypes

    // ---- 邻接表（有向 + 无向各一份）----
    val outgoing = mutableMapOf<String, MutableList<String>>()
    val neighbors = mutableMapOf<String, MutableList<String>>()
    kuiver.edges.forEach { e ->
        outgoing.getOrPut(e.fromId) { mutableListOf() }.add(e.toId)
        neighbors.getOrPut(e.fromId) { mutableListOf() }.add(e.toId)
        neighbors.getOrPut(e.toId) { mutableListOf() }.add(e.fromId)
    }

    // ---- 数据流聚类（并查集）：有边相连的节点归同一流，作为纵向泳道的分组键 ----
    val parent = mutableMapOf<String, String>()
    nodes.forEach { parent[it.id] = it.id }
    fun find(x: String): String {
        var root = x
        while (parent[root] != root) root = parent.getValue(root)
        var cur = x
        while (parent[cur] != cur) {
            val next = parent.getValue(cur)
            parent[cur] = root
            cur = next
        }
        return root
    }
    kuiver.edges.forEach { e ->
        val a = find(e.fromId)
        val b = find(e.toId)
        if (a != b) parent[a] = b
    }
    val flowKeyOf = nodes.associate { it.id to find(it.id) }

    // ---- alias 链深度与最终目标类型 ----
    val aliasNext = mutableMapOf<String, String>()
    kuiver.edges.forEach { e ->
        if (types[e.fromId] == TopologyNodeType.ALIAS_QUEUE &&
            types[e.toId] == TopologyNodeType.ALIAS_QUEUE
        ) {
            aliasNext[e.fromId] = e.toId
        }
    }

    /** alias 深度：沿 alias→alias 边走到非 alias 目标的步数（直接指向非 alias = 0）。 */
    fun aliasDepth(id: String): Int {
        var depth = 0
        var cur = id
        val seen = mutableSetOf(id)
        while (true) {
            val next = aliasNext[cur] ?: return depth
            if (next in seen) return depth // 环：按当前深度收尾
            seen += next
            cur = next
            depth++
        }
    }

    /** 沿 alias 链找到的最终非 alias 目标类型（TOPIC / REMOTE_QUEUE / LOCAL_QUEUE…）。 */
    fun terminalTargetType(id: String): TopologyNodeType? {
        var cur = id
        val seen = mutableSetOf(id)
        while (true) {
            val direct = outgoing[cur]
                ?.mapNotNull { types[it] }
                ?.firstOrNull { it != TopologyNodeType.ALIAS_QUEUE }
            if (direct != null) return direct
            val next = aliasNext[cur] ?: return null
            if (next in seen) return null
            seen += next
            cur = next
        }
    }

    val aliasDepths = nodes.mapNotNull { n ->
        if (types[n.id] == TopologyNodeType.ALIAS_QUEUE) n.id to aliasDepth(n.id) else null
    }.toMap()
    val maxAliasDepth = aliasDepths.values.maxOrNull() ?: 0

    // ---- 列分配（见类注释的列序表；alias 链按 maxAliasDepth - depth 左移）----
    fun getColumn(id: String): Int {
        val type = types[id] ?: return 0
        return when (type) {
            TopologyNodeType.ALIAS_QUEUE -> {
                val shift = maxAliasDepth - (aliasDepths[id] ?: 0)
                when (terminalTargetType(id)) {
                    TopologyNodeType.TOPIC -> 1 + shift
                    TopologyNodeType.REMOTE_QUEUE -> 3 + shift
                    TopologyNodeType.LOCAL_QUEUE -> 8 + shift
                    // 目标不在图内（悬空 alias）：靠最左，不强行归列。
                    else -> 1 + shift
                }
            }
            TopologyNodeType.TOPIC -> 2 + maxAliasDepth
            TopologyNodeType.SUBSCRIPTION -> 3 + maxAliasDepth
            TopologyNodeType.REMOTE_QUEUE -> 4 + maxAliasDepth
            TopologyNodeType.TRANSMISSION_QUEUE -> 5 + maxAliasDepth
            TopologyNodeType.CHANNEL -> 6 + maxAliasDepth
            TopologyNodeType.REMOTE_QMGR -> 7 + maxAliasDepth
            // 集群关系面板专用节点（消息流拓扑里不出现）；归 REMOTE_QMGR 同列兜底。
            TopologyNodeType.QMGR_FULL -> 7 + maxAliasDepth
            TopologyNodeType.QMGR_PARTIAL -> 7 + maxAliasDepth
            // Kafka / RabbitMQ 专属节点类型：MQ 图里不出现，归第 0 列兜底（保 exhaustive）。
            TopologyNodeType.PARTITION -> 0
            TopologyNodeType.BROKER -> 0
            TopologyNodeType.CONSUMER_GROUP -> 0
            TopologyNodeType.EXCHANGE -> 0
            TopologyNodeType.RABBIT_QUEUE -> 0
            TopologyNodeType.LOCAL_QUEUE -> 9 + maxAliasDepth
        }
    }

    // ---- 按列分组，初始按名称稳定排序 ----
    val columnOrder = nodes
        .groupBy { getColumn(it.id) }
        .toSortedMap()
        .mapValues { (_, ns) -> ns.sortedBy { it.id }.toMutableList() }
    val columns = columnOrder.keys.sorted()

    // ---- 泳道 + 重心法联合松弛：交替下扫 / 上扫（~12 轮收敛）----
    val yOf = mutableMapOf<String, Float>()
    fun restackColumn(col: Int) {
        var y = 0f
        columnOrder[col]?.forEach { n ->
            yOf[n.id] = y
            y += (n.dimensions?.height ?: CARD_HEIGHT.dp).value + ROW_SPACING.value
        }
    }
    columns.forEach(::restackColumn)

    /** 单节点重心：无向邻居的 y 均值；无邻居用自身当前 y（保持原位）。 */
    fun barycenter(id: String): Float {
        val ys = neighbors[id]?.mapNotNull { yOf[it] } ?: emptyList()
        return if (ys.isEmpty()) yOf[id] ?: 0f else ys.average().toFloat()
    }

    repeat(12) { round ->
        val leftToRight = round % 2 == 0
        val order = if (leftToRight) columns else columns.asReversed()
        order.forEach { col ->
            val list = columnOrder[col] ?: return@forEach
            // 先泳道：同一流的节点排成连续段，段间按该流在本列的重心均值排序
            // （泳道整体自适应上下换位，段内保持既有相对次序）。
            val lanes = list
                .groupBy { flowKeyOf.getValue(it.id) }
                .entries
                .sortedBy { (_, ns) -> ns.map { barycenter(it.id) }.average().toFloat() }
            list.clear()
            lanes.forEach { (_, ns) -> list.addAll(ns) }
            restackColumn(col)
        }
    }

    // ---- 落坐标（列 x 居中；列内 y 依最终顺序堆叠）----
    val columnCount = (columns.maxOrNull() ?: 0) + 1
    val totalWidth = COLUMN_SPACING * (columnCount - 1)
    val centerX0 = if (config.width > 0.dp) (config.width - totalWidth) / 2f else Dp(0f)
    val positions = mutableMapOf<String, DpOffset>()
    columnOrder.forEach { (col, list) ->
        val x = COLUMN_SPACING * col + centerX0
        var y = 0f
        list.forEach { n ->
            positions[n.id] = DpOffset(x, Dp(y))
            y += (n.dimensions?.height ?: CARD_HEIGHT.dp).value + ROW_SPACING.value
        }
    }

    val laidNodes = nodes.map { it.copy(position = positions.getValue(it.id)) }
    // 重建并分类边：FORWARD / BACK / CROSS / SELF_LOOP 定档后，BACK 边由面板标橙。
    buildKuiverWithClassifiedEdges(laidNodes, kuiver.edges.toList())
}

/** 列间距（320）：给 S 形曲线留足横向行程，绕开中间列的卡片。 */
private val COLUMN_SPACING = 320.dp

/** 行间距（100）：卡片间留出纵向空隙，水平切出 / 切入的曲线从行间隙穿过。 */
private val ROW_SPACING = 100.dp
