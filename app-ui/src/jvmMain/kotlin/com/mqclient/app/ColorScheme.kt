package com.mqclient.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamicColorScheme
import androidx.compose.material3.ColorScheme

@Composable
fun generateDynamicColorScheme(isDark: Boolean): ColorScheme {
    // 选择一个种子颜色，也可以让用户自定义
    val seedColor = Color(0xFF648042)

    // 生成动态 ColorScheme
    return dynamicColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        isAmoled = false
        // 可选参数：调整风格、对比度等
        // style = PaletteStyle.TonalSpot,
        // contrastLevel = 0.0,
    )
}