package com.mqclient.rabbitmq.ui.topology

import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyNodeType
import com.mqclient.rabbitmq.RabbitMqBindingSnapshot
import com.mqclient.rabbitmq.RabbitMqExchangeSnapshot
import com.mqclient.rabbitmq.RabbitMqQueueSnapshot
import com.mqclient.rabbitmq.RabbitMqQueueStatusSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RabbitMqTopologyGraphBuilder 纯逻辑的离线单测（不碰 RabbitMqModule / 网络）：
 * 节点 / 边 / 系统对象过滤 / severity 判定 / badge。
 */
class RabbitMqTopologyGraphBuilderTest {

    private val strings = RabbitMqTopologyStrings(messagesBadge = { "消息 $it" })

    private fun exchange(name: String) = RabbitMqExchangeSnapshot(
        name = name, type = "direct", durable = true, autoDelete = false, internal = false,
    )

    private fun queue(name: String, state: String = "running") = RabbitMqQueueSnapshot(
        name = name, state = state, durable = true, autoDelete = false, exclusive = false,
    )

    private fun binding(source: String, destination: String, type: String = "queue") =
        RabbitMqBindingSnapshot(source = source, destination = destination, destinationType = type, routingKey = "")

    private fun status(name: String, messages: Long, consumers: Int) = RabbitMqQueueStatusSnapshot(
        name = name, state = "running", messages = messages, messagesReady = messages,
        messagesUnacknowledged = 0, consumers = consumers,
    )

    private fun snapshot(
        exchanges: List<RabbitMqExchangeSnapshot>,
        queues: List<RabbitMqQueueSnapshot>,
        bindings: List<RabbitMqBindingSnapshot>,
        status: Map<String, RabbitMqQueueStatusSnapshot> = emptyMap(),
    ) = RabbitMqTopologySnapshot(exchanges, queues, bindings, status)

    // ---------------- 节点 / 边 ----------------

    @Test
    fun buildsExchangeAndQueueNodesWithBindingEdges() {
        val data = buildKuiverFromSnapshot(
            snapshot(
                exchanges = listOf(exchange("app.orders"), exchange("amq.direct")),
                queues = listOf(queue("team.q")),
                bindings = listOf(binding("app.orders", "team.q")),
            ),
            strings,
            showSystemObjects = false,
        )
        // 系统交换机 amq.direct 默认被过滤，只剩业务交换机 + 队列。
        assertEquals(setOf("x:app.orders", "q:team.q"), data.kuiver.nodes.keys)
        assertEquals(TopologyNodeType.EXCHANGE, data.nodeTypes["x:app.orders"])
        assertEquals(TopologyNodeType.RABBIT_QUEUE, data.nodeTypes["q:team.q"])
        assertEquals(setOf("x:app.orders" to "q:team.q"), data.kuiver.edges.map { it.fromId to it.toId }.toSet())
    }

    @Test
    fun showSystemObjectsIncludesDefaultAndAmqExchanges() {
        val data = buildKuiverFromSnapshot(
            snapshot(
                exchanges = listOf(exchange(""), exchange("amq.direct"), exchange("app.orders")),
                queues = listOf(queue("team.q")),
                bindings = listOf(
                    binding("", "team.q"), // 默认交换机的自动绑定
                    binding("app.orders", "team.q"),
                ),
            ),
            strings,
            showSystemObjects = true,
        )
        assertTrue("x:" in data.nodeTypes.keys && "x:amq.direct" in data.nodeTypes.keys)
        // 默认交换机 id 是 x:（name 为空串）。
        assertTrue("x:" in data.nodeTypes.keys)
        assertEquals(2, data.kuiver.edges.size)
    }

    @Test
    fun exchangeToExchangeBindingsSkippedInPhaseOne() {
        val data = buildKuiverFromSnapshot(
            snapshot(
                exchanges = listOf(exchange("a"), exchange("b")),
                queues = emptyList(),
                bindings = listOf(binding("a", "b", type = "exchange")),
            ),
            strings,
            showSystemObjects = true,
        )
        assertTrue(data.kuiver.edges.isEmpty(), "E2E 绑定第一阶段不画")
        assertEquals(2, data.kuiver.nodes.size)
    }

    @Test
    fun bindingsToFilteredSystemExchangesHidden() {
        val data = buildKuiverFromSnapshot(
            snapshot(
                exchanges = listOf(exchange("app.orders")),
                queues = listOf(queue("team.q")),
                bindings = listOf(
                    binding("", "team.q"),
                    binding("app.orders", "team.q"),
                ),
            ),
            strings,
            showSystemObjects = false,
        )
        // 默认交换机被过滤 → 它的绑定边一并消失。
        assertEquals(setOf("x:app.orders" to "q:team.q"), data.kuiver.edges.map { it.fromId to it.toId }.toSet())
    }

    // ---------------- severity / badge ----------------

    @Test
    fun queueWithBacklogAndNoConsumersIsWarning() {
        val data = buildKuiverFromSnapshot(
            snapshot(
                exchanges = listOf(exchange("app.orders")),
                queues = listOf(queue("team.q")),
                bindings = listOf(binding("app.orders", "team.q")),
                status = mapOf("team.q" to status("team.q", messages = 3, consumers = 0)),
            ),
            strings,
        )
        assertEquals(NodeSeverity.WARNING, data.severities["q:team.q"])
        assertEquals("消息 3", data.badges["q:team.q"])
    }

    @Test
    fun queueWithConsumersIsNormal() {
        val data = buildKuiverFromSnapshot(
            snapshot(
                exchanges = listOf(exchange("app.orders")),
                queues = listOf(queue("team.q")),
                bindings = listOf(binding("app.orders", "team.q")),
                status = mapOf("team.q" to status("team.q", messages = 3, consumers = 1)),
            ),
            strings,
        )
        assertEquals(NodeSeverity.NORMAL, data.severities["q:team.q"])
    }

    @Test
    fun downQueueIsErrorEvenWithoutStatus() {
        val data = buildKuiverFromSnapshot(
            snapshot(
                exchanges = listOf(exchange("app.orders")),
                queues = listOf(queue("dead.q", state = "down")),
                bindings = emptyList(),
            ),
            strings,
        )
        assertEquals(NodeSeverity.ERROR, data.severities["q:dead.q"])
        // 快扫未覆盖：无 badge（不误判消息数）。
        assertNull(data.badges["q:dead.q"])
    }

    @Test
    fun flowQueueIsErrorEvenWithoutStatus() {
        val data = buildKuiverFromSnapshot(
            snapshot(
                exchanges = listOf(exchange("app.orders")),
                queues = listOf(queue("blocked.q", state = "flow")),
                bindings = emptyList(),
            ),
            strings,
        )
        // flow = 内存/磁盘告警被阻塞，与 down 同级（队列卡片红点同口径）。
        assertEquals(NodeSeverity.ERROR, data.severities["q:blocked.q"])
        assertNull(data.badges["q:blocked.q"])
    }

    @Test
    fun emptySnapshotYieldsEmptyGraph() {
        val data = buildKuiverFromSnapshot(snapshot(emptyList(), emptyList(), emptyList()), strings)
        assertTrue(data.kuiver.nodes.isEmpty())
        assertTrue(data.nodeTypes.isEmpty())
    }
}
