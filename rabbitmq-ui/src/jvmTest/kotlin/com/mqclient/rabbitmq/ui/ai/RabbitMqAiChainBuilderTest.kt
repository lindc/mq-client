package com.mqclient.rabbitmq.ui.ai

import com.mqclient.rabbitmq.RabbitMqBindingSnapshot
import com.mqclient.rabbitmq.RabbitMqExchangeSnapshot
import com.mqclient.rabbitmq.RabbitMqQueueSnapshot
import com.mqclient.rabbitmq.RabbitMqQueueStatusSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * RabbitMqAiChainBuilder 纯逻辑的离线单测（不碰 RabbitMqModule / 网络）：上下游挑选
 * 与 chain JSON 结构。节点契约（type / isTarget / relationToNext / attributes）见构建器 KDoc。
 */
class RabbitMqAiChainBuilderTest {

    private fun binding(source: String, destination: String, type: String = "queue", rk: String = "") =
        RabbitMqBindingSnapshot(
            source = source,
            destination = destination,
            destinationType = type,
            routingKey = rk,
        )

    private fun queue(name: String, state: String? = "running") =
        RabbitMqQueueSnapshot(name = name, state = state, durable = true, autoDelete = false, exclusive = false)

    private fun status(name: String, messages: Long, consumers: Int = 1) =
        RabbitMqQueueStatusSnapshot(
            name = name,
            state = "running",
            messages = messages,
            messagesReady = messages,
            messagesUnacknowledged = 0,
            consumers = consumers,
        )

    // ---------------- selectUpstreamExchanges ----------------

    @Test
    fun upstreamFiltersByDestinationAndSkipsDefaultExchange() {
        val bindings = listOf(
            binding("demo.dlx", "worker", rk = "worker"),
            binding("", "worker", rk = "worker"), // 默认交换机隐式绑定，不入链
            binding("demo.dlx", "other", rk = "x"),
            binding("demo.fanout", "worker", type = "exchange", rk = "y"), // ex→ex 绑定不算
        )
        val selected = RabbitMqAiChainBuilder.selectUpstreamExchanges("worker", bindings)
        assertEquals(listOf("demo.dlx"), selected.map { it.name })
    }

    @Test
    fun upstreamMergesRoutingKeysPerExchange() {
        val bindings = listOf(
            binding("ex.a", "worker", rk = "k1"),
            binding("ex.b", "worker", rk = "k2"),
            binding("ex.a", "worker", rk = "k3"),
        )
        val selected = RabbitMqAiChainBuilder.selectUpstreamExchanges("worker", bindings)
        assertEquals(listOf("ex.a", "ex.b"), selected.map { it.name })
        assertEquals(listOf("k1", "k3"), selected[0].routingKeys)
    }

    @Test
    fun upstreamRoutingKeysCappedAtMax() {
        val bindings = (0..19).map { binding("ex.a", "worker", rk = "k$it") }
        val selected = RabbitMqAiChainBuilder.selectUpstreamExchanges("worker", bindings)
        assertEquals(RabbitMqAiChainBuilder.MAX_ROUTING_KEYS, selected.single().routingKeys.size)
    }

    // ---------------- selectDownstreamQueues ----------------

    @Test
    fun downstreamDedupesAndSortsByMessagesDescending() {
        val bindings = listOf(
            binding("ex.a", "q-big", rk = "k1"),
            binding("ex.a", "q-small", rk = "k2"),
            binding("ex.a", "q-big", rk = "k3"), // 去重
            binding("ex.a", "ex.b", type = "exchange", rk = "k4"), // ex→ex 不算
        )
        val status = mapOf(
            "q-big" to status("q-big", 999),
            "q-small" to status("q-small", 3),
        )
        val selected = RabbitMqAiChainBuilder.selectDownstreamQueues("ex.a", bindings, status)
        assertEquals(listOf("q-big", "q-small"), selected.map { it.name })
    }

