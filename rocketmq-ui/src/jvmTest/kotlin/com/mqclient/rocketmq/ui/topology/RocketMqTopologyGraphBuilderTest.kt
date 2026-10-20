package com.mqclient.rocketmq.ui.topology

import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyNodeType
import com.mqclient.rocketmq.ui.topology.RocketMqRouteInfo
import com.mqclient.rocketmq.ui.topology.RocketMqTopicTopologyInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RocketMqTopologyGraphBuilder 纯函数的离线单测（不碰网络）：
 * topic → broker 聚合边 / 系统主题过滤 / 只读告警。
 */
class RocketMqTopologyGraphBuilderTest {

    private val strings = RocketMqTopologyStrings(routeBadge = { read, write -> "读 " + read + " · 写 " + write })

    private fun topic(
        name: String,
        read: Int = 8,
        write: Int = 8,
        perm: Int = 6,
        brokers: List<String> = listOf("broker-a"),
        system: Boolean = false,
    ) = RocketMqTopicTopologyInfo(
        name = name,
        system = system,
        routes = brokers.map { b ->
            RocketMqRouteInfo(brokerName = b, readQueueNums = read, writeQueueNums = write, perm = perm)
        },
    )

    @Test
    fun topicLinksToEachBrokerInRoute() {
        val data = buildKuiverFromSnapshot(
            topics = listOf(topic("app.orders", brokers = listOf("broker-a", "broker-b"))),
            brokerAddresses = mapOf("broker-a" to "10.0.0.1:10911", "broker-b" to "10.0.0.2:10911"),
            strings = strings,
        )
        assertEquals(
            setOf("app.orders" to "b:broker-a", "app.orders" to "b:broker-b"),
            data.kuiver.edges.map { it.fromId to it.toId }.toSet(),
        )
        assertEquals(TopologyNodeType.TOPIC, data.nodeTypes["app.orders"])
        assertEquals(TopologyNodeType.BROKER, data.nodeTypes["b:broker-a"])
    }

    @Test
    fun systemTopicsHiddenByDefault() {
        val data = buildKuiverFromSnapshot(
            topics = listOf(
                topic("app.orders"),
                topic("%RETRY%app.group", system = true),
                topic("%DLQ%app.group", system = true),
                topic("SCHEDULE_TOPIC_XXXX", system = true),
            ),
            brokerAddresses = mapOf("broker-a" to "10.0.0.1:10911"),
            strings = strings,
        )
        // 默认只画业务主题；broker 节点恒在（Kafka 拓扑同款）。
        assertEquals(setOf("app.orders", "b:broker-a"), data.kuiver.nodes.keys)
    }

    @Test
    fun systemTopicsVisibleWithToggle() {
        val data = buildKuiverFromSnapshot(
            topics = listOf(
                topic("app.orders"),
                topic("%RETRY%app.group", system = true),
            ),
            brokerAddresses = mapOf("broker-a" to "10.0.0.1:10911"),
            strings = strings,
            showSystemTopics = true,
        )
        assertTrue("%RETRY%app.group" in data.kuiver.nodes.keys)
    }

    @Test
    fun readOnlyTopicIsWarning() {
        val data = buildKuiverFromSnapshot(
            topics = listOf(topic("ro.topic", perm = 2)), // 只读（无写权限位）
            brokerAddresses = mapOf("broker-a" to "10.0.0.1:10911"),
            strings = strings,
        )
        assertEquals(NodeSeverity.WARNING, data.severities["ro.topic"])
    }

    @Test
    fun noRouteTopicIsWarning() {
        val data = buildKuiverFromSnapshot(
            topics = listOf(topic("orphan.topic", brokers = emptyList())),
            brokerAddresses = mapOf("broker-a" to "10.0.0.1:10911"),
            strings = strings,
        )
        // 无路由 = 发布不可达，与只读同级告警。
        assertEquals(NodeSeverity.WARNING, data.severities["orphan.topic"])
        assertTrue(data.kuiver.edges.isEmpty())
    }
}
