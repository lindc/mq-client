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

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 0.01f, "expected $expected but was $actual")
    }
}
