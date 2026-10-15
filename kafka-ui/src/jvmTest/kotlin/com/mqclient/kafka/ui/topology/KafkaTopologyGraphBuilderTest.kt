package com.mqclient.kafka.ui.topology

import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyNodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [buildKuiverFromSnapshot] 的纯函数测试：聚合 / 展开 / 过滤 / 副本边开关四种形态下的
 * 节点、边与告警级别断言（不需要真实集群 —— builder 只吃 [KafkaTopologySnapshot]）。
 */
class KafkaTopologyGraphBuilderTest {

    private val strings = KafkaTopologyStrings(
        partitionsBadge = { "$it 分区" },
        leaderBadge = { "leader $it" },
        noLeaderBadge = "无 leader",
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
    )

    private fun edgeSet(data: com.mqclient.graph.TopologyData) =
        data.kuiver.edges.map { it.fromId to it.toId }.toSet()

    @Test
    fun `聚合视图 - 无分区节点 - topic 直连 leader broker - badge 为分区数`() {
        val data = buildKuiverFromSnapshot(snapshot(), strings)

        // 内部主题默认过滤：节点只有 t1 + 3 brokers。
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
    fun `展开视图 - 插入分区节点 - 其余 topic 保持聚合`() {
        val data = buildKuiverFromSnapshot(snapshot(), strings, expandedTopic = "t1")

        assertEquals(TopologyNodeType.PARTITION, data.nodeTypes["p:t1:0"])
        assertEquals("leader 0", data.badges["p:t1:0"])
        val edges = edgeSet(data)
        assertTrue("t1" to "p:t1:0" in edges)
        assertTrue("p:t1:0" to "b:0" in edges)
        assertTrue("p:t1:1" to "b:1" in edges)
        // p2 无 leader：只有 belongs_to 边，没有 led_by 边。
        assertFalse(edges.any { it.first == "p:t1:2" && it.second.startsWith("b:") })
        // 副本边默认关。
        assertFalse("p:t1:0" to "b:1" in edges)
        // 其他 topic（这里无第二个可见 topic）不参与；内部主题仍被过滤。
        assertFalse("__internal" to "b:2" in edges)
    }

    @Test
    fun `内部主题开关 - 开启后进图并产生聚合边 - badge 带内部标记`() {
        val data = buildKuiverFromSnapshot(snapshot(), strings, showInternalTopics = true)

        assertEquals(TopologyNodeType.TOPIC, data.nodeTypes["__internal"])
        assertTrue("__internal" to "b:2" in edgeSet(data))
        assertEquals("内部主题 · 1 分区", data.badges["__internal"])
    }

    @Test
    fun `副本边开关 - 展开且开启时补 partition 到非 leader 副本的边`() {
        val data = buildKuiverFromSnapshot(
            snapshot(),
            strings,
            expandedTopic = "t1",
            showReplicaEdges = true,
        )
        val edges = edgeSet(data)
        // p0 副本 {0,1}，leader 0 → 副本边 p0→b1。
        assertTrue("p:t1:0" to "b:1" in edges)
        // p1 副本 {1,2}，leader 1 → 副本边 p1→b2。
        assertTrue("p:t1:1" to "b:2" in edges)
        // p2 无 leader：副本 {0,1} 全部按副本边画。
        assertTrue("p:t1:2" to "b:0" in edges)
        assertTrue("p:t1:2" to "b:1" in edges)
        // replicaEdgeIds 与 builder 的副本边完全一致（面板虚线化用）。
        assertEquals(
            setOf(
                "p:t1:0" to "b:1",
                "p:t1:1" to "b:2",
                "p:t1:2" to "b:0",
                "p:t1:2" to "b:1",
            ),
            replicaEdgeIds(snapshot(), "t1"),
        )
    }

    @Test
    fun `告警级别 - 无 leader ERROR - 欠副本 WARNING - topic 取最差`() {
        val data = buildKuiverFromSnapshot(snapshot(), strings, expandedTopic = "t1")

        assertEquals(NodeSeverity.ERROR, data.severities["p:t1:2"])
        assertEquals(NodeSeverity.WARNING, data.severities["p:t1:1"])
        assertEquals(NodeSeverity.NORMAL, data.severities["p:t1:0"])
        // t1 含离线分区 → ERROR。
        assertEquals(NodeSeverity.ERROR, data.severities["t1"])
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

    @Test
    fun `partition 节点 id 解析 - 反解所属 topic`() {
        assertEquals("t1", partitionNodeTopic(partitionNodeId("t1", 3)))
        // topic 名里的点 / 下划线 / 连字符不影响解析（`:` 才是分隔符）。
        assertEquals("a.b-c_d", partitionNodeTopic(partitionNodeId("a.b-c_d", 0)))
    }
}
