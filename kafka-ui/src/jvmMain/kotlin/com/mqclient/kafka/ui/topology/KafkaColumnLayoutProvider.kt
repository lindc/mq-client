package com.mqclient.kafka.ui.topology

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.buildKuiverWithClassifiedEdges
import com.dk.kuiver.model.layout.LayoutProvider
import com.mqclient.graph.TopologyNodeType
import com.mqclient.topology.CARD_HEIGHT

/**
 * 当前图的 `nodeId → 类型` 查找表，供 [kafkaColumnLayoutProvider] 在布局阶段读取。
 *
 * 与 ibm-mq-ui 的 `topologyNodeTypes` 同一思路：kuiver 的 LayoutProvider 只看得到
 * 通用节点（无 type 字段），固定列序需要类型语义。**线程/时序不变量**：写入（面板经
 * TopologyCanvas 的 onBeforeLayout 钩子）与消费（紧随的 relayout → provider 同步执行）
 * 必须保持主线程相邻语句、中间无挂起点——该相邻性由 TopologyCanvas 的 LaunchedEffect
 * 保证；若未来把布局挪到后台，必须先把本表改为随图传递。
 */
internal var kafkaTopologyNodeTypes: Map<String, TopologyNodeType> = emptyMap()

/**
 * Kafka 拓扑的「按角色分两列」布局（TopologyCanvas 的 LayoutConfig.Custom 用）。
 *
 * 列序固定（左 → 右）：
 *
 * ```
 * topic | broker
 *   0    |   1
 * ```
 *
 * 同列纵向排序：topic 列按名称；broker 列按「邻居 y 均值」单趟重心（topic 列先排定，
 * broker 跟着自己的 topic 走，减少长跨线）；无邻居的按名称兜底保持稳定。
 *
 * 节点坐标由本 provider 直接写入并经 [buildKuiverWithClassifiedEdges] 重建，边类型
 * （FORWARD / BACK / CROSS / SELF_LOOP）随之定档（聚合视图的 topic→broker 回边会被
 * 标 BACK 标橙，与 MQ 拓扑同一语义）。
 */
val kafkaColumnLayoutProvider: LayoutProvider = labeled@{ kuiver, config ->
    val nodes = kuiver.nodes.values.toList()
    if (nodes.isEmpty()) return@labeled kuiver
    val types = kafkaTopologyNodeTypes

    // ---- 邻接表（无向，算 broker 重心用）----
    val neighbors = mutableMapOf<String, MutableList<String>>()
    kuiver.edges.forEach { e ->
        neighbors.getOrPut(e.fromId) { mutableListOf() }.add(e.toId)
        neighbors.getOrPut(e.toId) { mutableListOf() }.add(e.fromId)
    }

    // ---- 列分配：topic=0 / broker=1，其余类型兜底 0 ----
    fun getColumn(id: String): Int = when (types[id]) {
        TopologyNodeType.BROKER -> 1
        else -> 0
    }

    val columns: Map<Int, MutableList<String>> = nodes
        .groupBy { getColumn(it.id) }
        .mapValues { (_, ns) -> ns.map { it.id }.toMutableList() }
    columns[0]?.sort() // topic 列按名称
    columns[1]?.sort() // broker 列先占位后重心

    // ---- 纵向堆叠 + broker 单趟重心 ----
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

    // broker 列：topic 列的 y 已定，按邻居 y 均值一趟排定（无邻居的保持 id 序）。
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

/** 行间距：同 MQ 拓扑的 100dp，水平切入 / 切出的曲线从行间隙穿过。 */
private val ROW_SPACING = 100.dp

/** 列间距：同 MQ 拓扑的 320dp，给 S 形曲线留足横向行程。 */
private val COLUMN_SPACING = 320.dp
