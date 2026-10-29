package com.mqclient.kafka

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions
import org.apache.kafka.clients.admin.ListTopicsOptions
import org.apache.kafka.common.TopicCollection

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


internal suspend fun scanKafkaGroups(admin: AdminClient, timeoutMs: Long): List<KafkaGroupSnapshot> {
    val groupIds = admin.listConsumerGroups(ListConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()))
        .all()
        .get(timeoutMs, TimeUnit.MILLISECONDS)
        .map { it.groupId() }
        .sorted()
    if (groupIds.isEmpty()) return emptyList()
    // Concurrent per-group describe (Semaphore(8)) instead of a serial future.get()
    // loop, so a large group list no longer accumulates into minutes of latency.
    val described = admin.describeConsumerGroups(
        groupIds,
        org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()),
    ).describedGroups()
    val semaphore = Semaphore(8)
    return coroutineScope {
        groupIds.map { groupId ->
            async {
                val future = described[groupId] ?: return@async null
                try {
                    semaphore.withPermit {
                        val d = future.get(timeoutMs, TimeUnit.MILLISECONDS)
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
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    kafkaLog.warn("group describe skipped: group=" + groupId + " error=" + kafkaErrorOf(error))
                    null
                }
            }
        }.awaitAll()
    }.filterNotNull().sortedBy { it.groupId }
}