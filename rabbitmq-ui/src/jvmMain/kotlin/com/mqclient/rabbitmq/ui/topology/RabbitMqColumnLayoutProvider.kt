package com.mqclient.rabbitmq.ui.topology

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.buildKuiverWithClassifiedEdges
import com.dk.kuiver.model.layout.LayoutProvider
import com.mqclient.graph.TopologyNodeType
import com.mqclient.topology.CARD_HEIGHT

/**
 * 当前图的 `nodeId → 类型` 查找表，供 [rabbitMqColumnLayoutProvider] 在布局阶段读取。
 *
 * 与 kafka-ui 的 `kafkaTopologyNodeTypes` 同一思路：kuiver 的 LayoutProvider 只看得到
 * 通用节点（无 type 字段），固定列序需要类型语义。**线程/时序不变量**：写入（面板经
 * TopologyCanvas 的 onBeforeLayout 钩子）与消费（紧随的 relayout → provider 同步执行）
 * 必须保持主线程相邻语句、中间无挂起点 —— 该相邻性由 TopologyCanvas 的 LaunchedEffect
 * 保证；若未来把布局挪到后台，必须先把本表改为随图传递。
 */
internal var rabbitMqTopologyNodeTypes: Map<String, TopologyNodeType> = emptyMap()

/**
 * RabbitMQ 拓扑的「按角色分两列」布局（TopologyCanvas 的 LayoutConfig.Custom 用）。
 *
 * 列序固定（左 → 右）：
 *
 * ```
 * exchange | queue
 *     0    |   1
 * ```
 *
 * 同列纵向排序：
 *  1. exchange 列按名称；queue 列按**邻居 y 均值**（exchange 列先排定，单趟重心即
 *     大幅减少交叉）——队列跟着自己的上游交换机走；无邻居的按名称兜底。
 *  2. 边类型（FORWARD / BACK / CROSS）由 [buildKuiverWithClassifiedEdges] 按最终
 *     坐标定档（binding 方向与列序一致，绝大多数为 FORWARD 实线箭头）。
 *
 * 节点坐标由本 provider 直接写入并经 [buildKuiverWithClassifiedEdges] 重建。
 */
val rabbitMqColumnLayoutProvider: LayoutProvider = labeled@{ kuiver, config ->
    val nodes = kuiver.nodes.values.toList()
    if (nodes.isEmpty()) return@labeled kuiver
    val types = rabbitMqTopologyNodeTypes

    // ---- 邻接表（无向，算 queue 重心用）----
    val neighbors = mutableMapOf<String, MutableList<String>>()
    kuiver.edges.forEach { e ->
        neighbors.getOrPut(e.fromId) { mutableListOf() }.add(e.toId)
        neighbors.getOrPut(e.toId) { mutableListOf() }.add(e.fromId)
    }

    // ---- 列分配：exchange=0 / queue=1，其余类型兜底 0 ----
    fun getColumn(id: String): Int = when (types[id]) {
        TopologyNodeType.RABBIT_QUEUE -> 1
        else -> 0
    }

    val columns: Map<Int, MutableList<String>> = nodes
        .groupBy { getColumn(it.id) }
        .mapValues { (_, ns) -> ns.map { it.id }.toMutableList() }
    columns[0]?.sort() // exchange 列按 id（名称）稳定排序
    columns[1]?.sort() // queue 列先占位后重心

    // ---- 纵向堆叠 + queue 单趟重心 ----
    val yOf = mutableMapOf<String, Float>()
    fun restack(col: Int) {
        var y = 0f
        columns[col]?.forEach { id ->
            yOf[id] = y
            y += NODE_HEIGHT.value + ROW_SPACING.value
        }
    }
    (0..1).forEach(::restack)

    fun barycenter(id: String): Float {
        val ys = neighbors[id]?.mapNotNull { yOf[it] } ?: emptyList()
        return if (ys.isEmpty()) yOf[id] ?: 0f else ys.average().toFloat()
    }

    // queue 列：exchange 列的 y 已定，按邻居 y 均值一趟排定（无邻居的保持 id 序）。
    columns[1]?.sortBy { (barycenter(it) * 1000).toInt() } // 量化避免浮点抖动
    restack(1)

    // ---- 落坐标（列 x 居中；列内 y 依最终顺序堆叠）----
    val totalWidth = COLUMN_SPACING
    val centerX0 = if (config.width > 0.dp) (config.width - totalWidth) / 2f else Dp(0f)
    val positions = mutableMapOf<String, DpOffset>()
    columns.forEach { (col, ids) ->
        val x = COLUMN_SPACING * col + centerX0
        var y = 0f
        ids.forEach { id ->
            positions[id] = DpOffset(x, Dp(y))
            y += NODE_HEIGHT.value + ROW_SPACING.value
        }
    }

    val laidNodes = nodes.map { it.copy(position = positions.getValue(it.id)) }
    buildKuiverWithClassifiedEdges(laidNodes, kuiver.edges.toList())
}

/** 卡片高度：对齐 topology-ui 的 CARD_HEIGHT（布局与卡片尺寸必须一致）。 */
private val NODE_HEIGHT = CARD_HEIGHT.dp

/** 行间距：同 kafka / MQ 拓扑的 100dp。 */
private val ROW_SPACING = 100.dp

/** 列间距：同 kafka / MQ 拓扑的 320dp。 */
private val COLUMN_SPACING = 320.dp
