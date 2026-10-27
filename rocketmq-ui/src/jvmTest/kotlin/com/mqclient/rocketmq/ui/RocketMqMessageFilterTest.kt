package com.mqclient.rocketmq.ui

import com.mqclient.rocketmq.RocketMqMessage
import com.mqclient.uicommon.table.formatTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * RocketMQ 浏览页的**本地搜索过滤**（只作用于已加载的消息，不重新拉取）。
 *
 * 与 Kafka 版同一套契约，但多两个 RocketMQ 特有字段（tags / keys）—— 它们都是
 * **可空**，null 不能因为字符串 "null" 而命中，这是最容易写错的一格。
 * 这段逻辑此前是 Composable 里的 `private fun`，没有测试。
 */
class RocketMqMessageFilterTest {

    private fun message(
        offset: Long = 0,
        msgId: String = "7F00000100002A9F0000000000000001",
        tags: String? = null,
        keys: String? = null,
        valuePreview: String = "hello",
        valueHex: String = "68656c6c6f",
        storeTimestamp: Long = 1_700_000_000_000L,
    ) = RocketMqMessage(
        topic = "orders",
        queueId = 0,
        offset = offset,
        msgId = msgId,
        tags = tags,
        keys = keys,
        bornTimestamp = storeTimestamp,
        storeTimestamp = storeTimestamp,
        bornHost = "127.0.0.1:50000",
        valuePreview = valuePreview,
        valueHex = valueHex,
        valueProbablyBinary = false,
        valueSize = valuePreview.length,
        body = valuePreview.toByteArray(),
        userProperties = emptyMap(),
    )

    @Test
    fun `blank search returns the same list instance`() {
        val rows = listOf(message())
        assertSame(rows, filterMessages(rows, ""))
        assertSame(rows, filterMessages(rows, "  "))
    }

    @Test
    fun `matches message id`() {
        val rows = listOf(message(msgId = "AC1400"))
        assertEquals(1, filterMessages(rows, "ac14").size, "大小写不敏感")
    }

    @Test
    fun `matches tags and keys when present`() {
        val rows = listOf(message(tags = "ORDER_CREATED", keys = "order-42"))

        assertEquals(1, filterMessages(rows, "order_created").size)
        assertEquals(1, filterMessages(rows, "order-42").size)
    }

    @Test
    fun `absent tags and keys never match`() {
        val rows = listOf(message(tags = null, keys = null))
        // null 不能因为字符串 "null" 命中 —— 这是可空字段筛选最典型的错法。
        assertTrue(filterMessages(rows, "null").isEmpty())
    }

    @Test
    fun `matches value preview and hex`() {
        val rows = listOf(message(valuePreview = "订单已创建", valueHex = "E8AEA2E58D95"))

        assertEquals(1, filterMessages(rows, "订单").size)
        assertEquals(1, filterMessages(rows, "e8ae").size)
    }

    @Test
    fun `matches offset and formatted store time`() {
        val rows = listOf(message(offset = 987, storeTimestamp = 1_700_000_000_000L))

        assertEquals(1, filterMessages(rows, "987").size)
        assertEquals(1, filterMessages(rows, formatTimestamp(1_700_000_000_000L).take(10)).size)
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(filterMessages(listOf(message()), "nothing-here").isEmpty())
    }
}
