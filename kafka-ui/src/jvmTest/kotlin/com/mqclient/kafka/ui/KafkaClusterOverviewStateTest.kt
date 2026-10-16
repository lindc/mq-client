package com.mqclient.kafka.ui

import com.mqclient.kafka.KafkaBrokerSnapshot
import com.mqclient.kafka.KafkaPartitionSnapshot
import com.mqclient.kafka.KafkaTopicSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [KafkaClusterOverviewSnapshot] 的纯函数测试：离线 broker 推断（副本分配引用但不在
 * 存活列表的 id）与空态语义。
 */
class KafkaClusterOverviewStateTest {

    private fun broker(id: Int) = KafkaBrokerSnapshot(brokerId = id, host = "host$id", port = 9092)

    private fun topic(name: String, vararg replicaSets: List<Int>) = KafkaTopicSnapshot(
        name = name,
        internal = false,
        partitions = replicaSets.mapIndexed { i, replicas ->
            KafkaPartitionSnapshot(id = i, leaderId = replicas.first(), replicaIds = replicas, isrIds = replicas)
        },
    )

    @Test
    fun `离线推断 - 副本引用但不在存活列表的 id 即离线`() {
        // b3 有副本（t1 p0 / t2 p0）但 describeCluster 没回它 —— 离线。
        val snapshot = KafkaClusterOverviewSnapshot(
            clusterId = "cluster-1",
            controllerId = 1,
            brokers = listOf(broker(1), broker(2)),
            topics = listOf(
                topic("t1", listOf(1, 2, 3), listOf(2, 3, 1)),
                topic("t2", listOf(3, 1)),
            ),
        )
        assertEquals(setOf(3), snapshot.offlineBrokerIds)
    }

    @Test
    fun `离线推断 - 副本全部存活时为空集`() {
        val snapshot = KafkaClusterOverviewSnapshot(
            clusterId = null,
            controllerId = 1,
            brokers = listOf(broker(1), broker(2), broker(3)),
            topics = listOf(topic("t1", listOf(1, 2, 3))),
        )
        assertTrue(snapshot.offlineBrokerIds.isEmpty())
    }

    @Test
    fun `离线推断 - 无 topic 时无法推断 为空集`() {
        val snapshot = KafkaClusterOverviewSnapshot(
            clusterId = null,
            controllerId = null,
            brokers = listOf(broker(1)),
            topics = emptyList(),
        )
        assertTrue(snapshot.offlineBrokerIds.isEmpty())
    }

    @Test
    fun `空态 - brokers 与 topics 全空即 isEmpty - clusterId 不参与判定`() {
        val empty = KafkaClusterOverviewSnapshot("cluster-1", 1, emptyList(), emptyList())
        assertTrue(empty.isEmpty)
        assertFalse(empty.offlineBrokerIds.isNotEmpty())

        val onlyClusterId = KafkaClusterOverviewSnapshot("cluster-1", null, emptyList(), emptyList())
        assertTrue(onlyClusterId.isEmpty)
    }
}
