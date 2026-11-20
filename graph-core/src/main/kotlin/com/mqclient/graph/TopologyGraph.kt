package com.mqclient.graph

import com.dk.kuiver.model.Kuiver

/**
 * 拓扑节点的**开放**类型标识：各业务模块用自己的枚举实现它
 * （`MqNodeType` / `KafkaNodeType` / `RabbitMqNodeType` / `RocketMqNodeType` /
 * `OlapNodeType` …）。
 *
 * 这一层只承诺「节点有类型」，不承诺「有哪些类型」——图标、强调色、列布局语义
 * 全部归业务模块（见 topology-ui 的 `TopologyNodeStyleProvider`）。
 * 新增一个中间件时，本文件与 topology-ui 都不需要改动。
 *
 * 实现方约定：用 `enum class` 实现，并用自己的枚举做穷尽 `when` 映射图标 / 颜色，
 * 这样漏配一个类型是**编译错误**（编译期兜底留在业务侧，不再集中在渲染层）。
 */
interface TopologyNodeType {
    /**
     * 类型标签（详情卡副标题 + 图标无障碍描述）。
     *
     * 默认取枚举常量名——与重构前各面板显示的 `TopologyNodeType.name` 完全一致，
     * 业务侧不写任何额外代码即可保持视觉不变。
     */
    val label: String get() = (this as? Enum<*>)?.name ?: toString()
}

/**
 * 节点告警级别（健康态之外的两种异常）：
 *  - WARNING：业务告警（队列积压 / 分区欠副本 / 队列堆积无人消费 …）；
 *  - ERROR：链路或节点故障（通道 STOPPED / 无 leader / 节点不存活 …）。
 * 级别由业务 builder 判定，渲染层只负责着色（覆盖类型色）。
 */
enum class NodeSeverity {
    NORMAL,
    WARNING,
    ERROR,
}

/**
 * 渲染所需的节点视图：id + 业务类型 + 告警级别 + 徽标。
 *
 * [type] 为 null 表示**未标注**（渲染层走兜底样式，见 `FallbackTopologyNodeStyle`）；
 * 正常流程下 builder 保证每个节点都有类型。
 */
data class TopologyNode(
    val id: String,
    val type: TopologyNodeType? = null,
    val severity: NodeSeverity = NodeSeverity.NORMAL,
    /**
     * 节点辅助信息徽标（如 Kafka topic 的分区数、broker 地址）。
     * 渲染在节点卡片名称下方的小字；null 时卡片维持纯名称形态。
     */
    val badge: String? = null,
)

/**
 * 一次拓扑图构建的完整产物：kuiver 图模型 + 节点表（类型 / 告警 / 徽标都在节点上）。
 * 各中间件的 builder 产出它，渲染面板消费它。
 *
 * 原 `nodeTypes` / `severities` / `badges` 三张平行 map 合并为一张：三者在同一 id 上
 * 必须同步维护（每个 builder 各写一遍 putIfAbsent），放一个数据类是唯一真相。
 */
data class TopologyData(
    val kuiver: Kuiver,
    val nodes: Map<String, TopologyNode>,
)
