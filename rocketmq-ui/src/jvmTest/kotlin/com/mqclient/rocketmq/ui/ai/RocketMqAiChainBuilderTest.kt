package com.mqclient.rocketmq.ui.ai

import com.mqclient.rocketmq.RocketMqGroupLagSnapshot
import com.mqclient.rocketmq.RocketMqGroupSnapshot
import com.mqclient.rocketmq.RocketMqOffsetRowSnapshot
import com.mqclient.rocketmq.RocketMqTopicRouteSnapshot
import com.mqclient.rocketmq.RocketMqTopicSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * RocketMqAiChainBuilder 纯逻辑的离线单测（不碰 RocketMqModule / 网络）：消费组挑选
 * 与 chain JSON 结构。节点契约（type / isTarget / relationToNext / attributes）见构建器 KDoc。
 */
class RocketMqAiChainBuilderTest {

    private val topic = RocketMqTopicSnapshot(
        name = "app-orders",
        readQueueNums = 8,
        writeQueueNums = 8,
        perm = 6,
        routes = listOf(
            RocketMqTopicRouteSnapshot(brokerName = "broker-a", readQueueNums = 4, writeQueueNums = 4, perm = 6),
            RocketMqTopicRouteSnapshot(brokerName = "broker-b", readQueueNums = 4, writeQueueNums = 4, perm = 6),
        ),
    )

    private fun group(name: String, vararg topics: String, mode: String? = "CLUSTERING") =
        RocketMqGroupSnapshot(name = name, memberCount = 2, consumeMode = mode, subscribedTopics = topics.toList())

    private fun row(topic: String, broker: String, queueId: Int, lag: Long) = RocketMqOffsetRowSnapshot(
        topic = topic,
        broker = broker,
        queueId = queueId,
        maxOffset = lag + 100,
        consumerOffset = 100,
        lag = lag,
    )

    private fun lag(group: String, totalLag: Long, rows: List<RocketMqOffsetRowSnapshot> = emptyList()) =
        RocketMqGroupLagSnapshot(group = group, totalLag = totalLag, queueCount = rows.size, rows = rows)

    // ---------------- selectConsumerGroups ----------------

    @Test
    fun selectFiltersBySubscribedTopic() {
        val groups = listOf(
            group("g-orders", "app-orders", "app-billing"),
            group("g-other", "app-other"),
        )
        val selected = RocketMqAiChainBuilder.selectConsumerGroups("app-orders", groups, emptyMap())
        assertEquals(listOf("g-orders"), selected.map { it.name })
    }

    @Test
    fun selectSortsByTotalLagDescending() {
        val groups = listOf(group("g-small", "t"), group("g-big", "t"), group("g-none", "t"))
        val lags = mapOf("g-small" to lag("g-small", 10), "g-big" to lag("g-big", 999))
        val selected = RocketMqAiChainBuilder.selectConsumerGroups("t", groups, lags)
        assertEquals(listOf("g-big", "g-small", "g-none"), selected.map { it.name })
    }

    @Test
    fun selectCapsAtMaxGroups() {
        val groups = (0..9).map { group("g$it", "t") }
        val lags = groups.associate { it.name to lag(it.name, it.name.removePrefix("g").toLong()) }
        val selected = RocketMqAiChainBuilder.selectConsumerGroups("t", groups, lags)
        assertEquals(RocketMqAiChainBuilder.MAX_CONSUMER_GROUPS, selected.size)
        // 保留的是 lag 最大的前 N 个。
        assertEquals(listOf("g9", "g8", "g7", "g6"), selected.map { it.name })
    }

    // ---------------- topicChainJson ----------------

    @Test
    fun twoNodeChainWithoutConsumersHasNoConsumedBy() {
        val nodes = Json.parseToJsonElement(
            RocketMqAiChainBuilder.topicChainJson("ns:9876", "Chillbear", topic, emptyList(), emptyMap()),
        ).jsonArray
        assertEquals(2, nodes.size)
        val ns = nodes[0].jsonObject
        assertEquals("NAMESERVER", ns["type"]!!.jsonPrimitive.content)
        assertEquals("hosts", ns["relationToNext"]!!.jsonPrimitive.content)
        assertEquals("Chillbear", ns["attributes"]!!.jsonObject["clusterName"]!!.jsonPrimitive.content)
        val target = nodes[1].jsonObject
        assertEquals("TOPIC", target["type"]!!.jsonPrimitive.content)
        assertEquals("true", target["isTarget"]!!.jsonPrimitive.content)
        assertNull(target["relationToNext"])
    }

    @Test
    fun topicNodeCarriesRoutes() {
        val nodes = Json.parseToJsonElement(
            RocketMqAiChainBuilder.topicChainJson("ns:9876", null, topic, emptyList(), emptyMap()),
        ).jsonArray
        val attrs = nodes[1].jsonObject["attributes"]!!.jsonObject
        assertEquals("8", attrs["readQueueNums"]!!.jsonPrimitive.content)
        val routes = attrs["routes"]!!.jsonArray
        assertEquals(2, routes.size)
        assertEquals("broker-a", routes[0].jsonObject["brokerName"]!!.jsonPrimitive.content)
        // clusterName 为 null 时整体省略（契约：拿不到的字段不放）。
        assertNull(nodes[0].jsonObject["attributes"]!!.jsonObject["clusterName"])
    }

