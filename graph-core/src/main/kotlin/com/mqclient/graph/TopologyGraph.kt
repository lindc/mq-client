package com.mqclient.graph

import com.dk.kuiver.model.Kuiver

/**
 * 拓扑图的统一节点类型词汇（各中间件映射到这里，渲染层按它取图标 / 强调色）。
 *
 * 当前值来自 IBM MQ 的**业务链路角色**——alias / remote / xmitq 在 MQ 里不是独立
 * 对象类型，而是 QUEUE + Q_TYPE / USAGE 的组合，这里把它们提升成一等节点角色；
 * REMOTE_QMGR / QMGR_* 是从通道或集群关系推导出的**虚拟节点**（MQ 里没有这个对象）。
 * 其他中间件（Kafka broker / RabbitMQ exchange…）接入时按语义映射到既有值或新增值。
 *
 * IBM MQ 侧只画业务链路：Listener / Service / Process / Namelist / AuthInfo /
 * CommInfo / QMODEL 一律不进图（属性在对象面板单独看）。
 */
enum class TopologyNodeType {
    ALIAS_QUEUE,
    LOCAL_QUEUE,
    REMOTE_QUEUE,
    TRANSMISSION_QUEUE,
    TOPIC,
    SUBSCRIPTION,
    CHANNEL,
    REMOTE_QMGR,
    /** 集群关系图专用：完整仓库 QMgr。 */
    QMGR_FULL,
    /** 集群关系图专用：部分仓库 QMgr。 */
    QMGR_PARTIAL,

    // ---- Kafka（消息链路里 TOPIC 与 MQ 同源复用）----
    /** Kafka 分区（leader / 副本分布的载体）。 */
    PARTITION,
    /** Kafka broker。 */
    BROKER,
    /** Kafka consumer group（消费关系图用）。 */
    CONSUMER_GROUP,

    // ---- RabbitMQ（AMQP 0.9.1 的路由链路：exchange → binding → queue）----
    /** RabbitMQ 交换机（路由 / 分发角色；含 name="" 的默认交换机）。 */
    EXCHANGE,
    /** RabbitMQ 队列（区别于 IBM MQ 的 LOCAL_QUEUE：AMQP 0.9.1 语义）。 */
    RABBIT_QUEUE,

    // ---- OLAP（StarRocks FE / BE 集群拓扑）----
    /** Frontend：查询接入 / 元数据节点，星形拓扑的中心。 */
    OLAP_FE,
    /** Backend：存储 / 计算节点，作为 FE 的子节点连线。 */
    OLAP_BE,
}

/**
 * 节点告警级别（健康态之外的两种异常）：
 *  - WARNING：local queue 消息积压（currentDepth/maxDepth ≥ 连接告警阈值）；
 *  - ERROR：sender/requester 通道状态 STOPPED / RETRYING（链路故障）。
 * 与对象列表页的告警语义同源（队列列表红色警告图标 / 通道状态色）。
 */
enum class NodeSeverity {
    NORMAL,
    WARNING,
    ERROR,
}

/**
 * 一次拓扑图构建的完整产物：kuiver 图模型 + 节点类型表 + 告警级别表
 * （节点卡片着色与列布局都靠后两者）。各中间件的 builder 产出它，渲染面板消费它。
 */
data class TopologyData(
    val kuiver: Kuiver,
    val nodeTypes: Map<String, TopologyNodeType>,
    val severities: Map<String, NodeSeverity>,
    /**
     * 节点辅助信息徽标（如 Kafka topic 的分区数、partition 的 leader id）。
     * 渲染在节点卡片名称下方的小字；不传则卡片维持纯名称形态（IBM MQ 不用）。
     */
    val badges: Map<String, String> = emptyMap(),
)
