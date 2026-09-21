package com.mqclient.app

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * 覆盖 [Typography.withFontFamily]（Theme.kt 的 type scale 改写）：15 个样式
 * 全部落到指定 family、禁合成粗体、fontSize/lineHeight 以 14 为基准等比缩放。
 * 这是纯函数 —— 字体解析（rememberAppFontFamily）和组合接线不在覆盖范围。
 */
class TypographyScaleTest {

    /** material3 的 Typography 没有 styles 集合，15 个样式显式枚举。 */
    private fun Typography.allStyles(): List<Pair<String, TextStyle>> = listOf(
        "displayLarge" to displayLarge,
        "displayMedium" to displayMedium,
        "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge,
        "headlineMedium" to headlineMedium,
        "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge,
        "titleMedium" to titleMedium,
        "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge,
        "bodyMedium" to bodyMedium,
        "bodySmall" to bodySmall,
        "labelLarge" to labelLarge,
        "labelMedium" to labelMedium,
        "labelSmall" to labelSmall,
    )

    @Test
    fun `every style lands on the requested family with synthesis disabled`() {
        val scaled = Typography().withFontFamily(FontFamily.Monospace, scale = 18f / 14f)
        for ((name, style) in scaled.allStyles()) {
            assertSame(FontFamily.Monospace, style.fontFamily, "$name 的 fontFamily 应被覆盖")
            assertEquals(FontSynthesis.None, style.fontSynthesis, "$name 必须禁合成粗体")
        }
        assertEquals(15, scaled.allStyles().size, "Material 全部 15 个样式都要覆盖到")
    }

    @Test
    fun `fontSize and lineHeight scale uniformly from the 14px baseline`() {
        val defaults = Typography()
        val scale = 18f / 14f
        val scaled = defaults.withFontFamily(FontFamily.Monospace, scale)
        for ((name, style) in scaled.allStyles()) {
            val default = defaults.allStyles().first { it.first == name }.second
            assertEquals(
                default.fontSize.value * scale,
                style.fontSize.value,
                "$name 的 fontSize 应等比缩放",
            )
            assertEquals(
                default.lineHeight.value * scale,
                style.lineHeight.value,
                "$name 的 lineHeight 应同步缩放（否则大字号挤行）",
            )
        }
    }

    @Test
    fun `default size is an identity transform`() {
        val defaults = Typography()
        val identity = defaults.withFontFamily(FontFamily.Monospace, scale = 1f)
        for ((name, style) in identity.allStyles()) {
            val default = defaults.allStyles().first { it.first == name }.second
            assertEquals(default.fontSize, style.fontSize, "$name 在 scale=1 时字号不变")
            assertEquals(default.lineHeight, style.lineHeight, "$name 在 scale=1 时行高不变")
        }
        // fontSize=14（配置默认值）→ bodyMedium 正好落在 14.sp。
        assertEquals(14.sp, identity.bodyMedium.fontSize)
    }
}
