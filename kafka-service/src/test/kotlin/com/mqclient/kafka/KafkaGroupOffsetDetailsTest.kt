package com.mqclient.kafka

import org.apache.kafka.common.TopicPartition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 消费组位点行的**纯聚合**（`buildGroupOffsetDetails`）。
 *
 * 这三个算式是用户判断消费滞后的唯一依据，此前埋在 `getGroupDetail` 的 200 行实时
 * 查询里 —— 只能靠连真集群验证。现在离线钉住边界：
 *  - 从未提交位点 → `currentOffset` 为 **null**（UI 显示「-」），不是 0；
 *    「0 = 消费到开头」和「null = 从没提交过」是两件事。
 *  - 分区无末端位点（刚建、无消息）→ `latestOffset` 按 0 算。
 *  - 空组（无成员）→ `ownerId` 为 null。
 */
class KafkaGroupOffsetDetailsTest {

    private fun details(
        partitions: List<TopicPartition>,
        committed: Map<TopicPartition, Long> = emptyMap(),
        latest: Map<TopicPartition, Long> = emptyMap(),
        owners: Map<TopicPartition, String> = emptyMap(),
    ) = buildGroupOffsetDetails(partitions, committed, latest, owners)

    private val tp0 = TopicPartition("orders", 0)
    private val tp1 = TopicPartition("orders", 1)

    @Test
    fun `lag is end minus current`() {
        val rows = details(
            partitions = listOf(tp0),
            committed = mapOf(tp0 to 100L),
            latest = mapOf(tp0 to 1_000L),
        )

        assertEquals(900L, rows.single().lag)
        assertEquals(100L, rows.single().currentOffset)
        assertEquals(1_000L, rows.single().latestOffset)
    }

    @Test
    fun `never committed offset stays null and counts as zero for lag`() {
        val rows = details(
            partitions = listOf(tp0),
            committed = emptyMap(),
            latest = mapOf(tp0 to 500L),
        )

        assertNull(rows.single().currentOffset, "从未提交 ≠ 提交到 0")
        assertEquals(500L, rows.single().lag, "但算 lag 时按 0 处理")
    }

    @Test
    fun `missing latest offset is treated as zero`() {
        // 分区刚建、无消息：末端位点缺席。lag 会变成负的 current（历史已消费完的迹象）。
        val rows = details(
            partitions = listOf(tp0),
            committed = mapOf(tp0 to 42L),
            latest = emptyMap(),
        )

        assertEquals(0L, rows.single().latestOffset)
        assertEquals(-42L, rows.single().lag)
    }

    @Test
    fun `owner is null for partitions nobody is assigned`() {
        // 空组（无成员）：assignment 为空，但已提交位移还在 —— 行照常展示。
        val rows = details(
            partitions = listOf(tp0),
            committed = mapOf(tp0 to 10L),
            latest = mapOf(tp0 to 20L),
            owners = emptyMap(),
        )

        assertNull(rows.single().ownerId)
    }

    @Test
    fun `owner is mapped per partition`() {
        val rows = details(
            partitions = listOf(tp0, tp1),
            committed = mapOf(tp0 to 0L, tp1 to 0L),
            latest = mapOf(tp0 to 5L, tp1 to 7L),
            owners = mapOf(tp0 to "consumer-a", tp1 to "consumer-b"),
        )

        assertEquals(listOf("consumer-a", "consumer-b"), rows.map { it.ownerId })
        assertEquals(12L, rows.sumOf { it.lag }, "totalLag 是各行 lag 之和")
    }

    @Test
    fun `empty partition list yields no rows`() {
        assertEquals(emptyList(), details(emptyList()))
    }

    @Test
    fun `topic and partition are carried through`() {
        val rows = details(
            partitions = listOf(tp1),
            committed = mapOf(tp1 to 1L),
            latest = mapOf(tp1 to 2L),
        )

        assertEquals("orders", rows.single().topic)
        assertEquals(1, rows.single().partition)
    }
}
