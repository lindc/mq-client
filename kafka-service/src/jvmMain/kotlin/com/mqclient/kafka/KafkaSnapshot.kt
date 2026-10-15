package com.mqclient.kafka

/**
 * Kafka 扫描产物的内存快照模型（纯数据，进 [CacheManager] 缓存、被 UI 投影）。
 *
 * 与 ibm-mq 的 `MqObjectSnapshot` 同一角色：扫描链路 write-through 写入，读取方
 * （列表页 / 拓扑页 / AI 链路）只读缓存不碰 AdminClient。命名带 Snapshot 后缀，
 * 与 kafka-ui 的卡片投影类（KafkaTopicInfo / KafkaGroupInfo）区分。
 */

/** 一个 broker 的快照（describeCluster 投影）。 */
data class KafkaBrokerSnapshot(
    val brokerId: Int,
    val host: String,
    val port: Int,
    val rack: String? = null,
    /** 是否当前 controller（KRaft 集群唯一的活跃 controller）。 */
    val isController: Boolean = false,
)

/** 一个分区的副本布局快照（TopicPartitionInfo 投影）。 */
data class KafkaPartitionSnapshot(
    val id: Int,
    /** leader broker id；分区离线（无 leader）时为 null。 */
    val leaderId: Int?,
    val replicaIds: List<Int>,
    val isrIds: List<Int>,
)

/** 一个 topic 的完整快照：静态属性 + 全部分区明细（慢扫产物）。 */
data class KafkaTopicSnapshot(
    val name: String,
    /** Kafka 内部主题（__consumer_offsets / __transaction_state 等）。 */
    val internal: Boolean,
    val partitions: List<KafkaPartitionSnapshot>,
)

/** 一个 consumer group 的快照（describeConsumerGroups 投影，慢扫产物）。 */
data class KafkaGroupSnapshot(
    val groupId: String,
    /** 组状态（Stable / Empty / Dead…，行业术语不翻译）。 */
    val state: String,
    val members: Int,
    /** 简单组：没有分配协议（客户端手工分区分配）。 */
    val simple: Boolean,
    /** 各成员 assignment 覆盖的 topic 名（"消费的 Topic"）。 */
    val subscribedTopics: Set<String>,
)

/** 一个分区的位点 / Lag 明细（快扫产物）。 */
data class KafkaPartitionLag(
    val topic: String,
    val partition: Int,
    /** 组已提交位点。 */
    val currentOffset: Long,
    /** 该分区末尾位点（listOffsets LATEST）。 */
    val latestOffset: Long,
    /** latest - current（提交位点为空按 0 计）。 */
    val lag: Long,
)

/** 一个 consumer group 的 Lag 快照（快扫产物）。 */
data class KafkaGroupLagSnapshot(
    val groupId: String,
    val totalLag: Long,
    val partitions: List<KafkaPartitionLag>,
)
