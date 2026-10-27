package com.mqclient.aiui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 渲染节流的可观察契约（假时钟，不依赖真实时间）：
 *  - 首片立即上屏（不吞第一个字，避免“点了没反应”）；
 *  - 间隔内连续 append 不再刷（这是 O(n²) → O(n·秒数) 的来源）；
 *  - 跨过间隔后刷一次，且给的是**完整快照**（不是增量）；
 *  - force 无视间隔（流结束 / 取消时必须能拿到尾巴）。
 */
class StreamingReplyBufferTest {

    /** 可拨动的假时钟。 */
    private class FakeClock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) {
            now += ms
        }
    }

    @Test
    fun `first chunk is rendered immediately`() {
        val clock = FakeClock()
        val buffer = StreamingReplyBuffer(intervalMs = 100, clock = clock)

        buffer.append("Hel")

        assertEquals("Hel", buffer.takeIfDue(), "首片必须立刻可见")
    }

    @Test
    fun `chunks within the interval do not trigger a render`() {
        val clock = FakeClock()
        val buffer = StreamingReplyBuffer(intervalMs = 100, clock = clock)
        buffer.append("a")
        buffer.takeIfDue() // 首片

        clock.advance(30)
        repeat(500) { buffer.append("x") }

        assertNull(buffer.takeIfDue(), "间隔内不该再刷（否则等于没节流）")
        assertEquals(501, buffer.receivedChunks)
        assertEquals(1 + 500, buffer.currentText().length)
    }

    @Test
    fun `crossing the interval renders the full snapshot`() {
        val clock = FakeClock()
        val buffer = StreamingReplyBuffer(intervalMs = 100, clock = clock)
        buffer.append("a")
        buffer.takeIfDue()

        buffer.append("b")
        clock.advance(99)
        assertNull(buffer.takeIfDue(), "99ms 还不到点")

        clock.advance(1)
        assertEquals("ab", buffer.takeIfDue(), "跨过 100ms 应刷出完整快照")
    }

    @Test
    fun `force flushes regardless of the interval`() {
        val clock = FakeClock()
        val buffer = StreamingReplyBuffer(intervalMs = 100, clock = clock)
        buffer.append("a")
        buffer.takeIfDue()

        buffer.append("tail")
        clock.advance(1)

        assertEquals("atail", buffer.takeIfDue(force = true), "收尾必须能拿到尾巴")
    }

    @Test
    fun `no render when nothing is pending`() {
        val buffer = StreamingReplyBuffer(intervalMs = 100, clock = FakeClock())

        assertNull(buffer.takeIfDue(), "空缓冲不刷")
        buffer.append("a")
        assertEquals("a", buffer.takeIfDue())
        assertNull(buffer.takeIfDue(force = true), "已刷过且无新内容时 force 也不重复刷")
    }

    @Test
    fun `empty chunks are ignored`() {
        val buffer = StreamingReplyBuffer(intervalMs = 100, clock = FakeClock())

        buffer.append("")

        assertEquals(0, buffer.receivedChunks)
        assertNull(buffer.takeIfDue())
    }

    /**
     * 端到端形态：1000 个分片、每 5ms 一个（总时长 5s）时，渲染次数应是
     * 「首片 + 约 50 次」而不是 1000 次 —— 这正是重解析成本被压下去的量级。
     */
    @Test
    fun `render count scales with time not with chunk count`() {
        val clock = FakeClock()
        val buffer = StreamingReplyBuffer(intervalMs = 100, clock = clock)
        var renders = 0

        repeat(1_000) {
            buffer.append("x")
            clock.advance(5)
            if (buffer.takeIfDue() != null) renders++
        }

        // 5000ms / 100ms ≈ 50 次，外加首片；给足余量断言“远小于分片数”。
        assertEquals(true, renders in 2..80, "渲染次数应与时间成正比，实际 $renders")
    }
}
