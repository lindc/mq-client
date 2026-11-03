package com.mqclient.rocketmq

import org.apache.rocketmq.common.message.MessageQueue
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats
import org.apache.rocketmq.remoting.protocol.admin.OffsetWrapper
import org.apache.rocketmq.remoting.protocol.body.ConsumeStatsList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * broker 直读统计 → UI 行（`brokerStatsToRows`）。
 *
 * 这是 CODE 17 兜底路径的落点：组级查询被 `%RETRY%` 路由挡住后，逐 broker 读
 * ConsumerOffsetManager 的原始数据必须被**正确过滤**——只留本组、去重、排序。
 *
 * 用例里的形状来自真实响应（chillbear 集群 `brokerConsumeStats`）：
 * `List<Map<groupId, List<ConsumeStats>>>`，一台 broker 返回它上面所有组。
 */
class RocketMqBrokerStatsRowsTest {

    private fun stats(vararg rows: Triple<String, Int, Pair<Long, Long>>): ConsumeStats =
        ConsumeStats().apply {
            offsetTable = rows.associate { (broker, queueId, offsets) ->
                MessageQueue("ChillbearNormal", broker, queueId) to
                    OffsetWrapper().apply {
                        consumerOffset = offsets.first
                        brokerOffset = offsets.second
                    }
            }
        }

    private fun listOfBroker(vararg entries: Pair<String, List<ConsumeStats>>): ConsumeStatsList =
        ConsumeStatsList().apply {
            consumeStatsList = entries.map { (group, stats) -> mapOf(group to stats.toList()) }
        }

    @Test
    fun `keeps only the requested group`() {
        val list = listOfBroker(
            "CG_PROGRESS_PROBE" to listOf(stats(Triple("broker-b", 0, 1L to 1L))),
            // 同一台 broker 上的其它组必须被丢掉
            "CG_OTHER" to listOf(stats(Triple("broker-b", 5, 7L to 9L))),
        )
        val rows = brokerStatsToRows("CG_PROGRESS_PROBE", list)
        assertEquals(1, rows.size)
        assertEquals("broker-b", rows[0].broker)
        assertEquals(0, rows[0].queueId)
    }

    @Test
    fun `deduplicates the same queue reported by multiple brokers`() {
        // 主从切换残留：两台 broker 都记了同一队列。
        val list = listOfBroker(
            "G" to listOf(
                stats(Triple("broker-a", 1, 5L to 10L)),
                stats(Triple("broker-a", 1, 5L to 10L)),
            ),
        )
        val rows = brokerStatsToRows("G", list)
        assertEquals(1, rows.size, "同一 (topic, broker, queue) 只能出现一行，实测 ${rows.size}")
    }

    @Test
    fun `lag is computed and never negative`() {
        val list = listOfBroker(
            "G" to listOf(
                stats(Triple("broker-a", 0, 3L to 10L)),   // lag 7
                stats(Triple("broker-a", 1, 9L to 4L)),    // 消费位点超前 → 截 0
            ),
        )
        val rows = brokerStatsToRows("G", list)
        assertEquals(7L, rows.first { it.queueId == 0 }.lag)
        assertEquals(0L, rows.first { it.queueId == 1 }.lag)
    }

    @Test
    fun `rows are sorted by topic then broker then queue`() {
        val list = listOfBroker(
            "G" to listOf(
                stats(Triple("broker-c", 2, 0L to 0L)),
                stats(Triple("broker-a", 3, 0L to 0L)),
                stats(Triple("broker-a", 1, 0L to 0L)),
            ),
        )
        val rows = brokerStatsToRows("G", list)
        assertEquals(
            listOf("broker-a" to 1, "broker-a" to 3, "broker-c" to 2),
            rows.map { it.broker to it.queueId },
        )
    }

    @Test
    fun `null or empty responses yield no rows`() {
        assertTrue(brokerStatsToRows("G", null).isEmpty())
        assertTrue(brokerStatsToRows("G", ConsumeStatsList()).isEmpty())
        assertTrue(
            brokerStatsToRows("G", listOfBroker("OTHER" to listOf(stats(Triple("b", 0, 0L to 0L)))))
                .isEmpty(),
            "别的组的条目不能泄漏过来",
        )
    }
}
