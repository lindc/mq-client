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
)

/** 一个消费组的 Lag 快照（快扫产物：examineConsumeStats 的组级投影）。 */
data class RocketMqGroupLagSnapshot(
    val group: String,
    /** 组级 Lag：全 MessageQueue 的 brokerOffset - consumerOffset 之和。 */
    val totalLag: Long,
    /** 参与统计的 MessageQueue 数。 */
    val queueCount: Int,
)

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

/** 消费组详情快照（属性对话框现查投影，数据已在 service 层拍平）。 */
data class RocketMqGroupDetailSnapshot(
    val name: String,
    val memberCount: Int,
    /** 消费模式原始枚举名（CLUSTERING / BROADCASTING），UI 层翻译；未知为空串。 */
    val consumeMode: String,
    /** 订阅的 topic 列表（subscriptionTable keys，已排序）。 */
    val subscriptions: List<String>,
    /** 消费进度行（按 topic / broker / queueId 排序）。 */
    val rows: List<RocketMqOffsetRowSnapshot>,
    /** 组级总积压（rows 求和）。 */
    val totalLag: Long,
)
