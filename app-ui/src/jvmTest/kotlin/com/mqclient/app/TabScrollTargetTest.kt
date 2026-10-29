package com.mqclient.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tab 条「把激活 Tab 滚进视口」的几何计算（`tabScrollTarget`）。
 *
 * 真正会坏的是边界：贴边时反复起滚动动画（抖动）、两侧都被裁时不知道该露哪边、
 * 视口还没测出来时算出垃圾值。滚动 / 动画本身归 Compose，这里只钉纯函数。
 */
class TabScrollTargetTest {

    @Test
    fun `fully visible tab needs no scroll`() {
        assertNull(tabScrollTarget(tabRange = 100..200, scroll = 0, viewportWidth = 500))
    }

    @Test
    fun `tab flush with the right edge needs no scroll`() {
        // last = 500，正好等于 scroll + viewportWidth —— 不算被裁，别抖一下。
        assertNull(tabScrollTarget(tabRange = 300..500, scroll = 0, viewportWidth = 500))
    }

    @Test
    fun `tab cut on the right scrolls right`() {
        assertEquals(
            120,
            tabScrollTarget(tabRange = 520..620, scroll = 0, viewportWidth = 500),
        )
    }

    @Test
    fun `tab cut on the left scrolls left`() {
        assertEquals(
            100,
            tabScrollTarget(tabRange = 100..200, scroll = 150, viewportWidth = 500),
        )
    }

    @Test
    fun `margin keeps the tab off the viewport edge`() {
        // 右侧：target = last + margin - viewportWidth
        assertEquals(
            8,
            tabScrollTarget(tabRange = 395..500, scroll = 0, viewportWidth = 500, marginPx = 8),
        )
        // 左侧：target = first - margin
        assertEquals(
            92,
            tabScrollTarget(tabRange = 100..200, scroll = 150, viewportWidth = 500, marginPx = 8),
        )
    }

    @Test
    fun `tab wider than the viewport aligns its left edge`() {
        assertEquals(
            300,
            tabScrollTarget(tabRange = 300..900, scroll = 400, viewportWidth = 500),
        )
    }

    @Test
    fun `unmeasured viewport or empty range yields no target`() {
        assertNull(tabScrollTarget(tabRange = 0..100, scroll = 0, viewportWidth = 0))
        assertNull(tabScrollTarget(tabRange = IntRange.EMPTY, scroll = 0, viewportWidth = 500))
    }
}