    @Test
    fun consumerNodesCarryLagAndQueueRows() {
        val consumers = listOf(group("g1", "app-orders"))
        val lags = mapOf(
            "g1" to lag(
                "g1", 42,
                rows = listOf(
                    row("app-orders", "broker-a", 0, 100),
                    row("app-orders", "broker-a", 1, 20),
                    row("app-other", "broker-b", 0, 500), // 别的 topic：应被过滤掉
                ),
            ),
        )
        val nodes = Json.parseToJsonElement(
            RocketMqAiChainBuilder.topicChainJson("ns:9876", null, topic, consumers, lags),
        ).jsonArray
        assertEquals(3, nodes.size)
        val target = nodes[1].jsonObject
        assertEquals("consumed_by", target["relationToNext"]!!.jsonPrimitive.content)
        val attrs = nodes[2].jsonObject["attributes"]!!.jsonObject
        assertEquals("42", attrs["totalLag"]!!.jsonPrimitive.content)
        assertEquals("CLUSTERING", attrs["consumeMode"]!!.jsonPrimitive.content)
        val queueLags = attrs["queueLags"]!!.jsonArray
        assertEquals(2, queueLags.size)
        assertEquals("100", queueLags[0].jsonObject["lag"]!!.jsonPrimitive.content)
    }

    @Test
    fun topicConsumerRowsAreCapped() {
        val consumers = listOf(group("g1", "app-orders"))
        val rows = (0..15).map { row("app-orders", "broker-a", it, it.toLong()) }
        val lags = mapOf("g1" to lag("g1", 120, rows))
        val nodes = Json.parseToJsonElement(
            RocketMqAiChainBuilder.topicChainJson("ns:9876", null, topic, consumers, lags),
        ).jsonArray
        val queueLags = nodes[2].jsonObject["attributes"]!!.jsonObject["queueLags"]!!.jsonArray
        assertEquals(RocketMqAiChainBuilder.MAX_TOPIC_QUEUE_LAG_ROWS, queueLags.size)
    }

    @Test
    fun topicGroupWithoutLagCacheStillSelected() {
        // lag 缓存缺该组：按 0 计（仍入选垫底），节点无 queueLags 字段。
        val nodes = Json.parseToJsonElement(
            RocketMqAiChainBuilder.topicChainJson("ns:9876", null, topic, listOf(group("g1", "app-orders")), emptyMap()),
        ).jsonArray
        val attrs = nodes[2].jsonObject["attributes"]!!.jsonObject
        assertEquals("0", attrs["totalLag"]!!.jsonPrimitive.content)
        assertFalse(attrs.containsKey("queueLags"))
    }

    // ---------------- groupChainJson ----------------

    @Test
    fun groupChainCarriesProgressRowsAndSubscriptions() {
        val g = group("g1", "app-orders", "app-billing")
        val l = lag(
            "g1", 130,
            rows = listOf(
                row("app-orders", "broker-a", 0, 100),
                row("app-orders", "broker-a", 1, 30),
            ),
        )
        val nodes = Json.parseToJsonElement(
            RocketMqAiChainBuilder.groupChainJson("ns:9876", null, g, l),
        ).jsonArray
        assertEquals(2, nodes.size)
        val target = nodes[1].jsonObject
        assertEquals("CONSUMER_GROUP", target["type"]!!.jsonPrimitive.content)
        assertEquals("true", target["isTarget"]!!.jsonPrimitive.content)
        assertNull(target["relationToNext"])
        val attrs = target["attributes"]!!.jsonObject
        assertEquals(2, attrs["subscribedTopics"]!!.jsonArray.size)
        val progress = attrs["consumerProgress"]!!.jsonArray
        assertEquals(2, progress.size)
        // 行按 lag 降序（缓存口径）：第一行是 lag 最大的。
        assertEquals("100", progress[0].jsonObject["lag"]!!.jsonPrimitive.content)
        assertTrue(attrs.containsKey("totalLag"))
    }

    @Test
    fun groupChainOmitsUnavailableFields() {
        // 离线组（consumeMode=null、订阅空）+ 无 lag 缓存：字段整体省略，不报错。
        val g = RocketMqGroupSnapshot(name = "g-offline", memberCount = 0)
        val nodes = Json.parseToJsonElement(
            RocketMqAiChainBuilder.groupChainJson("ns:9876", null, g, null),
        ).jsonArray
        val attrs = nodes[1].jsonObject["attributes"]!!.jsonObject
        assertFalse(attrs.containsKey("consumeMode"))
        assertFalse(attrs.containsKey("subscribedTopics"))
        assertFalse(attrs.containsKey("totalLag"))
        assertFalse(attrs.containsKey("consumerProgress"))
    }

    @Test
    fun groupProgressRowsAreCapped() {
        val g = group("g1", "app-orders")
        val rows = (0..15).map { row("app-orders", "broker-a", it, it.toLong()) }
        val nodes = Json.parseToJsonElement(
            RocketMqAiChainBuilder.groupChainJson("ns:9876", null, g, lag("g1", 120, rows)),
        ).jsonArray
        val progress = nodes[1].jsonObject["attributes"]!!.jsonObject["consumerProgress"]!!.jsonArray
        assertEquals(RocketMqAiChainBuilder.MAX_GROUP_PROGRESS_ROWS, progress.size)
    }
}
