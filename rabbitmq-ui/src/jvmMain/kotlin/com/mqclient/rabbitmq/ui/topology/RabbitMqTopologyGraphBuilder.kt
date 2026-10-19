package com.mqclient.rabbitmq.ui.topology

import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import com.dk.kuiver.model.buildKuiver
import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyData
import com.mqclient.graph.TopologyNodeType
import com.mqclient.rabbitmq.RabbitMqBindingSnapshot
import com.mqclient.rabbitmq.RabbitMqExchangeSnapshot
import com.mqclient.rabbitmq.RabbitMqQueueSnapshot
import com.mqclient.rabbitmq.RabbitMqQueueStatusSnapshot
import com.mqclient.topology.CARD_HEIGHT
import com.mqclient.topology.CARD_WIDTH

/**
 * 拓扑图 badge / 展示文案的取词口径：builder 在组合外执行，模板由面板用
 * stringResource 解析好再传进来（语言切换随重组自然刷新）。
 */
class RabbitMqTopologyStrings(
    /** queue 节点 badge：「消息 %1$d」。 */
    val messagesBadge: (Long) -> String,
)

/**
 * 一次缓存读取的完整快照（纯数据，拓扑图构建吃它）。
 */
data class RabbitMqTopologySnapshot(
    val exchanges: List<RabbitMqExchangeSnapshot>,
    val queues: List<RabbitMqQueueSnapshot>,
    /** 全量绑定（含 exchange 目标）；builder 只取 destinationType=queue 的部分。 */
    val bindings: List<RabbitMqBindingSnapshot>,
    /** 队列状态（快扫）：badge 消息数与 severity 的数据源。 */
    val queueStatus: Map<String, RabbitMqQueueStatusSnapshot>,
) {
    val isEmpty: Boolean get() = exchanges.isEmpty() && queues.isEmpty()
}

/**
 * 从 [RabbitMqTopologySnapshot] 构建 RabbitMQ 拓扑图（纯函数，jvmTest 直测）。
 *
 * 结构：两列路由链路 `EXCHANGE —(binding)→ RABBIT_QUEUE`：
 *  - exchange 节点：id `x:<name>`（name="" 的默认交换机仅在 [showSystemObjects]
 *    开启时进图，显示名取 [RabbitMqTopologyStrings] 的默认交换机标记之外由面板
 *    displayName 处理）；`amq.*` 系统交换机同受开关控制；
 *  - queue 节点：id `q:<name>`，**badge = 当前消息数**（快扫计数）；
 *  - 边：bindings 里 destinationType=queue 的部分，`x:<source> → q:<destination>`；
 *    source 不在可见交换机内（系统对象被过滤）或目标队列不存在（retention 清除等）
 *    时跳过；**exchange → exchange 绑定第一阶段不画**（同列边布局语义未定，后续再议）；
 *  - 孤立节点（无绑定）照常显示，落在各自列尾。
 *
 * 告警级别（[NodeSeverity]，与概览健康度同口径）：
 *  - queue：`messages > 0 && consumers == 0` → WARNING（有堆积没人消费）；
 *    state == "down" / "flow"（阻塞）/ "crashed" → ERROR；快扫计数未覆盖（status
 *    缺该队列）→ NORMAL 不误判；
 *  - exchange：恒 NORMAL。
 *
 * 限制：kuiver 的 EdgeStyle 不支持边标签 —— Routing Key 第一阶段不上图
 * （直连场景通常等于队列名；选中节点经底部详情卡看属性）。
 *
 * 节点 id 约定：RabbitMQ 对象名可含 `:`（vhost 路径风格不常见但合法），前缀
 * `x:` / `q:` 只用于防两类节点 id 互相冲突，不做反解。
 */
fun buildKuiverFromSnapshot(
    snapshot: RabbitMqTopologySnapshot,
    strings: RabbitMqTopologyStrings,
    showSystemObjects: Boolean = false,
): TopologyData {
    val isSystemExchange = { name: String -> name.isEmpty() || name.startsWith("amq.") }
    val visibleExchanges = snapshot.exchanges
        .filter { showSystemObjects || !isSystemExchange(it.name) }
        .associateBy { it.name }
    val visibleQueues = snapshot.queues.associateBy { it.name }
    if (visibleExchanges.isEmpty() && visibleQueues.isEmpty()) {
        return TopologyData(Kuiver(), emptyMap(), emptyMap())
    }

    val nodeTypes = LinkedHashMap<String, TopologyNodeType>()
    val severities = LinkedHashMap<String, NodeSeverity>()
    val badges = LinkedHashMap<String, String>()

    fun put(id: String, type: TopologyNodeType, severity: NodeSeverity = NodeSeverity.NORMAL, badge: String? = null) {
        nodeTypes.putIfAbsent(id, type)
        severities.putIfAbsent(id, severity)
        badge?.let { badges.putIfAbsent(id, it) }
    }

    // ---- exchange 节点 ----
    visibleExchanges.values.forEach { exchange ->
        put(exchangeNodeId(exchange.name), TopologyNodeType.EXCHANGE)
    }

    // ---- queue 节点（badge = 消息数）----
    visibleQueues.values.forEach { queue ->
        val status = snapshot.queueStatus[queue.name]
        put(
            id = queueNodeId(queue.name),
            type = TopologyNodeType.RABBIT_QUEUE,
            severity = queueSeverity(queue, status),
            badge = status?.let { strings.messagesBadge(it.messages) },
        )
    }

    // ---- 边：binding（仅 queue 目标）----
    val edges = LinkedHashSet<Pair<String, String>>()
    fun link(from: String, to: String) {
        if (from != to && from in nodeTypes && to in nodeTypes) edges += from to to
    }
    snapshot.bindings.forEach { binding ->
        if (binding.destinationType != "queue") return@forEach
        // 系统对象被过滤时，指向它们的边一并隐藏（含默认交换机的自动绑定）。
        if (!showSystemObjects && isSystemExchange(binding.source)) return@forEach
        link(exchangeNodeId(binding.source), queueNodeId(binding.destination))
    }

    val kuiver = buildKuiver {
        nodeTypes.forEach { (id, _) ->
            addNode(KuiverNode(id, NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp)))
        }
        edges.forEach { (from, to) -> addEdge(KuiverEdge(fromId = from, toId = to)) }
    }
    return TopologyData(kuiver, nodeTypes, severities, badges)
}

/**
 * queue 健康级别：有堆积且无消费者 → WARNING；state=down/flow/crashed（不可用或阻塞）→ ERROR；
 * 快扫计数未覆盖（刚建 / 刚删窗口期）→ NORMAL 不误判。
 */
private fun queueSeverity(
    queue: RabbitMqQueueSnapshot,
    status: RabbitMqQueueStatusSnapshot?,
): NodeSeverity = when {
    // down（节点不可用）/ flow（内存/磁盘告警发布被阻塞）与队列卡片色点同一口径。
    queue.state.equals("down", ignoreCase = true) ||
        queue.state.equals("flow", ignoreCase = true) ||
        queue.state.equals("crashed", ignoreCase = true) -> NodeSeverity.ERROR
    status != null && status.messages > 0 && status.consumers == 0 -> NodeSeverity.WARNING
    else -> NodeSeverity.NORMAL
}

/** exchange 节点 id（`x:` 前缀与 queue 节点区分）。 */
fun exchangeNodeId(name: String): String = "x:$name"

/** queue 节点 id（`q:` 前缀）。 */
fun queueNodeId(name: String): String = "q:$name"