    @Test
    fun downstreamCapsAtMaxAndNullStatusSortsLast() {
        val bindings = (0..9).map { binding("ex.a", "q$it", rk = "k$it") }
        val status = mapOf(
            "q0" to status("q0", 1),
            "q1" to status("q1", 100),
        )
        val selected = RabbitMqAiChainBuilder.selectDownstreamQueues("ex.a", bindings, status)
        assertEquals(RabbitMqAiChainBuilder.MAX_DOWNSTREAM_QUEUES, selected.size)
        // 消息数大的在前，计数缺失的垫底。
        assertEquals("q1", selected[0].name)
        assertEquals("q2", selected[2].name)
        assertNull(selected[2].status)
    }

    // ---------------- chainJson ----------------

    @Test
    fun queueChainWithoutUpstreamIsTwoNodes() {
        val nodes = RabbitMqAiChainBuilder.queueChainJson(
            vhost = "/",
            upstream = emptyList(),
            exchangeTypes = emptyMap(),
            queue = queue("worker"),
            status = null,
        ).let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonArray }
        assertEquals(2, nodes.size)
        assertEquals("VHOST", nodes[0].jsonObject["type"]!!.jsonPrimitive.content)
        val target = nodes[1].jsonObject
        assertEquals("QUEUE", target["type"]!!.jsonPrimitive.content)
        assertEquals("true", target["isTarget"]!!.jsonPrimitive.content)
        assertNull(target["relationToNext"])
        // 计数未扫到（status=null）→ attributes 不含 messages，不给 AI 假 0。
        assertTrue("messages" !in target["attributes"]!!.jsonObject)
    }

    @Test
    fun queueChainCarriesUpstreamExchangesAndCounts() {
        val nodes = RabbitMqAiChainBuilder.queueChainJson(
            vhost = "demo",
            upstream = listOf(RabbitMqAiChainBuilder.UpstreamExchange("demo.dlx", listOf("worker"))),
            exchangeTypes = mapOf("demo.dlx" to "fanout"),
            queue = queue("worker"),
            status = status("worker", 42, consumers = 2),
        ).let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonArray }
        assertEquals(3, nodes.size)
        assertEquals("routes_to", nodes[1].jsonObject["relationToNext"]!!.jsonPrimitive.content)
        val attrs = nodes[1].jsonObject["attributes"]!!.jsonObject
        assertEquals("fanout", attrs["type"]!!.jsonPrimitive.content)
        assertEquals("worker", attrs["routingKeys"]!!.jsonArray[0].jsonPrimitive.content)
        val queueAttrs = nodes[2].jsonObject["attributes"]!!.jsonObject
        assertEquals("42", queueAttrs["messages"]!!.jsonPrimitive.content)
        assertEquals("2", queueAttrs["consumers"]!!.jsonPrimitive.content)
    }

    @Test
    fun exchangeChainNamesDefaultExchangeAndCapQueues() {
        val downstream = RabbitMqAiChainBuilder.selectDownstreamQueues(
            "",
            (0..9).map { binding("", "q$it", rk = "k$it") },
            emptyMap(),
        )
        val nodes = RabbitMqAiChainBuilder.exchangeChainJson(
            vhost = "/",
            exchange = RabbitMqExchangeSnapshot(name = "", type = "direct", durable = true, autoDelete = false, internal = false),
            downstream = downstream,
        ).let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonArray }
        assertEquals(1 + 1 + RabbitMqAiChainBuilder.MAX_DOWNSTREAM_QUEUES, nodes.size)
        // 默认交换机（name=""）节点编址 amq.default（管理 API 同款口径）。
        assertEquals("amq.default", nodes[1].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("true", nodes[1].jsonObject["isTarget"]!!.jsonPrimitive.content)
        assertEquals("routes_to", nodes[1].jsonObject["relationToNext"]!!.jsonPrimitive.content)
        // 兄弟队列节点之间无 relationToNext（线性化约定）。
        assertNull(nodes[2].jsonObject["relationToNext"])
    }
}
