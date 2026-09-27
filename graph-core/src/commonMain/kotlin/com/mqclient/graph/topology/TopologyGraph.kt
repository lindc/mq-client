package com.mqclient.graph.topology

import kotlinx.serialization.Serializable

/**
 * 拓扑图的通用数据模型（点与边）。**与中间件无关**：核心层不认识任何具体 MQ / 中间件类型，
 * 只用 [TopologyNode.type] 这个字符串判别符 + [TopologyNode.properties] 扩展字段来描述节点。
 *
 * 设计约束：
 * - 不依赖任何 UI 框架（Compose / Swing 都不出现），也不依赖任何具体 MQ / 中间件客户端库。
 * - 当前只服务「单个 QMgr 内的局部链路」视图：从某对象出发，向上游/下游各展开一层或两层。
 * - 数据结构保持纯粹：只描述「是什么」，不含「怎么画」「点了怎么办」。
 *
 * 放在独立的 graph-core 模块（纯数据、零业务依赖）。各中间件 service 模块把自己的领域对象
 * 映射成下面的通用结构（按自身概念定义 [TopologyNode.type] 取值），统一由 topology-ui 渲染；
 * 新增一种中间件无需改动本数据模型。
 */

/** 节点的语义颜色。用语义枚举而非具体 Color：与主题解耦、可序列化、跨 UI 框架统一映射。 */
@Serializable
enum class NodeColor {
    NORMAL,
    PRIMARY,
    WARNING,
    ERROR,
    MUTED,
}

/**
 * 节点状态。状态属于「节点」而非「边」——颜色/状态都挂在节点上，边只表达两端的连接关系。
 */
@Serializable
enum class NodeStatus {
    RUNNING,
    STOPPED,
    RETRYING,
    STARTING,
    STOPPING,
    UNKNOWN,
}

/** 边的语义颜色（同 [NodeColor] 的语义化考量）。 */
@Serializable
enum class EdgeColor {
    NORMAL,
    PRIMARY,
    WARNING,
    ERROR,
    MUTED,
}

/** 边的线型。 */
@Serializable
enum class EdgeStyle {
    SOLID,
    DASHED,
}

/**
 * 拓扑中的一个节点。
 *
 * 以 [name] 作为唯一标识：单 QMgr 视图下对象名唯一，故不另设 id 字段；
 * 边也用 name 引用节点。所有节点同形状，靠 [type] 解析的图标 + [color] 区分类型。
 *
 * [type] 是不透明的**字符串判别符**（如 `"queue"` / `"xmitq"` / `"alias_queue"`），由各个中间件
 * 自己定义取值；核心层与渲染器不直接识别其具体含义，而是通过各中间件的适配器（图标/颜色注册表）
 * 来映射 —— 这样新增一种中间件无需改动本数据模型，满足「核心层不认识具体中间件类型」。
 *
 * [properties] 是可扩展的键值对（端口、通道类型、队列深度、计数等），承载各中间件特有的展示
 * 信息；核心层不解析其具体内容，仅供 UI 按需读取。
 *
 * [objectRef] 指向原始中间件对象（用于 UI 点击后跳转到对应对象的详情），可空——纯展示节点可不带引用。
 */
@Serializable
data class TopologyNode(
    /** 唯一标识，单 QMgr 内唯一。同时作为边引用的端点。 */
    val name: String,
    /** 不透明类型判别符（中间件自定义取值，核心层不识别具体含义）。 */
    val type: String,
    val color: NodeColor = NodeColor.NORMAL,
    val status: NodeStatus? = null,
    /** 角标文案（如深度、计数等短标注），可选。 */
    val badge: String? = null,
    /** 各中间件特有的扩展信息（端口、通道类型、深度等），核心层不解析。 */
    val properties: Map<String, String> = emptyMap(),
    /** 指向原始中间件对象的引用，用于点击跳转；纯展示节点可为 null。 */
    val objectRef: ObjectRef? = null,
)

/**
 * 拓扑中的一条边（连线）。
 *
 * 边不携带 type 与 status：关系类型可由两端节点类型推断
 * （如 `queue → xmitq → channel` 表达「远程队列经传输队列与通道发出」），
 * 状态属于节点而非连线。边只描述「谁连到谁」以及可选的视觉样式。
 */
@Serializable
data class TopologyEdge(
    /** 起点节点 name。 */
    val from: String,
    /** 终点节点 name。 */
    val to: String,
    val color: EdgeColor? = null,
    val style: EdgeStyle? = null,
)

/**
 * 一张拓扑图：单个连接（QMgr）内的局部链路。
 *
 * [connectionId] 标识这张图属于哪个连接；节点名在该连接内唯一。
 */
@Serializable
data class TopologyGraph(
    val connectionId: String,
    val nodes: List<TopologyNode>,
    val edges: List<TopologyEdge>,
)

/**
 * 指向原始中间件对象的引用。
 *
 * [objectType] 同样是不透明字符串判别符（与 [TopologyNode.type] 同源），使 UI 层跳转时
 * 无需感知 IBM MQ / Kafka 等差异，交给各中间件的详情页按 [objectType] 自行解析。
 */
@Serializable
data class ObjectRef(
    val connectionId: String,
    val objectType: String,
    val objectName: String,
)

/** 返回一张空图（无节点、无边）。 */
fun emptyGraph(connectionId: String): TopologyGraph =
    TopologyGraph(connectionId = connectionId, nodes = emptyList(), edges = emptyList())

/**
 * 示例图：展示两条典型链路，供后续单元测试参考。
 *
 * 1) alias_queue → queue（别名队列指向本地队列）
 * 2) remote_queue → xmitq → channel（远程队列经传输队列与通道发出）
 *
 * [TopologyNode.type] 使用字符串判别符，体现「核心层不认识具体中间件类型」：
 * 这里的取值只是 IBM MQ 概念的一种映射示例，Kafka 等可定义自己的取值。
 */
fun sampleGraph(connectionId: String = "sample"): TopologyGraph {
    val nodes = listOf(
        TopologyNode(name = "ALIAS.LOCAL", type = "alias_queue"),
        TopologyNode(name = "LOCAL.Q", type = "queue", color = NodeColor.PRIMARY, status = NodeStatus.RUNNING),
        TopologyNode(name = "REMOTE.OUT", type = "remote_queue"),
        TopologyNode(name = "XMIT.Q", type = "xmitq"),
        TopologyNode(name = "SENDER.CHANNEL", type = "channel", color = NodeColor.WARNING, status = NodeStatus.RETRYING),
    )
    val edges = listOf(
        TopologyEdge(from = "ALIAS.LOCAL", to = "LOCAL.Q"),
        TopologyEdge(from = "REMOTE.OUT", to = "XMIT.Q"),
        TopologyEdge(from = "XMIT.Q", to = "SENDER.CHANNEL", style = EdgeStyle.DASHED),
    )
    return TopologyGraph(connectionId = connectionId, nodes = nodes, edges = edges)
}
