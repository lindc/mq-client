package com.mqclient.starrocks.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 数值展示辅助函数的契约（[formatPercent] / [percentOf] / [formatBytes]）。
 *
 * 这三个都被多个面板共用（节点属性对话框、集群概览、BE / CN 表格），
 * 改动等于改三处显示 —— 边界钉在这里，比在三个面板的测试里各断言一次可靠。
 */
class StarRocksFormatHelpersTest {

    // ---------------- formatPercent ----------------

    @Test
    fun `percent keeps precision by magnitude`() {
        // ≥10 取整、≥1 一位小数、<1 两位小数 —— 保留小数是因为 CPU 使用率常态是个位数。
        assertEquals("100%", formatPercent(100.0))
        assertEquals("39%", formatPercent(39.03))
        assertEquals("2.6%", formatPercent(2.63))
        assertEquals("0.53%", formatPercent(0.534))
    }

    @Test
    fun `zero is rendered as a real value`() {
        // 0 是合法读数（磁盘 0% 使用），不能被当成「无数据」吞掉。
        assertEquals("0.0%", formatPercent(0.0))
    }

    /**
     * 非有限值不是 0%，也不是任何有意义的使用率。
     *
     * 实测旧实现的两个真实输出：NaN → `NaN%`；Infinity →
     * `9223372036854775807%`（`kotlin.math.round(∞).toLong()` 饱和到 Long.MAX_VALUE）。
     * 两者都是乱码/天文数字，统一按「无数据」渲染成 `-`。
     */
    @Test
    fun `non-finite values render as no-data`() {
        assertEquals("-", formatPercent(Double.NaN))
        assertEquals("-", formatPercent(Double.POSITIVE_INFINITY))
        assertEquals("-", formatPercent(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `negative values keep their sign`() {
        // 负使用率是数据异常，如实显示比吞成 "-" 更容易被发现。
        assertEquals("-5.0%", formatPercent(-5.0))
    }

    // ---------------- percentOf ----------------

    @Test
    fun `percentOf guards against an invalid denominator`() {
        assertEquals("", percentOf(used = 10, total = 0), "分母为 0 返回空串，调用方不渲染")
        assertEquals("", percentOf(used = 10, total = -1))
    }

    @Test
    fun `percentOf computes the ratio`() {
        assertEquals("50%", percentOf(used = 512, total = 1024))
        assertEquals("0.1%", percentOf(used = 1, total = 1024))
    }

    // ---------------- formatBytes（委托 ui-common） ----------------

    @Test
    fun `formatBytes delegates to the shared implementation`() {
        // 模块内的 formatBytes 只是 ui-common formatSize 的别名 —— 钉住一致性，
        // 防止有人在这里重新实现一份。
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("236.5 GB", formatBytes(253_910_950_346))
    }
}
