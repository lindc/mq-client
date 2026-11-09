package com.mqclient.ibmmq.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BrowseCursor] 的核心不变式：**无论队列在两次翻页之间怎么变，都不能漏交付、
 * 不能重复交付**。
 *
 * 背景：浏览会话每次翻页都重新开句柄，MQI 的光标（句柄级）随之丢失。旧实现用
 * 「已交付条数」从头跳过同样多条消息来恢复位置，于是：
 *  - 用户删掉一条 → 队列短一条，下一页会多跳一条从未展示过的消息（静默丢数据）；
 *  - 优先级队列插入一条 → 同一页可能重复交付同一 MessageId → Compose key 冲突崩溃。
 */
class BrowseCursorTest {

    @Test
    fun `deleting a delivered message does not skip an undelivered one`() {
        val cursor = BrowseCursor()
        // 第一页交付了 A / B / C（队列顺序即交付顺序）。
        listOf("A", "B", "C").forEach(cursor::recordDelivered)

        // 用户删掉了 B —— 队列现在只剩 A / C / D / E。
        cursor.onMessageRemoved("B")

        val scan = cursor.startScan()
        assertTrue(scan.shouldSkip("A"), "A 已交付过")
        assertTrue(scan.shouldSkip("C"), "C 已交付过")
        assertFalse(scan.shouldSkip("D"), "D 从未展示过，不能被跳过")
        assertFalse(scan.shouldSkip("E"), "E 从未展示过，不能被跳过")
    }

    @Test
    fun `a fresh cursor skips nothing`() {
        val scan = BrowseCursor().startScan()
        assertFalse(scan.shouldSkip("A"))
        assertFalse(scan.shouldSkip("A"))
    }

    @Test
    fun `duplicate message ids are tracked as a multiset`() {
        val cursor = BrowseCursor()
        // 极端输入：队列里两条消息共用同一个 MessageId（MQ 允许）。
        cursor.recordDelivered("X")

        val scan = cursor.startScan()
        assertTrue(scan.shouldSkip("X"), "第一条 X 已交付")
        assertFalse(scan.shouldSkip("X"), "第二条 X 还没交付，不能被吞掉")
    }

    @Test
    fun `reset forgets everything after a refresh`() {
        val cursor = BrowseCursor()
        cursor.recordDelivered("A")
        assertEquals(1, cursor.trackedCount())

        cursor.reset()

        assertEquals(0, cursor.trackedCount())
        assertFalse(cursor.startScan().shouldSkip("A"))
    }

    @Test
    fun `removing an unknown message is a no-op`() {
        val cursor = BrowseCursor()
        cursor.recordDelivered("A")
        cursor.onMessageRemoved("missing")
        assertEquals(1, cursor.trackedCount())
    }

    @Test
    fun `skip state is scoped to one scan`() {
        val cursor = BrowseCursor()
        cursor.recordDelivered("A")

        // 同一页里扫描两次不能把 A 的记账消耗掉，否则下一次翻页会重复交付。
        assertTrue(cursor.startScan().shouldSkip("A"))
        assertTrue(cursor.startScan().shouldSkip("A"))
        assertEquals(1, cursor.trackedCount())
    }
}

/**
 * 重建锚点（[BrowseCursor.lastLiveDeliveredId]）的决定性语义。
 *
 * 句柄失效后重新打开时，整页是否 O(1) 完全取决于这条 Id：它必须指向**最后一条
 * 仍在队列里的**已交付消息。指向已删除的尾部条目 → 每次重建的匹配都落空、
 * 退化成 O(n) 队首扫描。
 */
class BrowseCursorAnchorTest {

    @Test
    fun `anchor is the last delivered message`() {
        val cursor = BrowseCursor()
        listOf("A", "B", "C").forEach(cursor::recordDelivered)

        assertEquals("C", cursor.lastLiveDeliveredId())
    }

    @Test
    fun `anchor retreats when the tail message is deleted`() {
        val cursor = BrowseCursor()
        listOf("A", "B", "C").forEach(cursor::recordDelivered)

        cursor.onMessageRemoved("C")

        assertEquals("B", cursor.lastLiveDeliveredId(), "尾部被删后应退到前一条还存在的")
    }

    @Test
    fun `anchor retreats past several deleted tail messages`() {
        val cursor = BrowseCursor()
        listOf("A", "B", "C", "D").forEach(cursor::recordDelivered)

        cursor.onMessageRemoved("D")
        cursor.onMessageRemoved("C")
        cursor.onMessageRemoved("B")

        assertEquals("A", cursor.lastLiveDeliveredId())
    }

    @Test
    fun `deleting a middle message keeps the anchor`() {
        val cursor = BrowseCursor()
        listOf("A", "B", "C").forEach(cursor::recordDelivered)

        cursor.onMessageRemoved("B")

        assertEquals("C", cursor.lastLiveDeliveredId(), "删中间一条不影响锚点")
    }

    @Test
    fun `anchor is null when everything delivered was deleted`() {
        val cursor = BrowseCursor()
        listOf("A", "B").forEach(cursor::recordDelivered)

        cursor.onMessageRemoved("A")
        cursor.onMessageRemoved("B")

        assertNull(cursor.lastLiveDeliveredId(), "全删光后没有锚点，重建应从队首开始")
    }

    @Test
    fun `anchor is null for a fresh cursor`() {
        assertNull(BrowseCursor().lastLiveDeliveredId())
    }

    @Test
    fun `reset clears the anchor`() {
        val cursor = BrowseCursor()
        cursor.recordDelivered("A")

        cursor.reset()

        assertNull(cursor.lastLiveDeliveredId())
    }

    @Test
    fun `duplicate message ids keep the anchor at the last occurrence`() {
        val cursor = BrowseCursor()
        cursor.recordDelivered("X")
        cursor.recordDelivered("X")

        assertEquals("X", cursor.lastLiveDeliveredId())
        cursor.onMessageRemoved("X")
        assertEquals("X", cursor.lastLiveDeliveredId(), "还有一条 X 在队列里，锚点仍是它")
        cursor.onMessageRemoved("X")
        assertNull(cursor.lastLiveDeliveredId())
    }
}
