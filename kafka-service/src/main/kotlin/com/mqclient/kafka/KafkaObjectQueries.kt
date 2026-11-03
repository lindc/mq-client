package com.mqclient.kafka

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions
import org.apache.kafka.clients.admin.ListTopicsOptions
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicCollection
import org.apache.kafka.common.TopicPartition

/**
 * Kafka 对象慢扫的**协议查询层**：AdminClient → 领域快照。
 *
 * 从 `KafkaScanService`（原本 1000 行）里按职责切出来：这一层只做「查 + 映射」，
 * 不含并发编排、缓存策略、失败判定与事件 —— 那些留在 service（或基类）。
 *
 * 切分依据是**这层几乎无状态**：只依赖 AdminClient 与超时，三个函数都自带逐项容错
 * （单 topic / 单组失败只跳过，不炸整类），因此可以在不改编排的前提下独立演进。
 *
 * 出错文案统一走 [kafkaErrorOf]（与 service 的 `errorMessage` 同一实现：解掉
 * `ExecutionException` 那层包装，拿根因消息）。
 */

/** Kafka 异常的根因文案：解掉 `ExecutionException`（KafkaFuture 的包装）取 cause。 */
internal fun kafkaErrorOf(error: Throwable): String =
    (error as? ExecutionException)?.cause?.message
        ?: error.message
        ?: error::class.simpleName
        ?: "unknown"

/**
 * describeCluster 一次调用的双产出：broker 明细（含 controller 标记）+ 集群元信息。
 * clusterId 单独兜底 —— 部分受限账号对 Describe Cluster 的细项 ACL 缺失时抛
 * Unauthorized，broker 列表照常产出、clusterId 置 null。
 */
internal data class ClusterScan(
    val brokers: List<KafkaBrokerSnapshot>,
    val cluster: KafkaClusterSnapshot,
)

internal suspend fun scanKafkaBrokers(admin: AdminClient, timeoutMs: Long): ClusterScan {
    val cluster = admin.describeCluster(
        org.apache.kafka.clients.admin.DescribeClusterOptions().timeoutMs(timeoutMs.toInt()),
    )
    val controllerId = cluster.controller().get(timeoutMs, TimeUnit.MILLISECONDS)?.id()
    val clusterId = try {
        cluster.clusterId().get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        kafkaLog.warn("clusterId unavailable: error=${kafkaErrorOf(error)}")
        null
    }
    val brokers = cluster.nodes().get(timeoutMs, TimeUnit.MILLISECONDS)
        .map {
            KafkaBrokerSnapshot(
                brokerId = it.id(),
                host = it.host(),
                port = it.port(),
                rack = it.rack()?.takeIf(String::isNotBlank),
                isController = it.id() == controllerId,
            )
        }
        .sortedBy { it.brokerId }
    return ClusterScan(brokers, KafkaClusterSnapshot(clusterId = clusterId, controllerId = controllerId))
}

internal suspend fun scanKafkaTopics(admin: AdminClient, timeoutMs: Long): List<KafkaTopicSnapshot> {
    val names = admin.listTopics(ListTopicsOptions().listInternal(true).timeoutMs(timeoutMs.toInt()))
        .listings()
        .get(timeoutMs, TimeUnit.MILLISECONDS)
        .map { it.name() }
        .sorted()
    if (names.isEmpty()) return emptyList()
    // 逐 topic future：单 topic 描述失败（无权 / 已删除）只跳过，不炸整类。
    val described = admin.describeTopics(TopicCollection.ofTopicNames(names))
        .topicNameValues()
    return described.mapNotNull { (name, future) ->
        try {
            val d = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            KafkaTopicSnapshot(
                name = name,
                internal = d.isInternal,
                partitions = d.partitions()
                    .map { p ->
                        KafkaPartitionSnapshot(
                            id = p.partition(),
                            leaderId = p.leader()?.takeIf { it.id() >= 0 }?.id(),
                            replicaIds = p.replicas().map { it.id() },
                            isrIds = p.isr().map { it.id() },
                        )
                    }
                    .sortedBy { it.id },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            kafkaLog.warn("topic describe skipped: topic=$name error=${kafkaErrorOf(error)}")
            null
        }
    }.sortedBy { it.name }
}


internal suspend fun scanKafkaGroups(admin: AdminClient, timeoutMs: Long): List<KafkaGroupSnapshot> =
    withContext(Dispatchers.IO) {
        val groupIds = admin.listConsumerGroups(ListConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()))
            .all()
            .awaitKafka(timeoutMs)
            .map { it.groupId() }
            .sorted()
        if (groupIds.isEmpty()) return@withContext emptyList()
        // **一次** describeConsumerGroups 拿到全部组：AdminClient 内部按 coordinator
        // broker 分组批量下发，返回的是一组已经全部发出的 future —— 这里只负责并发等，
        // 不再「N 个组 × N 次往返」。原先的 Semaphore(8) 也已去掉：`awaitKafka` 不占
        // 线程，没有需要节流的阻塞点了。
        val described = admin.describeConsumerGroups(
            groupIds,
            DescribeConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()),
        ).describedGroups()
        coroutineScope {
            groupIds.map { groupId ->
                async {
                    val future = described[groupId] ?: return@async null
                    try {
                        val d = future.awaitKafka(timeoutMs)
                        KafkaGroupSnapshot(
                            groupId = groupId,
                            state = d.state().toString(),
                            members = d.members().size,
                            simple = d.isSimpleConsumerGroup,
                            subscribedTopics = d.members()
                                .flatMap { it.assignment().topicPartitions() }
                                .map { it.topic() }
                                .toSortedSet(),
                        )
                    } catch (timeout: TimeoutCancellationException) {
                        // 单组超时（协调器无响应 / 组太多堆在同一个 broker）：跳过它，
                        // 其它组照常列出 —— 一个坏组不能拖垮整张列表。
                        kafkaLog.warn("group describe skipped (timeout): group=$groupId")
                        null
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        // GroupIdNotFoundException / GroupAuthorizationException /
                        // CoordinatorNotAvailable 等都落到这里：跳过 + 记日志即可。
                        kafkaLog.warn("group describe skipped: group=$groupId error=${kafkaErrorOf(error)}")
                        null
                    }
                }
            }.awaitAll()
        }.filterNotNull().sortedBy { it.groupId }
    }

