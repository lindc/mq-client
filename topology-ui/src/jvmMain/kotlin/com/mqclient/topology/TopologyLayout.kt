package com.mqclient.topology

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.buildKuiverWithClassifiedEdges
import com.dk.kuiver.model.layout.LayoutConfig
import com.mqclient.graph.TopologyNode

/**
 * 带类型语义的布局函数：`nodeId → 节点（含业务类型）` 随每次调用**显式传入**。
 *
 * kuiver 的 `LayoutProvider` 只看得到通用节点（没有 type 字段），而固定列序需要类型
 * 语义。这个类型别名把「图 + 配置 + 节点表」一起交出去，调用方（[TopologyCanvas]）
 * 用 `rememberUpdatedState` 把当前图的节点表**捕获进闭包**——于是：
 *
 *  - 不再需要各面板维护 `internal var xxxTopologyNodeTypes` 这类模块级可变表；
 *  - 布局可以在后台线程跑（kuiver 本身就 `withContext(Dispatchers.Default)`），
 *    闭包读的是自己的不可变快照，不存在「另一个面板刚把它改掉」的窗口；
 *  - 窗口 resize 触发的重新布局也拿得到正确的类型表（旧方案只在 data 变化时写入，
 *    resize 时读到的是**上一次写入的值**，多连接并存时会串图）。
 */
typealias TopologyLayoutProvider = (
    kuiver: Kuiver,
    config: LayoutConfig,
    nodes: Map<String, TopologyNode>,
) -> Kuiver

/**
 * 通用「按列分层」布局：`columnOf` 给出每个节点的列号（0 = 最左），列内纵向堆叠。
 *
 * 排序规则（两级）：第 0 列按 id（名称）**稳定**排序；其余列按**邻居 y 均值**单趟
 * 重心 —— 前面的列先排定，后面的列跟着自己的左邻列走，减少长跨线；无邻居的保持 id 序。
 *
 * 为什么做成 N 列而不是两列：两列只够表达 `topic | broker` 这类单一流向；StarRocks
 * 的集群拓扑是三层（外部 Catalog → FE → BE/CN），MQ 的固定 9 列布局又是它自己的
 * 定制实现。这里给通用算法，列数交给业务模块的谓词决定。
 *
 * 节点坐标直接写入并经 [buildKuiverWithClassifiedEdges] 重建，边类型（FORWARD /
 * BACK / CROSS / SELF_LOOP）随之定档 —— 跨列回流这类反向边会标 BACK 标橙。
 *
 * @param columnOf 节点 → 列号（负数按 0 处理，无需连续）。谓词由业务模块给出 ——
 *   「哪种类型在哪一列」是业务语义，不归渲染层。
 */
fun layeredLayoutProvider(columnOf: (TopologyNode) -> Int): TopologyLayoutProvider =
    labeled@{ kuiver, config, nodeTable ->
    val nodes = kuiver.nodes.values.toList()
    if (nodes.isEmpty()) return@labeled kuiver

    // ---- 邻接表（无向，算列内重心用）----
    val neighbors = mutableMapOf<String, MutableList<String>>()
    kuiver.edges.forEach { edge ->
        neighbors.getOrPut(edge.fromId) { mutableListOf() }.add(edge.toId)
        neighbors.getOrPut(edge.toId) { mutableListOf() }.add(edge.fromId)
    }

    val columnOrder: MutableMap<Int, MutableList<String>> = nodes
        // 缺项的节点按「未标注」处理（列号由业务谓词决定，通常兜底到 0），
        // 与旧实现对未知类型的行为一致。
        .groupBy { node -> columnOf(nodeTable[node.id] ?: TopologyNode(node.id)).coerceAtLeast(0) }
        .mapValues { (_, ns) -> ns.map { it.id }.toMutableList() }
        .toMutableMap()
    val columns = columnOrder.keys.sorted()
    columnOrder[0]?.sort() // 首列按 id（名称）稳定排序

    // ---- 纵向堆叠 + 逐列重心 ----
    val yOf = mutableMapOf<String, Float>()
    fun restack(column: Int) {
        var y = 0f
        columnOrder[column]?.forEach { id ->
            yOf[id] = y
            y += NODE_HEIGHT.value + ROW_SPACING.value
        }
    }
    columns.forEach(::restack)

    fun barycenter(id: String): Float {
        val ys = neighbors[id]?.mapNotNull { yOf[it] } ?: emptyList()
        return if (ys.isEmpty()) yOf[id] ?: 0f else ys.average().toFloat()
    }

    // 首列已按 id 定序，其余列依次按重心微调（量化避免浮点抖动）。
    columns.filter { it != 0 }.forEach { column ->
        columnOrder[column]?.sortBy { (barycenter(it) * 1000).toInt() }
        restack(column)
    }

    // ---- 落坐标（列 x 居中；列内 y 依最终顺序堆叠）----
    val columnCount = (columns.maxOrNull() ?: 0) + 1
    val totalWidth = COLUMN_SPACING * (columnCount - 1)
    val centerX0 = if (config.width > 0.dp) (config.width - totalWidth) / 2f else Dp(0f)
    val positions = mutableMapOf<String, DpOffset>()
    columnOrder.forEach { (column, ids) ->
        val x = COLUMN_SPACING * column + centerX0
        var y = 0f
        ids.forEach { id ->
            positions[id] = DpOffset(x, Dp(y))
            y += NODE_HEIGHT.value + ROW_SPACING.value
        }
    }

    val laidNodes = nodes.map { it.copy(position = positions.getValue(it.id)) }
    buildKuiverWithClassifiedEdges(laidNodes, kuiver.edges.toList())
}

/**
 * 「左列 = 主角，右列 = 另一端」的两列布局（Kafka / RocketMQ 的 topic|broker、
 * RabbitMQ 的 exchange|queue、StarRocks 早期的 FE|BE 都是这个形状）。
 *
 * 退化为 [layeredLayoutProvider] 的两列特例 —— 列宽 / 行距 / 排序结果逐字不变，
 * 已有面板零感知。
 */
fun twoColumnLayoutProvider(
    inSecondColumn: (TopologyNode) -> Boolean,
): TopologyLayoutProvider = layeredLayoutProvider { node -> if (inSecondColumn(node)) 1 else 0 }

/** 卡片高度：对齐 [CARD_HEIGHT]（布局与卡片尺寸必须一致）。 */
private val NODE_HEIGHT = CARD_HEIGHT.dp

/** 行间距（100dp）：水平切入 / 切出的曲线从行间隙穿过。 */
private val ROW_SPACING = 100.dp

/** 列间距（320dp）：给 S 形曲线留足横向行程，绕开中间列的卡片。 */
private val COLUMN_SPACING = 320.dp
