package com.mqclient.kafka.ui

import com.mqclient.kafka.KafkaBrokerSnapshot
import com.mqclient.kafka.KafkaPartitionSnapshot
import com.mqclient.kafka.KafkaTopicSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [brokerPartitionRows] 的纯函数测试：Leader / Follower（含离线副本）判定、排序与
 * 承载 Topic 数（Broker 详情对话框统计区的数据来源）。
 */
class KafkaBrokerDetailDialogTest {

    private fun topic(
        name: String,
        vararg parts: Triple<Int, Int?, List<Int>>,  // (partitionId, leaderId, replicas)
    ): KafkaTopicSnapshot = KafkaTopicSnapshot(
        name = name,
        internal = false,
        partitions = parts.map { (id, leader, replicas) ->
            KafkaPartitionSnapshot(
                id = id,
                leaderId = leader,
                replicaIds = replicas,
                isrIds = replicas,
            )
        },
    )

    @Test
    fun `分区反查 - leader 与 follower 都算承载 - 角色判定正确`() {
        // broker 1：t1-p0 leader；t1-p1 follower（leader 2）；t2-p0 follower。
        val topics = listOf(
            topic("t1", Triple(0, 1, listOf(1, 2)), Triple(1, 2, listOf(1, 2))),
            topic("t2", Triple(0, 2, listOf(1, 2))),
        )

        val rows = brokerPartitionRows(1, topics)

        assertEquals(3, rows.size)
        assertEquals(
            rows.map { it.topic to it.partition },
            listOf("t1" to 0, "t1" to 1, "t2" to 0),
        )
        assertEquals(listOf(true, false, false), rows.map { it.isLeader })
    }

    @Test
    fun `分区反查 - 离线副本 leaderId 为 null 时按 follower 计`() {
        // b3 离线：t1-p0 的副本含 3 但 leader 挂 null（无 leader 离线分区）。
        val topics = listOf(
            KafkaTopicSnapshot(
                name = "t1",
                internal = false,
                partitions = listOf(
                    KafkaPartitionSnapshot(id = 0, leaderId = null, replicaIds = listOf(1, 3), isrIds = listOf(1)),
                ),
            ),
        )

        val rows = brokerPartitionRows(3, topics)

        assertEquals(1, rows.size)
        assertTrue(!rows[0].isLeader)
        assertEquals(listOf(1), rows[0].isrIds)
    }

    @Test
    fun `分区反查 - 未承载的分区被排除 - 排序按 topic partition`() {
        val topics = listOf(
            topic("zz", Triple(5, 9, listOf(9))),
            topic("aa", Triple(7, 1, listOf(1, 2)), Triple(3, 2, listOf(2))),
        )

        val rows = brokerPartitionRows(1, topics)

        // zz-p5（leader 9）与 aa-p3（leader 2）不含 broker 1，排除；剩余按序。
        assertEquals(
            listOf("aa" to 7),
            rows.map { it.topic to it.partition },
        )
    }

    @Test
    fun `统计 - 承载 topic 数按去重计`() {
        val topics = listOf(
            topic("t1", Triple(0, 1, listOf(1, 2)), Triple(1, 1, listOf(1, 2))),
            topic("t2", Triple(0, 1, listOf(1, 2))),
        )

        val rows = brokerPartitionRows(1, topics)

        assertEquals(3, rows.size)
        assertEquals(2, rows.map { it.topic }.distinct().size)
    }

    @Test
    fun `空 topics - 返回空列表`() {
        assertTrue(brokerPartitionRows(1, emptyList()).isEmpty())
    }
}
