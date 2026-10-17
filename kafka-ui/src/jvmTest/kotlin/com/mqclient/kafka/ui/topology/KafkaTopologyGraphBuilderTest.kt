package com.mqclient.kafka.ui.topology

import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyNodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [buildKuiverFromSnapshot] 的纯函数测试：聚合视图 / 内部主题过滤 / 告警级别 / 空快照
 * 的节点、边与 badge 断言（不需要真实集群 —— builder 只吃 [KafkaTopologySnapshot]）。
 * 分区展开视图已移除（分区明细在主题属性对话框的「分区」Tab）。
 */
class KafkaTopologyGraphBuilderTest {

    private val strings = KafkaTopologyStrings(
        partitionsBadge = { "$it 分区" },
        internalLabel = "内部主题",
    )

    /** 3 brokers；t1（3 分区：p0 leader b0 / p1 leader b1 欠副本 / p2 无 leader 离线）、t2（1 分区 leader b2，内部）。 */
    private fun snapshot() = KafkaTopologySnapshot(
        brokers = listOf(
            KafkaBrokerInfo(0, "host0", 9092),
            KafkaBrokerInfo(1, "host1", 9092),
            KafkaBrokerInfo(2, "host2", 9092),
        ),
        topics = listOf(
            KafkaTopicTopologyInfo(
                name = "t1",
                internal = false,
                partitions = listOf(
                    KafkaPartitionInfo(id = 0, leaderId = 0, replicaIds = listOf(0, 1), isrIds = listOf(0, 1)),
                    KafkaPartitionInfo(id = 1, leaderId = 1, replicaIds = listOf(1, 2), isrIds = listOf(1)),
                    KafkaPartitionInfo(id = 2, leaderId = null, replicaIds = listOf(0, 1), isrIds = listOf(0, 1)),
                ),
            ),
            KafkaTopicTopologyInfo(
                name = "__internal",
                internal = true,
                partitions = listOf(
                    KafkaPartitionInfo(id = 0, leaderId = 2, replicaIds = listOf(2), isrIds = listOf(2)),
                ),
            ),
        ),
        controllerId = 0,
    )

    private fun edgeSet(data: com.mqclient.graph.TopologyData) =
        data.kuiver.edges.map { it.fromId to it.toId }.toSet()

    @Test
    fun `聚合视图 - 无分区节点 - topic 直连 leader broker - badge 为分区数`() {
        val data = buildKuiverFromSnapshot(snapshot(), strings)

        // 内部主题默认过滤：节点只有 t1 + 3 brokers，无 PARTITION 节点。
        assertEquals(
            mapOf(
                "b:0" to TopologyNodeType.BROKER,
                "b:1" to TopologyNodeType.BROKER,
                "b:2" to TopologyNodeType.BROKER,
                "t1" to TopologyNodeType.TOPIC,
            ),
            data.nodeTypes,
        )
        // 内部主题默认过滤：只有 t1 的 leader 边（b0、b1）。
        assertEquals(setOf("t1" to "b:0", "t1" to "b:1"), edgeSet(data))
        assertEquals("3 分区", data.badges["t1"])
    }

    @Test
    fun `内部主题开关 - 开启后进图并产生聚合边 - badge 带内部标记`() {
        val data = buildKuiverFromSnapshot(snapshot(), strings, showInternalTopics = true)

        assertEquals(TopologyNodeType.TOPIC, data.nodeTypes["__internal"])
        assertTrue("__internal" to "b:2" in edgeSet(data))
        assertEquals("内部主题 · 1 分区", data.badges["__internal"])
    }

    @Test
    fun `告警级别 - 无 leader ERROR - 欠副本 WARNING - topic 取最差`() {
        val data = buildKuiverFromSnapshot(snapshot(), strings)

        // t1 含离线分区（无 leader）→ ERROR；欠副本分区在 topic 级聚合下同样体现。
        assertEquals(NodeSeverity.ERROR, data.severities["t1"])
        assertEquals(NodeSeverity.NORMAL, data.severities["b:0"])
    }

    @Test
    fun `空快照 - 返回空图`() {
        val data = buildKuiverFromSnapshot(
            KafkaTopologySnapshot(brokers = emptyList(), topics = emptyList()),
            strings,
        )
        assertTrue(data.kuiver.nodes.isEmpty())
        assertTrue(data.nodeTypes.isEmpty())
    }
}
