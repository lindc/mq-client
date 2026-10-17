package com.mqclient.kafka.ui.ai

import com.mqclient.kafka.KafkaGroupLagSnapshot
import com.mqclient.kafka.KafkaGroupSnapshot
import com.mqclient.kafka.KafkaPartitionLag
import com.mqclient.kafka.ui.KafkaTopicInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * KafkaAiChainBuilder 纯逻辑的离线单测（不碰 KafkaModule / 网络）：消费组挑选与
 * chain JSON 结构。节点契约（type / isTarget / relationToNext / attributes）见构建器 KDoc。
 */
class KafkaAiChainBuilderTest {

    private val topic = KafkaTopicInfo(name = "app-orders", partitions = 3, replicas = 2, internal = false)

    private fun group(id: String, vararg topics: String) =
        KafkaGroupSnapshot(groupId = id, state = "Stable", members = 2, simple = false, subscribedTopics = topics.toSet())

    private fun lag(id: String, totalLag: Long, rows: List<KafkaPartitionLag> = emptyList()) =
        KafkaGroupLagSnapshot(groupId = id, totalLag = totalLag, partitions = rows)

    // ---------------- selectConsumerGroups ----------------

    @Test
    fun selectFiltersBySubscribedTopic() {
        val groups = listOf(
            group("g-orders", "app-orders", "app-billing"),
            group("g-other", "app-other"),
        )
        val selected = KafkaAiChainBuilder.selectConsumerGroups("app-orders", groups, emptyMap())
        assertEquals(listOf("g-orders"), selected.map { it.groupId })
    }

    @Test
    fun selectSortsByTotalLagDescending() {
        val groups = listOf(group("g-small", "t"), group("g-big", "t"), group("g-none", "t"))
        val lags = mapOf("g-small" to lag("g-small", 10), "g-big" to lag("g-big", 999))
        val selected = KafkaAiChainBuilder.selectConsumerGroups("t", groups, lags)
        assertEquals(listOf("g-big", "g-small", "g-none"), selected.map { it.groupId })
    }

    @Test
    fun selectCapsAtMaxGroups() {
        val groups = (0..9).map { group("g$it", "t") }
        val lags = groups.associate { it.groupId to lag(it.groupId, it.groupId.removePrefix("g").toLong()) }
        val selected = KafkaAiChainBuilder.selectConsumerGroups("t", groups, lags)
        assertEquals(KafkaAiChainBuilder.MAX_CONSUMER_GROUPS, selected.size)
        // 保留的是 lag 最大的前 N 个。
        assertEquals(listOf("g9", "g8", "g7", "g6"), selected.map { it.groupId })
    }

    // ---------------- chainJson ----------------

    @Test
    fun twoNodeChainWithoutConsumersHasNoConsumedBy() {
        val nodes = KafkaAiChainBuilder.chainJson("b1:9092", topic, emptyList(), emptyMap()).let {
            kotlinx.serialization.json.Json.parseToJsonElement(it).jsonArray
        }
        assertEquals(2, nodes.size)
        assertEquals("CLUSTER", nodes[0].jsonObject["type"]!!.jsonPrimitive.content)
        val target = nodes[1].jsonObject
        assertEquals("TOPIC", target["type"]!!.jsonPrimitive.content)
        assertEquals("true", target["isTarget"]!!.jsonPrimitive.content)
        assertNull(target["relationToNext"])
    }

    @Test
    fun consumerNodesCarryStateTotalLagAndPartitionRows() {
        val consumers = listOf(group("g1", "app-orders"))
        val lags = mapOf(
            "g1" to lag(
                "g1", 42,
                rows = listOf(
                    KafkaPartitionLag("app-orders", 0, 10, 15, 5),
                    KafkaPartitionLag("app-orders", 1, 20, 57, 37),
                    KafkaPartitionLag("unrelated", 0, 1, 2, 1),
                ),
            ),
        )
        val nodes = KafkaAiChainBuilder.chainJson("b1:9092", topic, consumers, lags)
            .let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonArray }
        assertEquals(3, nodes.size)
        // TOPIC 带 consumed_by（有下游时才出现）。
        assertEquals("consumed_by", nodes[1].jsonObject["relationToNext"]!!.jsonPrimitive.content)
        val group = nodes[2].jsonObject
        assertEquals("CONSUMER_GROUP", group["type"]!!.jsonPrimitive.content)
        assertEquals("g1", group["name"]!!.jsonPrimitive.content)
        assertEquals("false", group["isTarget"]!!.jsonPrimitive.content)
        // 兄弟节点之间无 relationToNext（线性化约定）。
        assertNull(group["relationToNext"])
        val attrs = group["attributes"]!!.jsonObject
        assertEquals("Stable", attrs["state"]!!.jsonPrimitive.content)
        assertEquals("42", attrs["totalLag"]!!.jsonPrimitive.content)
        val rows = attrs["topicPartitionLags"]!!.jsonArray
        assertEquals(2, rows.size)
        val row0 = rows[0].jsonObject
        assertEquals(5, row0["lag"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, row0["partition"]!!.jsonPrimitive.content.toInt())
        assertTrue(rows.all { it.jsonObject.containsKey("currentOffset") && it.jsonObject.containsKey("latestOffset") })
    }

    @Test
    fun partitionRowsCappedAtMax() {
        val consumers = listOf(group("g1", "app-orders"))
        val lags = mapOf(
            "g1" to lag(
                "g1", 100,
                rows = (0..19).map { KafkaPartitionLag("app-orders", it, 0, it.toLong(), it.toLong()) },
            ),
        )
        val nodes = KafkaAiChainBuilder.chainJson("b1:9092", topic, consumers, lags)
            .let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonArray }
        val attrs = nodes[2].jsonObject["attributes"]!!.jsonObject
        assertEquals(KafkaAiChainBuilder.MAX_LAG_ROWS, attrs["topicPartitionLags"]!!.jsonArray.size)
    }
}
