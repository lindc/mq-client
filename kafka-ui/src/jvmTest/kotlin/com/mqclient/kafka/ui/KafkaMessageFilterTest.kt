package com.mqclient.kafka.ui

import com.mqclient.kafka.KafkaMessageSummary
import com.mqclient.uicommon.table.formatTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Kafka 浏览页的**本地搜索过滤**（只作用于已加载的消息，不重新读集群）。
 *
 * 这段逻辑此前是 Composable 文件里的 `private fun`，没有测试。它覆盖的字段比
 * 看上去多（key 预览 / key hex / value 预览 / value hex / 时间文本 / offset），
 * 漏改一个就是「明明有这条消息却搜不到」。
 */
class KafkaMessageFilterTest {

    private fun message(
        offset: Long = 0,
        keyPreview: String? = null,
        keyHex: String? = null,
        valuePreview: String = "hello",
        valueHex: String = "68656c6c6f",
        timestamp: Long = 1_700_000_000_000L,
    ) = KafkaMessageSummary(
        topic = "orders",
        partition = 0,
        offset = offset,
        timestamp = timestamp,
        timestampType = "CREATE_TIME",
        keyPreview = keyPreview,
        keyHex = keyHex,
        valuePreview = valuePreview,
        valueHex = valueHex,
        valueProbablyBinary = false,
        valueSize = valuePreview.length,
    )

    @Test
    fun `blank search returns the same list instance`() {
        val rows = listOf(message())
        // 没筛选就别复制列表：调用方在组合期每帧调用它。
        assertSame(rows, filterMessages(rows, ""))
        assertSame(rows, filterMessages(rows, "   "))
    }

    @Test
    fun `matches value preview and hex`() {
        val rows = listOf(message(valuePreview = "订单已创建", valueHex = "E8AEA2E58D95"))

        assertEquals(1, filterMessages(rows, "订单").size)
        assertEquals(1, filterMessages(rows, "e8ae").size, "hex 串大小写不敏感")
    }

    @Test
    fun `matches key preview and key hex when present`() {
        val rows = listOf(message(keyPreview = "order-42", keyHex = "6f726465722d3432"))

        assertEquals(1, filterMessages(rows, "order-42").size)
        assertEquals(1, filterMessages(rows, "6F72").size)
    }

    @Test
    fun `absent key never matches`() {
        val rows = listOf(message(keyPreview = null, keyHex = null))
        // null 的 key 不该因为 "null" 字符串命中。
        assertTrue(filterMessages(rows, "null").isEmpty())
    }

    @Test
    fun `matches offset as text`() {
        val rows = listOf(message(offset = 1234))
        assertEquals(1, filterMessages(rows, "1234").size)
        assertEquals(0, filterMessages(rows, "1235").size)
    }

    @Test
    fun `matches formatted timestamp`() {
        val rows = listOf(message(timestamp = 1_700_000_000_000L))
        val formatted = formatTimestamp(1_700_000_000_000L)

        assertEquals(1, filterMessages(rows, formatted.take(10)).size, "按日期文本可搜到：$formatted")
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(filterMessages(listOf(message()), "definitely-not-there").isEmpty())
    }
}
