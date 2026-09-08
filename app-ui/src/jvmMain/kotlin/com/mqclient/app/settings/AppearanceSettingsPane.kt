package com.mqclient.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.core.DEFAULT_SEED_COLOR
import com.mqclient.core.FONT_LXGW_WEN_KAI_MONO
import com.mqclient.core.FONT_SYSTEM_MONO
import com.mqclient.core.FONT_SYSTEM_SANS
import com.mqclient.core.FONT_SYSTEM_SERIF
import com.mqclient.core.ThemeConfig
import com.mqclient.core.ThemeMode
import com.mqclient.uicommon.PressableButton
import mq_client.app_ui.generated.resources.Res
import mq_client.app_ui.generated.resources.lxgw_wen_kai_mono_regular
import org.jetbrains.compose.resources.Font as ResourceFont

/** Static font choices the user can pick; matches the ids persisted in [ThemeConfig.fontFamily]. */
private val FontChoices: List<Pair<String, String>> = listOf(
    FONT_LXGW_WEN_KAI_MONO to "霞鹜文楷 Mono",
    FONT_SYSTEM_SANS to "System Sans",
    FONT_SYSTEM_SERIF to "System Serif",
    FONT_SYSTEM_MONO to "System Mono",
)

/** Display labels for [ThemeMode] in the dropdown. */
private val ThemeModeChoices: List<Pair<ThemeMode, String>> = listOf(
    ThemeMode.SYSTEM to "跟随系统",
    ThemeMode.LIGHT to "浅色",
    ThemeMode.DARK to "深色",
)

/**
 * Appearance tab — theme mode picker, seed color (preset palette + an
 * optional HSV picker behind "更多颜色..."), font family, and font size
 * with a live preview.
 */
@Composable
internal fun AppearanceSettingsPane(
    value: ThemeConfig,
    onChange: (ThemeConfig) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("外观", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        ThemeModeRow(value.themeMode) { onChange(value.copy(themeMode = it)) }
        Spacer(Modifier.height(20.dp))

        ColorSeedSection(value.seedColor) { newColor ->
            // Color.value is ULong with RGBA packed in the high 32 bits;
            // widen to Long so we can mask with `and 0xFFFFFFFFL` (Long).
            onChange(value.copy(seedColor = (newColor.value shr 32).toLong() and 0xFFFFFFFFL))
        }
        Spacer(Modifier.height(20.dp))

        FontRow(value.fontFamily) { onChange(value.copy(fontFamily = it)) }
        Spacer(Modifier.height(20.dp))

        NumberRow(
            label = "字号",
            suffix = "px",
            value = value.fontSize,
            min = 12,
            max = 18,
            onChange = { onChange(value.copy(fontSize = it)) },
        )
        Spacer(Modifier.height(8.dp))
        FontPreviewRow(fontSize = value.fontSize)
    }
}

/**
 * Light/dark/system dropdown for [ThemeConfig.themeMode]. Reuses the same
 * Surface+chevron primitive as [LanguageRow] / [FontRow] for visual consistency.
 */
@Composable
private fun ThemeModeRow(current: ThemeMode, onChange: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val display = ThemeModeChoices.firstOrNull { it.first == current }?.second ?: current.name
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "主题",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(display, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("▼", style = MaterialTheme.typography.bodyMedium)
            }
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                ThemeModeChoices.forEach { (mode, label) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onChange(mode)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Seed-color section, laid out as a self-contained card:
 *
 *   1. the preset palette grid (loaded from `files/palette/palette.txt`)
 *   2. a "更多颜色..." disclosure that reveals the HSV sliders
 *   3. a footer row: current hex + a "恢复默认" button
 *
 * The palette is the primary path — it covers the common "pick a hue" case
 * without exposing three sliders the user has to understand.
 */
@Composable
private fun ColorSeedSection(
    current: Long,
    onChange: (Color) -> Unit,
) {
    val currentColor = Color(current)
    val palette = rememberPresetPalette()
    var advancedExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            text = "颜色种子",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        PresetPaletteGrid(
            palette = palette,
            currentArgb = current,
            onPick = { onChange(it) },
        )

        Spacer(Modifier.height(8.dp))

        // Disclosure for the free-form HSV sliders.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (advancedExpanded) "▴ 收起更多颜色" else "▾ 更多颜色...",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (advancedExpanded) {
            HsvColorPicker(
                initial = currentColor,
                expanded = true,
                onChange = onChange,
            )
            Spacer(Modifier.height(8.dp))
            // Hex input — typed escape hatch. Any valid hex resets the HSV
            // sliders (via re-emit through onChange).
            var hexText by remember(current) { mutableStateOf(formatHex(currentColor)) }
            OutlinedTextField(
                value = hexText,
                onValueChange = { input ->
                    hexText = input
                    parseHex(input)?.let(onChange)
                },
                modifier = Modifier.width(220.dp),
                singleLine = true,
                placeholder = { Text("#FF68A500") },
                label = { Text("Hex") },
            )
        }

        Spacer(Modifier.height(8.dp))

        // Footer: current value + reset.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(currentColor, RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "当前: ${formatHex(currentColor)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            PressableButton(
                onClick = { onChange(Color(DEFAULT_SEED_COLOR)) },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text("恢复默认") }
        }
    }
}

/**
 * Wrapping grid of preset swatches. The selected one gets a ring so the
 * user can see which entry produced the current seed.
 */
@Composable
private fun PresetPaletteGrid(
    palette: List<PresetColor>,
    currentArgb: Long,
    onPick: (Color) -> Unit,
) {
    if (palette.isEmpty()) {
        Text(
            text = "（色板配置未加载）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        palette.forEach { preset ->
            val selected = preset.argb == currentArgb
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(preset.color, RoundedCornerShape(6.dp))
                    .then(
                        if (selected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                        } else {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                        },
                    )
                    .clickable { onPick(preset.color) },
            )
        }
    }
}

@Composable
private fun FontRow(
    current: String,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "字体",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val display = FontChoices.firstOrNull { it.first == current }?.second ?: current
            Text(
                text = display,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text("▼", style = MaterialTheme.typography.bodyMedium)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            FontChoices.forEach { (id, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onChange(id)
                    },
                )
            }
        }
    }
}

/**
 * Live font preview row. Renders a sample string with the **bundled**
 * LXGW face at the chosen size; the `fontFamily` dropdown selection is
 * intentionally not wired because Theme.kt currently loads the bundled
 * font only (system ids are persisted for the day a Compose upgrade lifts
 * the limitation, but ignored at render time).
 */
@Composable
private fun FontPreviewRow(fontSize: Int) {
    // ResourceFont is @Composable (it reads async resources), so it can't
    // sit inside remember's @DisallowComposableCalls lambda. Just construct
    // FontFamily inline — it's a thin wrapper, cheap to recreate.
    val family = FontFamily(ResourceFont(Res.font.lxgw_wen_kai_mono_regular, FontWeight.Normal))
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "示例文字预览",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Aa 中文字体预览 123",
            fontFamily = family,
            fontSize = fontSize.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/* ---------- hex helpers (tolerate `#`, `0x`, missing alpha) ---------- */

private fun parseHex(input: String): Color? {
    val cleaned = input.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    val parsed = cleaned.toLongOrNull(16) ?: return null
    val argb = if (cleaned.length == 6) (0xFF000000L or parsed) else parsed
    return Color(argb.toInt())
}