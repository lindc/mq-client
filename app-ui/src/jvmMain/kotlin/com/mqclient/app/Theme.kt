// Generated using MaterialKolor Builder version 1.3.0 (103)
// https://materialkolor.com/?color_seed=FF68A500&dark_mode=false&color_spec=SPEC_2025&package_name=com.mqclient.app

package com.mqclient.app

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.unit.sp
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicMaterialThemeState
import com.mqclient.app.fonts.rememberAppFontFamily
import com.mqclient.core.ThemeConfig

/**
 * The app's type scale: Material's default weights, rendered in the family
 * resolved from [ThemeConfig.fontFamily] (bundled LXGW by default, a system
 * font when the settings dialog persists one — see [rememberAppFontFamily]),
 * scaled uniformly so that [ThemeConfig.fontSize] acts as the body size.
 *
 * [Font] reads the resource during composition, so this has to be built inside
 * a composable.
 */
@Composable
private fun appTypography(theme: ThemeConfig): Typography {
    val family = rememberAppFontFamily(theme.fontFamily)
    // 14 = Material bodyMedium 默认 = ThemeConfig.fontSize 的默认值，作为缩放基准。
    // Typography 重建会让 MaterialTheme.typography 换一个身份，把整棵读样式的
    // 界面推着重算 —— key 只放 family 身份和 scale，平时重组不重建。
    val scale = theme.fontSize / 14f
    return remember(family, scale) { Typography().withFontFamily(family, scale) }
}

/**
 * Applies [family] and the size [scale] to every style in the Material type
 * scale: family + font size + line height (scaled in lockstep, otherwise the
 * larger sizes would crowd their lines).
 *
 * [FontSynthesis.None] matters here: the bundled family ships a single Regular
 * weight, so any bolder request (a `FontWeight.SemiBold` label, say) would
 * otherwise be answered by Skia smearing the Regular outline — a fake bold
 * that reads as a different, blurrier typeface rather than as emphasis.
 */
// internal（而非 private）：TypographyScaleTest 直接覆盖这个纯函数。
internal fun Typography.withFontFamily(family: FontFamily, scale: Float): Typography =
    copy(
        displayLarge = displayLarge.with(family, scale),
        displayMedium = displayMedium.with(family, scale),
        displaySmall = displaySmall.with(family, scale),
        headlineLarge = headlineLarge.with(family, scale),
        headlineMedium = headlineMedium.with(family, scale),
        headlineSmall = headlineSmall.with(family, scale),
        titleLarge = titleLarge.with(family, scale),
        titleMedium = titleMedium.with(family, scale),
        titleSmall = titleSmall.with(family, scale),
        bodyLarge = bodyLarge.with(family, scale),
        bodyMedium = bodyMedium.with(family, scale),
        bodySmall = bodySmall.with(family, scale),
        labelLarge = labelLarge.with(family, scale),
        labelMedium = labelMedium.with(family, scale),
        labelSmall = labelSmall.with(family, scale),
    )

private fun TextStyle.with(family: FontFamily, scale: Float): TextStyle = copy(
    fontFamily = family,
    fontSynthesis = FontSynthesis.None,
    fontSize = (fontSize.value * scale).sp,
    lineHeight = (lineHeight.value * scale).sp,
)

/**
 * Top-level theme wrapper. [theme] controls the seed color and the type scale
 * (font family + base size, resolved by [rememberAppFontFamily]); [isDarkTheme]
 * is the resolved light/dark choice — pass the result of
 * [com.mqclient.core.ThemeMode] + `isSystemInDarkTheme()` from the call site
 * so the title bar ([NucleusDecoratedWindowTheme]) and content can share
 * one resolved value.
 *
 * Everything else uses Material defaults via MaterialKolor's dynamic palette.
 */
@Composable
fun AppTheme(
    theme: ThemeConfig = ThemeConfig(),
    isDarkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val dynamicThemeState =
        rememberDynamicMaterialThemeState(
            isDark = isDarkTheme,
            style = PaletteStyle.TonalSpot,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            seedColor = Color(theme.seedColor),
        )

    DynamicMaterialTheme(
        state = dynamicThemeState,
        animate = true,
        typography = appTypography(theme),
        content = content,
    )
}