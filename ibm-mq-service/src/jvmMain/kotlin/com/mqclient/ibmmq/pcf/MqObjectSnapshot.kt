package com.mqclient.ibmmq.pcf

/**
 * MQ 对象类型（慢扫覆盖的全部 INQUIRE 目标）。
 *
 * 状态语义不在枚举里 —— 每类的「状态」含义不同（通道 RUNNING / 监听器 RUNNING /
 * 队列深度占比…），由 UI 侧按 [MqObjectSnapshot] 的强类型字段或 attributes 自行解释。
 */
enum class MqObjectType {
    QUEUE_MANAGER,
    QUEUE,
    CHANNEL,
    TOPIC,
    SUBSCRIPTION,
    LISTENER,
    SERVICE,
    PROCESS,
    NAMELIST,
    AUTH_INFO,
    COMM_INFO,
}

/**
 * 一个 MQ 对象的完整快照：PCF INQUIRE 响应的全量属性 + 常用字段强类型提升。
 *
 * [attributes] 的键是可读属性名（[MqAttributeNameMapper] 把 PCF 参数 ID 转成
 * `MQCA_Q_NAME` 这类名字），值是 String / Int / List 等可读形态（byte[] 已解码）。
 * 属性 + 状态两条响应合并时，状态参数直接覆盖同名属性键 —— 状态更新鲜。
 */
data class MqObjectSnapshot(
    /** 对象名字。 */
    val name: String,
    /** 对象类型。 */
    val type: MqObjectType,
    /** 全量属性（INQUIRE 属性响应 + 状态响应合并）。 */
    val attributes: Map<String, Any?> = emptyMap(),

    /** 对象描述（各类型都有 DESC 类参数；缺失为 null）。 */
    val description: String? = null,

    // ---- Queue 专用 ----
    /** 当前深度（MQIA_CURRENT_Q_DEPTH，来自 Q_STATUS）。 */
    val currentDepth: Int? = null,
    /** 最大深度（MQIA_MAX_Q_DEPTH）。 */
    val maxDepth: Int? = null,
    /** 消息数 —— 与 currentDepth 同值，给 UI 一个语义化的别名。 */
    val messageCount: Int? = null,
    /** 打开的句柄数（openInput + openOutput，Q_STATUS）。 */
    val consumerCount: Int? = null,

    // ---- Channel 专用 ----
    /** 通道运行状态的可读名（RUNNING / RETRYING / STOPPED…）。 */
    val channelStatus: String? = null,
    /** 通道类型可读名（SENDER / RECEIVER / CLUSRCVR…）。 */
    val channelType: String? = null,

    // ---- Listener 专用 ----
    /** 监听器运行状态可读名（RUNNING / STOPPED…）。 */
    val listenerStatus: String? = null,
    /** 监听端口。 */
    val port: Int? = null,
)
