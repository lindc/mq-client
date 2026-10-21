package com.mqclient.rocketmq

/**
 * 一个 Broker 的快照（examineBrokerClusterInfo 的投影）。[role] 由 brokerId 推导：
 * 0 = Master，其余 = Slave。
 */
data class RocketMqBrokerSnapshot(
    val cluster: String,
    val brokerName: String,
    val brokerId: Long,
    val address: String,
    val role: String,
)

/** 集群元信息快照：慢扫的 clusterInfo 投影（Phase 1 的版本号暂缺，后续经 broker 运行时信息补）。 */
data class RocketMqClusterInfoSnapshot(
    val clusterName: String?,
    val nameServers: List<String>,
    val brokers: List<RocketMqBrokerSnapshot>,
)

/** 一个 Topic 在单个 Broker 上的路由明细（queueDatas 投影）。 */
data class RocketMqTopicRouteSnapshot(
    val brokerName: String,
    val readQueueNums: Int,
    val writeQueueNums: Int,
    val perm: Int,
)

/** 一个 Topic 的快照（慢扫逐 topic examineTopicRouteInfo 的投影，受限并发）。 */
data class RocketMqTopicSnapshot(
    val name: String,
    /** 全 broker readQueueNums 之和。 */
    val readQueueNums: Int,
    /** 全 broker writeQueueNums 之和。 */
    val writeQueueNums: Int,
    /** 路由权限位（RabbitMQ 式 perm：2 读 / 4 写 / 6 读写）。 */
    val perm: Int,
    /** 按 broker 的路由明细（拓扑图 topic → broker 边的数据源）。 */
    val routes: List<RocketMqTopicRouteSnapshot> = emptyList(),
)

/** 一个消费组的快照（慢扫产物）。 */
data class RocketMqGroupSnapshot(
    val name: String,
    /** 在线成员数（examineConsumerConnectionInfo，best-effort；组离线为 null）。 */
    val memberCount: Int?,
    /**
     * 消费模式原始枚举名（CLUSTERING / BROADCASTING，与组详情快照同口径）；组离线 /
     * 查询失败为 null —— AI 上下文里整体省略该字段。
     */
    val consumeMode: String? = null,
    /** 订阅的 topic 列表（examineConsumerConnectionInfo 的 subscriptionTable，已排序）；离线 / 失败为空。 */
    val subscribedTopics: List<String> = emptyList(),
)

/**
 * 一个消费组的 Lag 快照（快扫产物：examineConsumeStats 的组级投影）。
 *
 * [rows] 是 offsetTable 的拍平行，**落缓存时已按 lag 降序截前 [LAG_ROWS_CACHE_CAP]**
 * —— 只为 AI 上下文与未来的队列级展示服务，控制缓存体积（大扇出组的全量行列表
 * 可能上百条，缓存里留头部就够：积压分析只关心 lag 最大的那些）。
 */
data class RocketMqGroupLagSnapshot(
    val group: String,
    /** 组级 Lag：全 MessageQueue 的 brokerOffset - consumerOffset 之和。 */
    val totalLag: Long,
    /** 参与统计的 MessageQueue 数。 */
    val queueCount: Int,
    /** 按 lag 降序的头部队列行（[LAG_ROWS_CACHE_CAP] 条封顶）；组级查询失败为空。 */
    val rows: List<RocketMqOffsetRowSnapshot> = emptyList(),
) {
    companion object {
        /** 快扫落缓存的每组行数上限（lag 降序截断；AI 构建时还会再截到自己的更小上限）。 */
        internal const val LAG_ROWS_CACHE_CAP = 16
    }
}

/** 消费组在单个 MessageQueue 上的位点行（ConsumeStats.offsetTable 扁平化投影）。 */
data class RocketMqOffsetRowSnapshot(
    val topic: String,
    val broker: String,
    val queueId: Int,
    /** 最大位点（brokerOffset）。 */
    val maxOffset: Long,
    /** 消费位点（consumerOffset）。 */
    val consumerOffset: Long,
    /** 积压量 = maxOffset - consumerOffset（负值截 0）。 */
    val lag: Long,
)

/** 消费进度查询状态：OK = rows 有值；其余两态 UI 走局部降级（警告卡片）。 */
enum class RocketMqProgressState {
    OK,

    /** CODE 17：重试 Topic（%RETRY%<group>）在 NameServer 无路由（组从未消费等）。 */
    RETRY_TOPIC_NO_ROUTE,

    /** 其他失败（权限 / 超时等），原始错误只进日志。 */
    FAILED,
}

/**
 * 消费组详情快照（属性对话框现查投影，数据已在 service 层拍平）。设计为**局部
 * 可得**：基础配置（订阅组配置，不依赖 %RETRY% 路由）与成员/订阅 best-effort 恒
 * 尽力填充；进度查询失败不阻塞整个详情（[progressState] 驱动 UI 局部降级）。
 */
data class RocketMqGroupDetailSnapshot(
    val name: String,
    /** broker 订阅组配置是否查到（不依赖 %RETRY% 路由）。 */
    val configFound: Boolean,
    /** 消费开关（订阅组配置）；null = 查询失败。 */
    val consumeEnable: Boolean?,
    /** 顺序消费（订阅组配置）；null = 查询失败。 */
    val orderly: Boolean?,
    /** 消费模式原始枚举名（CLUSTERING / BROADCASTING），UI 层翻译；未知为空串。 */
    val consumeMode: String,
    /** 订阅的 topic 列表（subscriptionTable keys，已排序）；消费者离线为空。 */
    val subscriptions: List<String>,
    /** 在线成员数；null = 查询失败（离线为 0）。 */
    val memberCount: Int?,
    /** 消费进度查询状态（见 [RocketMqProgressState]）。 */
    val progressState: RocketMqProgressState,
    /** 消费进度行（按 topic / broker / queueId 排序）；仅 [RocketMqProgressState.OK] 有值。 */
    val rows: List<RocketMqOffsetRowSnapshot>,
    /** 组级总积压（rows 求和）。 */
    val totalLag: Long,
)
