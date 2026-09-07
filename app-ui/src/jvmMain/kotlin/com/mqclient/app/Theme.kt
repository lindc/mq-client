// Generated using MaterialKolor Builder version 1.3.0 (103)
// https://materialkolor.com/?color_seed=FF68A500&dark_mode=false&color_spec=SPEC_2025&package_name=com.mqclient.app

package com.mqclient.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicMaterialThemeState
import mq_client.app_ui.generated.resources.Res
import mq_client.app_ui.generated.resources.lxgw_wen_kai_mono_regular
import org.jetbrains.compose.resources.Font

/**
 * The app's type scale: Material's default sizes/weights, rendered entirely in
 * LXGW WenKai Mono Regular. One font covers Latin and CJK both, so mixing a
 * Latin face with a CJK face (which reads heavier at the same size) can't make
 * the text look patchy.
 *
 * [Font] reads the resource during composition, so this has to be built inside
 * a composable.
 */
@Composable
private fun appTypography(): Typography {
    val family = FontFamily(Font(Res.font.lxgw_wen_kai_mono_regular, FontWeight.Normal))
    return Typography().withFontFamily(family)
}

/**
 * Applies [family] to every style in the Material type scale. The individual
 * styles carry their own size/weight; only the family is overridden.
 *
 * [FontSynthesis.None] matters here: the family ships a single Regular weight,
 * so any bolder request (a `FontWeight.SemiBold` label, say) would otherwise be
 * answered by Skia smearing the Regular outline — a fake bold that reads as a
 * different, blurrier typeface rather than as emphasis.
 */
private fun Typography.withFontFamily(family: FontFamily): Typography =
    copy(
        displayLarge = displayLarge.with(family),
        displayMedium = displayMedium.with(family),
        displaySmall = displaySmall.with(family),
        headlineLarge = headlineLarge.with(family),
        headlineMedium = headlineMedium.with(family),
        headlineSmall = headlineSmall.with(family),
        titleLarge = titleLarge.with(family),
        titleMedium = titleMedium.with(family),
        titleSmall = titleSmall.with(family),
        bodyLarge = bodyLarge.with(family),
        bodyMedium = bodyMedium.with(family),
        bodySmall = bodySmall.with(family),
        labelLarge = labelLarge.with(family),
        labelMedium = labelMedium.with(family),
        labelSmall = labelSmall.with(family),
    )

private fun TextStyle.with(family: FontFamily): TextStyle =
    copy(fontFamily = family, fontSynthesis = FontSynthesis.None)

@Composable
fun AppTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dynamicThemeState =
        rememberDynamicMaterialThemeState(
            isDark = isDarkTheme,
            style = PaletteStyle.TonalSpot,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            seedColor = SeedColor,
        )

    DynamicMaterialTheme(
        state = dynamicThemeState,
        animate = true,
        typography = appTypography(),
        content = content,
    )
}