/**
 * 消费组 Lag 的**批量**计算：两次往返覆盖全部组。
 *
 * ```
 * listConsumerGroups               1 次（调用方已做）
 * listConsumerGroupOffsets(批量)   1 次  ← 全部组一起
 * listOffsets(批量)                1 次  ← 全部组的分区一起
 * ```
 *
 * 对比旧实现（每组一次 `listConsumerGroupOffsets` + 一次 `listOffsets`，再包一层
 * `withTimeout(3000)`）：200 个组就是 400 次往返，3 秒的「硬超时」还因为
 * `future.get` 不可取消而变成假守卫。这里两次往返就够，超时也由 [awaitKafka] 真能打断。
 *
 * `partitionsToOffsetAndMetadata(groupId)` 只是对同一次响应做**本地**按组切分，
 * 不会再产生网络请求 —— 所以既拿到「每次往返覆盖全部组」，又保住了按组归属。
 *
 * 容错是逐组 / 逐分区的：单组取不到位（无权 / 组刚好消失）就跳过该组；
 * 单分区查不到末端位移就按 lag = 0 计（不让一个已删除的分区炸掉整轮）。
 */
internal suspend fun scanKafkaGroupLags(
    admin: AdminClient,
    groupIds: List<String>,
    timeoutMs: Long,
): Map<String, KafkaGroupLagSnapshot> = withContext(Dispatchers.IO) {
    if (groupIds.isEmpty()) return@withContext emptyMap()

    val offsetsResult = admin.listConsumerGroupOffsets(
        groupIds.associateWith { ListConsumerGroupOffsetsSpec() },
        ListConsumerGroupOffsetsOptions().timeoutMs(timeoutMs.toInt()),
    )
    // 同一次请求的多组视图：每个 future 完成时数据已经在本地，不会额外发请求。
    val perGroup = groupIds.associateWith { offsetsResult.partitionsToOffsetAndMetadata(it) }

    val committedByGroup: Map<String, Map<TopicPartition, OffsetAndMetadata>> =
        coroutineScope {
            perGroup.map { (groupId, future) ->
                async {
                    try {
                        groupId to future.awaitKafka(timeoutMs)
                    } catch (timeout: TimeoutCancellationException) {
                        kafkaLog.warn("group offsets skipped (timeout): group=$groupId")
                        null
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        kafkaLog.warn("group offsets skipped: group=$groupId error=${kafkaErrorOf(error)}")
                        null
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    if (committedByGroup.isEmpty()) return@withContext emptyMap()

    val allPartitions = committedByGroup.values.flatMap { it.keys }.toSet()
    val latest = if (allPartitions.isEmpty()) {
        emptyMap()
    } else {
        val result = admin.listOffsets(allPartitions.associateWith { OffsetSpec.latest() })
        // `partitionResult(tp)` 取的是**同一次** RPC 的按分区视图（不再发请求）。
        coroutineScope {
            allPartitions.map { tp ->
                val future = result.partitionResult(tp)
                async {
                    try {
                        tp to future.awaitKafka(timeoutMs).offset()
                    } catch (timeout: TimeoutCancellationException) {
                        kafkaLog.warn("latest offset skipped (timeout): $tp")
                        null
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        // 分区被删除 / 无 leader：按 0 计 lag，不拖累整轮。
                        kafkaLog.warn("latest offset skipped: $tp error=${kafkaErrorOf(error)}")
                        null
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    committedByGroup.map { (groupId, committed) ->
        val details = committed.map { (tp, current) ->
            val end = latest[tp] ?: current.offset()
            KafkaPartitionLag(
                topic = tp.topic(),
                partition = tp.partition(),
                currentOffset = current.offset(),
                latestOffset = end,
                lag = (end - current.offset()).coerceAtLeast(0L),
            )
        }.sortedWith(compareBy({ it.topic }, { it.partition }))
        groupId to KafkaGroupLagSnapshot(
            groupId = groupId,
            totalLag = details.sumOf { it.lag },
            partitions = details,
        )
    }.toMap()
}
