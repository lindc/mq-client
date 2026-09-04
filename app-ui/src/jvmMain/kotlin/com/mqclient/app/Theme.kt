// Generated using MaterialKolor Builder version 1.3.0 (103)
// https://materialkolor.com/?color_seed=FF68A500&dark_mode=false&color_spec=SPEC_2025&package_name=com.mqclient.app

package com.mqclient.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicMaterialThemeState

@Composable
fun AppTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dynamicThemeState = rememberDynamicMaterialThemeState(
        isDark = isDarkTheme,
        style = PaletteStyle.TonalSpot,
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
        seedColor = SeedColor,
    )

    DynamicMaterialTheme(
        state = dynamicThemeState,
        animate = true,
        content = content,
    )
}
