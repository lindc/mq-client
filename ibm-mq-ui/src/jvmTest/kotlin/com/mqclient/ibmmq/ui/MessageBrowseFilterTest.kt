package com.mqclient.ibmmq.ui

import com.mqclient.ibmmq.messaging.MessageSummary
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 浏览页的**本地过滤**（搜索 + 时间范围）。
 *
 * 这段逻辑此前是 Composable 文件里的 `private fun`，没有一行测试 —— 而它包含
 * 最容易写错的那类条件：相对时间窗的端点、CUSTOM 区间只有单侧、以及「时间戳解析
 * 不出」的脏数据该对哪些档位可见。[now] 现在由参数注入，边界才能测准。
 */
class MessageBrowseFilterTest {

    private val now = LocalDateTime.of(2026, 10, 27, 12, 0, 0)

    private fun message(
        id: String = "AA",
        correlationId: String = "BB",
        at: LocalDateTime? = null,
        rawDate: String = "20261027",
        rawTime: String = "12000000",
    ) = MessageSummary(
        messageId = id,
        correlationId = correlationId,
        putDate = if (at != null) "%04d%02d%02d".format(at.year, at.monthValue, at.dayOfMonth) else rawDate,
        putTime = if (at != null) "%02d%02d%02d00".format(at.hour, at.minute, at.second) else rawTime,
        dataLength = 1,
        persistence = 0,
        priority = 4,
        msgType = 8,
    )

    private fun filter(
        messages: List<MessageSummary>,
        search: String = "",
        range: BrowseTimeRange = BrowseTimeRange.ALL,
        start: LocalDateTime? = null,
        end: LocalDateTime? = null,
    ) = filterMessages(messages, search, range, start, end, now)

    /* ------------------------------ 搜索 ------------------------------ */

    @Test
    fun `blank search keeps everything`() {
        val rows = listOf(message("AA"), message("BB"))
        assertEquals(rows, filter(rows))
        assertEquals(rows, filter(rows, search = "   "), "纯空白等于没筛选")
    }

    @Test
    fun `search matches message id and correlation id case insensitively`() {
        val rows = listOf(message(id = "AB12CD", correlationId = "ZZ99"), message(id = "FF", correlationId = "AB34"))

        assertEquals(2, filter(rows, search = "ab").size, "MessageId / CorrelationId 都参与匹配")
        assertEquals(1, filter(rows, search = "zz99").size)
        assertEquals(0, filter(rows, search = "nope").size)
    }

    @Test
    fun `search is trimmed`() {
        val rows = listOf(message(id = "AB"))
        assertEquals(1, filter(rows, search = "  AB  ").size)
    }

    /* ------------------------------ 时间范围 ------------------------------ */

    @Test
    fun `ALL ignores timestamps entirely`() {
        // 时间解析不出来的脏数据在「全部」下必须可见。
        val broken = message(rawDate = "bogus", rawTime = "bogus")
        assertEquals(null, broken.putDateTime, "前置条件：这条解析不出时间")

        assertEquals(1, filter(listOf(broken), range = BrowseTimeRange.ALL).size)
    }

    @Test
    fun `relative windows exclude older messages`() {
        val rows = listOf(
            message("A", at = now.minusMinutes(1)),
            message("B", at = now.minusMinutes(10)),
            message("C", at = now.minusHours(3)),
            message("D", at = now.minusHours(30)),
        )

        assertEquals(listOf("A"), filter(rows, range = BrowseTimeRange.LAST_5_MIN).map { it.messageId })
        assertEquals(listOf("A", "B"), filter(rows, range = BrowseTimeRange.LAST_1_HOUR).map { it.messageId })
        assertEquals(listOf("A", "B", "C"), filter(rows, range = BrowseTimeRange.LAST_24_HOURS).map { it.messageId })
        assertEquals(4, filter(rows, range = BrowseTimeRange.ALL).size)
    }

    @Test
    fun `relative window boundary is inclusive`() {
        // 恰好 5 分钟前：!isBefore(start) → 保留（含端点）。
        val exactly = now.minusMinutes(5)
        assertEquals(1, filter(listOf(message("A", at = exactly)), range = BrowseTimeRange.LAST_5_MIN).size)

        val justOutside = now.minusMinutes(5).minusSeconds(1)
        assertEquals(0, filter(listOf(message("A", at = justOutside)), range = BrowseTimeRange.LAST_5_MIN).size)
    }

    @Test
    fun `unparseable timestamp is hidden in any explicit range`() {
        // 说不清时间就不能声称它落在窗口内 —— 只有「全部」可见（上一例）。
        val broken = message(rawDate = "bogus", rawTime = "bogus")

        assertTrue(filter(listOf(broken), range = BrowseTimeRange.LAST_24_HOURS).isEmpty())
        assertTrue(filter(listOf(broken), range = BrowseTimeRange.CUSTOM).isEmpty())
    }

    /* ------------------------------ 自定义区间 ------------------------------ */

    @Test
    fun `custom range with only start is open ended`() {
        val rows = listOf(
            message("old", at = now.minusHours(2)),
            message("new", at = now.plusHours(2)),
        )

        assertEquals(
            listOf("new"),
            filter(rows, range = BrowseTimeRange.CUSTOM, start = now).map { it.messageId },
        )
    }

    @Test
    fun `custom range with only end is open started`() {
        val rows = listOf(
            message("old", at = now.minusHours(2)),
            message("new", at = now.plusHours(2)),
        )

        assertEquals(
            listOf("old"),
            filter(rows, range = BrowseTimeRange.CUSTOM, start = null, end = now).map { it.messageId },
        )
    }

    @Test
    fun `custom range with neither bound keeps everything timestamped`() {
        val rows = listOf(message("A", at = now.minusHours(5)), message("B", at = now.plusHours(5)))

        assertEquals(2, filter(rows, range = BrowseTimeRange.CUSTOM).size)
    }

    @Test
    fun `custom range bounds are inclusive`() {
        val start = now.minusHours(1)
        val end = now.plusHours(1)
        val rows = listOf(message("atStart", at = start), message("atEnd", at = end))

        assertEquals(2, filter(rows, range = BrowseTimeRange.CUSTOM, start = start, end = end).size)
    }

    @Test
    fun `search and time range compose with AND`() {
        val rows = listOf(
            message("AB", at = now.minusMinutes(1)),
            message("AB", at = now.minusHours(3)),
            message("ZZ", at = now.minusMinutes(1)),
        )

        val hit = filter(rows, search = "AB", range = BrowseTimeRange.LAST_5_MIN)

        assertEquals(1, hit.size, "搜索与时间窗是 AND，不是 OR")
        assertEquals("AB", hit.single().messageId)
        assertTrue(hit.single().putDateTime!!.isAfter(now.minusMinutes(5)))
    }
}
