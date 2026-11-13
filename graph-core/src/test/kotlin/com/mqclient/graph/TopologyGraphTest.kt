package com.mqclient.graph

import com.dk.kuiver.model.Kuiver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 图模型契约（graph-core 的全部公开面）：节点类型词汇 + 一次性构建产物。
 *
 * 这一层没有逻辑、只有数据契约，所以测试盯的是「容易静默出错」的几条：
 *  - `TopologyNodeType` 的**枚举名**是各 `*-ui` 与渲染层之间的隐式协议（拓扑面板按
 *    名字映射图标 / 颜色、测试断言也按名字），改名会让别的模块编不过或渲染错色；
 *  - `TopologyData` 的三个映射默认值语义（badges 可省、severities 可空）；
 *  - `TopologyData` 是数据载体：`copy` 不应意外丢字段。
 */
class TopologyGraphTest {

    /** 节点类型名是跨模块协议：新增可以，改名/删除必须是**有意识的**改动。 */
    @Test
    fun `node type vocabulary is stable`() {
        val expected = listOf(
            // IBM MQ 业务链路
            "ALIAS_QUEUE", "LOCAL_QUEUE", "REMOTE_QUEUE", "TRANSMISSION_QUEUE",
            "TOPIC", "SUBSCRIPTION", "CHANNEL", "REMOTE_QMGR",
            "QMGR_FULL", "QMGR_PARTIAL",
            // Kafka
            "PARTITION", "BROKER", "CONSUMER_GROUP",
            // RabbitMQ
            "EXCHANGE", "RABBIT_QUEUE",
            // OLAP（StarRocks FE / BE 集群拓扑）
            "OLAP_FE", "OLAP_BE",
        )

        assertEquals(expected, TopologyNodeType.entries.map { it.name })
    }

    @Test
    fun `severity order is normal warning error`() {
        // 渲染层按 ordinal 比较（取更严重的那个），顺序即语义。
        assertEquals(
            listOf("NORMAL", "WARNING", "ERROR"),
            NodeSeverity.entries.map { it.name },
        )
        assertTrue(NodeSeverity.WARNING.ordinal > NodeSeverity.NORMAL.ordinal)
        assertTrue(NodeSeverity.ERROR.ordinal > NodeSeverity.WARNING.ordinal)
    }

    @Test
    fun `badges default to empty so builders can omit it`() {
        val data = TopologyData(
            kuiver = Kuiver(),
            nodeTypes = mapOf("q1" to TopologyNodeType.LOCAL_QUEUE),
            severities = mapOf("q1" to NodeSeverity.NORMAL),
        )

        assertEquals(emptyMap(), data.badges, "IBM MQ 的 builder 不传 badges，默认必须是空表")
    }

    @Test
    fun `data carries the kuiver graph by reference`() {
        val graph = Kuiver()
        val data = TopologyData(
            kuiver = graph,
            nodeTypes = emptyMap(),
            severities = emptyMap(),
            badges = mapOf("p0" to "leader=1"),
        )

        // 不复制图：渲染层直接消费同一个实例（大图上复制没有意义）。
        assertSame(graph, data.kuiver)
        assertEquals("leader=1", data.badges["p0"])
    }

    @Test
    fun `copy keeps fields that were not overridden`() {
        val data = TopologyData(
            kuiver = Kuiver(),
            nodeTypes = mapOf("t" to TopologyNodeType.TOPIC),
            severities = mapOf("t" to NodeSeverity.ERROR),
            badges = mapOf("t" to "p=3"),
        )

        val updated = data.copy(severities = mapOf("t" to NodeSeverity.NORMAL))

        assertEquals(mapOf("t" to TopologyNodeType.TOPIC), updated.nodeTypes)
        assertEquals(mapOf("t" to "p=3"), updated.badges, "copy 未点名的字段必须原样保留")
        assertEquals(NodeSeverity.NORMAL, updated.severities["t"])
    }
}
