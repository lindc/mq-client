package com.mqclient.topology

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.buildKuiverWithClassifiedEdges
import com.dk.kuiver.model.layout.LayoutConfig
import com.mqclient.graph.TopologyNodeType

/**
 * 带类型语义的布局函数：`nodeId → 类型` 随每次调用**显式传入**。
 *
 * kuiver 的 `LayoutProvider` 只看得到通用节点（没有 type 字段），而固定列序需要类型
 * 语义。这个类型别名把「图 + 配置 + 类型表」一起交出去，调用方（[TopologyCanvas]）
 * 用 `remember(kuiver, nodeTypes)` 把当前图的类型表**捕获进闭包**——于是：
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
    nodeTypes: Map<String, TopologyNodeType>,
) -> Kuiver

/**
 * 「左列 = 主角，右列 = 另一端」的两列布局（Kafka / RocketMQ 的 topic|broker、
 * RabbitMQ 的 exchange|queue 都是这个形状）。
 *
 * 列序固定（左 → 右）0 | 1：命中 [inSecondColumn] 的节点进第 1 列，其余兜底第 0 列。
 * 同列纵向排序：第 0 列按 id（名称）稳定排序；第 1 列按**邻居 y 均值**单趟重心
 * （第 0 列先排定，右列跟着自己的左列邻居走，减少长跨线），无邻居的保持 id 序。
 *
 * 节点坐标直接写入并经 [buildKuiverWithClassifiedEdges] 重建，边类型（FORWARD /
 * BACK / CROSS / SELF_LOOP）随之定档 —— 聚合视图里 broker→topic 这类反向边会标
 * BACK 标橙，与 MQ 拓扑同一语义。
 *
 * @param inSecondColumn 判定节点进右列（其余一律左列，未知类型不必特殊处理）
 */
fun twoColumnLayoutProvider(
    inSecondColumn: (TopologyNodeType?) -> Boolean,
): TopologyLayoutProvider = labeled@{ kuiver, config, types ->
    val nodes = kuiver.nodes.values.toList()
    if (nodes.isEmpty()) return@labeled kuiver

    // ---- 邻接表（无向，算右列重心用）----
    val neighbors = mutableMapOf<String, MutableList<String>>()
    kuiver.edges.forEach { edge ->
        neighbors.getOrPut(edge.fromId) { mutableListOf() }.add(edge.toId)
        neighbors.getOrPut(edge.toId) { mutableListOf() }.add(edge.fromId)
    }

    val columns: Map<Int, MutableList<String>> = nodes
        .groupBy { if (inSecondColumn(types[it.id])) 1 else 0 }
        .mapValues { (_, ns) -> ns.map { it.id }.toMutableList() }
    columns[0]?.sort() // 左列按 id（名称）稳定排序
    columns[1]?.sort() // 右列先占位后重心

    // ---- 纵向堆叠 + 右列单趟重心 ----
    val yOf = mutableMapOf<String, Float>()
    fun restack(column: Int) {
        var y = 0f
        columns[column]?.forEach { id ->
            yOf[id] = y
            y += NODE_HEIGHT.value + ROW_SPACING.value
        }
    }
    (0..1).forEach(::restack)

    fun barycenter(id: String): Float {
        val ys = neighbors[id]?.mapNotNull { yOf[it] } ?: emptyList()
        return if (ys.isEmpty()) yOf[id] ?: 0f else ys.average().toFloat()
    }

    columns[1]?.sortBy { (barycenter(it) * 1000).toInt() } // 量化避免浮点抖动
    restack(1)

    // ---- 落坐标（列 x 居中；列内 y 依最终顺序堆叠）----
    val totalWidth = COLUMN_SPACING
    val centerX0 = if (config.width > 0.dp) (config.width - totalWidth) / 2f else Dp(0f)
    val positions = mutableMapOf<String, DpOffset>()
    columns.forEach { (column, ids) ->
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

/** Kafka / RocketMQ：topic | broker。 */
val topicBrokerLayout: TopologyLayoutProvider =
    twoColumnLayoutProvider { type -> type == TopologyNodeType.BROKER }

/** RabbitMQ：exchange | queue。 */
val exchangeQueueLayout: TopologyLayoutProvider =
    twoColumnLayoutProvider { type -> type == TopologyNodeType.RABBIT_QUEUE }

/**
 * OLAP（StarRocks）：FE | BE 的星形投影 —— 单个 FE 在左列，所有 BE 在右列，
 * 每条 FE→BE 边是一条「接入层 → 存储层」连线。复用通用两列布局，不新造布局算法。
 */
val feBeLayout: TopologyLayoutProvider =
    twoColumnLayoutProvider { type -> type == TopologyNodeType.OLAP_BE }

/** 卡片高度：对齐 [CARD_HEIGHT]（布局与卡片尺寸必须一致）。 */
private val NODE_HEIGHT = CARD_HEIGHT.dp

/** 行间距（100dp）：水平切入 / 切出的曲线从行间隙穿过。 */
private val ROW_SPACING = 100.dp

/** 列间距（320dp）：给 S 形曲线留足横向行程，绕开中间列的卡片。 */
private val COLUMN_SPACING = 320.dp
