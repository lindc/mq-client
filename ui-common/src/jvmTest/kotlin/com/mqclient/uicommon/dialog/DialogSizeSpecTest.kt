package com.mqclient.uicommon.dialog

import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 标准弹窗尺寸（[computeStandardDialogSize]）的无头测试。
 *
 * 验收点：所有 MQ 模块的属性 / 消息对话框统一走 [DialogSizeClass.CONTENT]（屏幕 85%），
 * 初始尺寸再钳到 [DialogSizeSpec.MAX_SCREEN_FRACTION]（95% 屏幕）—— 在 1080p 及更小的
 * 常见屏幕上不能溢出屏幕；极小屏钳到统一 min、不抛异常。尺寸计算抽成纯函数就是为了让
 * 这些性质不依赖 AWT / Compose 帧管线即可验证。
 */
class DialogSizeSpecTest {

    /** 1080p 是验收点名的「小屏」，其余为逐级更小的常见分辨率。 */
    private val screens = listOf(
        1920f to 1080f,
        1366f to 768f,
        1280f to 720f,
        1024f to 600f,
        800f to 480f,
    )

    @Test
    fun `CONTENT is 85 percent of a 1080p screen`() {
        val size = computeStandardDialogSize(1920f.dp, 1080f.dp, DialogSizeClass.CONTENT)
        assertNear(1632f, size.width.value)
        assertNear(918f, size.height.value)
    }

    @Test
    fun `FORM is 60 percent of a 1080p screen`() {
        val size = computeStandardDialogSize(1920f.dp, 1080f.dp, DialogSizeClass.FORM)
        assertNear(1152f, size.width.value)
        assertNear(648f, size.height.value)
    }

    @Test
    fun `neither class overflows the screen on 1080p and smaller displays`() {
        for ((w, h) in screens) {
            for (sizeClass in DialogSizeClass.entries) {
                val size = computeStandardDialogSize(w.dp, h.dp, sizeClass)
                assertTrue(
                    size.width.value >= DialogSizeSpec.MIN_WIDTH.value - 0.01f,
                    "${w}x$h ${sizeClass.name}: width below unified min",
                )
                assertTrue(
                    size.height.value >= DialogSizeSpec.MIN_HEIGHT.value - 0.01f,
                    "${w}x$h ${sizeClass.name}: height below unified min",
                )
                // 初始尺寸硬上限：95% 屏幕。
                assertTrue(
                    size.width.value <= w * DialogSizeSpec.MAX_SCREEN_FRACTION + 0.01f,
                    "${w}x$h ${sizeClass.name}: width over 95% cap",
                )
                assertTrue(
                    size.height.value <= h * DialogSizeSpec.MAX_SCREEN_FRACTION + 0.01f,
                    "${w}x$h ${sizeClass.name}: height over 95% cap",
                )
                // 更强的不溢出保证：不超过屏幕本身。
                assertTrue(size.width.value <= w + 0.01f, "${w}x$h ${sizeClass.name}: width over screen")
                assertTrue(size.height.value <= h + 0.01f, "${w}x$h ${sizeClass.name}: height over screen")
            }
        }
    }

    @Test
    fun `absurdly tiny screens clamp to the unified minimum without throwing`() {
        val size = computeStandardDialogSize(320f.dp, 240f.dp, DialogSizeClass.CONTENT)
        assertEquals(DialogSizeSpec.MIN_WIDTH.value, size.width.value)
        assertEquals(DialogSizeSpec.MIN_HEIGHT.value, size.height.value)
    }

    /**
     * 内容区宽度曲线 `min(W, max(0.8W, 900dp))`：与 `fillMaxWidth(0.8f).widthIn(min=900dp)`
     * 等价，但 W < 900dp 时退回 W，不会撑出窗口被裁掉右侧。1125dp 是连续点。
     */
    @Test
    fun `content width is 80 percent with a 900dp floor and never exceeds W`() {
        // 小窗口：全宽，不被 0.8W 压窄
        assertEquals(800f, computeContentWidth(800f.dp).value, 0.01f)
        assertEquals(900f, computeContentWidth(900f.dp).value, 0.01f)
        // 中窗口：900dp 保底
        assertEquals(900f, computeContentWidth(1000f.dp).value, 0.01f)
        // 连续点：0.8 × 1125 = 900，两侧无跳变
        assertEquals(900f, computeContentWidth(1125f.dp).value, 0.01f)
        assertNear(900.8f, computeContentWidth(1126f.dp).value)
        // 大窗口：80% 跟随窗口宽度
        assertEquals(1280f, computeContentWidth(1600f.dp).value, 0.01f)
        assertEquals(1520f, computeContentWidth(1900f.dp).value, 0.01f)
        assertTrue(computeContentWidth(1900f.dp) > computeContentWidth(1600f.dp))
        // 任何输入都夹在 [min(900, W), W] 内，绝不超出可用宽度
        for (w in listOf(320f, 480f, 800f, 900f, 1125f, 1600f, 1920f, 3840f)) {
            val cw = computeContentWidth(w.dp).value
            assertTrue(cw <= w + 0.01f, "W=$w 内容宽 $cw 超出可用宽度")
        }
    }

    /**
     * 窗口最小宽度：900dp 但不超过屏幕 95%，保证拖拽缩放时 900dp 约束有效
     * 且小屏上窗口不撑出屏幕。
     */
    @Test
    fun `window min width is 900dp capped at 95 percent of the screen`() {
        assertEquals(900f, computeWindowMinWidth(1920f.dp).value, 0.01f)
        assertEquals(900f, computeWindowMinWidth(1366f.dp).value, 0.01f)
        assertEquals(900f, computeWindowMinWidth(1024f.dp).value, 0.01f)
        // 小屏：95% 屏封顶，窗口不会撑出屏幕
        assertNear(760f, computeWindowMinWidth(800f.dp).value)
        assertNear(304f, computeWindowMinWidth(320f.dp).value)
        for (screen in listOf(320f, 800f, 1024f, 1920f, 3840f)) {
            val min = computeWindowMinWidth(screen.dp).value
            assertTrue(min <= screen * DialogSizeSpec.MAX_SCREEN_FRACTION + 0.01f,
                "屏宽 $screen 的最小窗口 $min 超出 95% 屏")
        }
    }

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 0.01f, "expected $expected but was $actual")
    }
}
