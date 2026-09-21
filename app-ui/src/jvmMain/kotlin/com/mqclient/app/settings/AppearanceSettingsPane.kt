package com.mqclient.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.app.fonts.FontEntry
import com.mqclient.app.fonts.SystemFonts
import com.mqclient.app.fonts.rememberAppFontFamily
import com.mqclient.core.DEFAULT_SEED_COLOR
import com.mqclient.core.FONT_LXGW_WEN_KAI_MONO
import com.mqclient.core.FONT_SYSTEM_MONO
import com.mqclient.core.FONT_SYSTEM_SANS
import com.mqclient.core.FONT_SYSTEM_SERIF
import com.mqclient.core.ThemeConfig
import com.mqclient.core.ThemeMode
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.SettingsDropdownRow
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mq_client.app_ui.generated.resources.Res
import mq_client.app_ui.generated.resources.lxgw_wen_kai_mono_regular
import androidx.compose.ui.text.platform.Font as PlatformFont
import org.jetbrains.compose.resources.Font as ResourceFont

/**
 * Display-only labels for font ids that older builds could persist into
 * [ThemeConfig.fontFamily] (System Sans/Serif/Mono). They are no longer
 * offered in the dropdown, but a stale config.toml may still carry one, so
 * the collapsed field shows its old label instead of a raw id. Persisted
 * values are never rewritten here.
 */
private val LegacyFontLabels: Map<String, String> = mapOf(
    FONT_SYSTEM_SANS to "System Sans",
    FONT_SYSTEM_SERIF to "System Serif",
    FONT_SYSTEM_MONO to "System Mono",
)

/** String-table keys for [ThemeMode] options, resolved at render time. */
private val ThemeModeChoices: List<Pair<ThemeMode, String>> = listOf(
    ThemeMode.SYSTEM to "theme_mode_system",
    ThemeMode.LIGHT to "theme_mode_light",
    ThemeMode.DARK to "theme_mode_dark",
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
        Text(stringResource("settings_tab_appearance"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        ThemeModeRow(value.themeMode) { onChange(value.copy(themeMode = it)) }
        Spacer(Modifier.height(20.dp))

        ColorSeedSection(value.seedColor) { newColor ->
            // seedColor 存的是 0xAARRGGBB（读取方 `Color(seedColor)` 就按这个解），
            // 所以打包也走同一个 helper —— 别再自己拆 `Color.value` 的位域。
            onChange(value.copy(seedColor = newColor.toPackedArgb()))
        }
        Spacer(Modifier.height(20.dp))

        FontRow(value.fontFamily) { onChange(value.copy(fontFamily = it)) }
        Spacer(Modifier.height(20.dp))

        NumberRow(
            label = stringResource("font_size_label"),
            suffix = stringResource("unit_px"),
            value = value.fontSize,
            min = 12,
            max = 18,
            onChange = { onChange(value.copy(fontSize = it)) },
        )
        Spacer(Modifier.height(8.dp))
        FontPreviewRow(fontSize = value.fontSize, fontFamilyId = value.fontFamily)
    }
}

/**
 * Light/dark/system dropdown for [ThemeConfig.themeMode]. Reuses the same
 * Surface+chevron primitive as [LanguageRow] / [FontRow] for visual consistency.
 */
@Composable
private fun ThemeModeRow(current: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val display = ThemeModeChoices.firstOrNull { it.first == current }?.second
        ?.let(::stringResource) ?: current.name
    SettingsDropdownRow(
        label = stringResource("theme_label"),
        currentLabel = display,
        options = ThemeModeChoices,
        optionLabel = { (_, key) -> stringResource(key) },
        onPick = { (mode, _) -> onChange(mode) },
    )
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
            text = stringResource("color_seed_label"),
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
                text = (if (advancedExpanded) "▴ " else "▾ ") +
                    stringResource(if (advancedExpanded) "color_less" else "color_more"),
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
            // Hex input — typed escape hatch. 输入期间 text 是唯一真相：合法前缀一出现
            // 就会被重置成 8 位规范形式，正在敲的字符会被冲掉（parseHex 只认 6 / 8 位）。
            var hexText by remember { mutableStateOf(formatHex(currentColor)) }
            var hexFocus by remember { mutableStateOf(false) }
            LaunchedEffect(current, hexFocus) {
                if (!hexFocus) hexText = formatHex(currentColor)
            }
            OutlinedTextField(
                value = hexText,
                onValueChange = { input ->
                    hexText = input
                    // 合法 hex 仍然实时回写（HSV 滑杆跟着动），但不再回灌到文本框。
                    parseHexArgb(input)?.let { onChange(Color(it)) }
                },
                modifier = Modifier
                    .width(220.dp)
                    .onFocusChanged { hexFocus = it.isFocused },
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
                text = stringResource("color_current_prefix") + " ${formatHex(currentColor)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            PressableButton(
                onClick = { onChange(Color(DEFAULT_SEED_COLOR)) },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("color_reset")) }
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
            text = stringResource("palette_not_loaded"),
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

/**
 * Font dropdown, flat layout with no grouping:
 *   1. “霞鹜文楷 Mono” — the bundled face — always pinned as the first entry;
 *   2. every font family the OS reports, enumerated on a background thread
 *      ([SystemFonts]) — including families whose backing file can't be
 *      resolved (later faces of a .ttc collection); those render disabled.
 *
 * A search field is pinned above the list and filters the system fonts in
 * real time (the bundled entry stays pinned regardless). Every entry renders
 * its own label in the font it represents (self-preview), so the user sees
 * the actual look before picking.
 *
 * Selecting an entry persists its id — [FONT_LXGW_WEN_KAI_MONO] for the
 * bundled font, the raw family name otherwise (save logic unchanged: a plain
 * string in config.toml). The typography layer does not consume
 * [ThemeConfig.fontFamily] yet; that wiring stays deferred.
 */
@Composable
private fun FontRow(
    current: String,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // Enumerating + scanning font files takes a few hundred ms the first time;
    // run it off the UI thread. SystemFonts caches internally, so this cost is
    // paid once per process.
    val systemEntries by produceState<List<FontEntry>?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { SystemFonts.entries() }
    }
    val filteredSystem = remember(systemEntries, query) {
        val entries = systemEntries ?: return@remember null
        val q = query.trim()
        if (q.isEmpty()) entries else entries.filter { it.family.contains(q, ignoreCase = true) }
    }
    val searching = query.isNotBlank()
    // Bundled LXGW face for the pinned entry. ResourceFont is @Composable
    // (async resource read), so it can't live inside remember's lambda — the
    // wrapper is thin, recreating it per composition is fine.
    val bundledFamily = FontFamily(ResourceFont(Res.font.lxgw_wen_kai_mono_regular, FontWeight.Normal))

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource("font_label"),
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
            // Bundled font shows its Chinese label; anything else shows its own
            // name — a persisted system family is self-describing, and legacy
            // system_sans/serif/mono ids map to their old display labels.
            val display = when (current) {
                FONT_LXGW_WEN_KAI_MONO -> stringResource("font_lxgw_label")
                else -> LegacyFontLabels[current] ?: current
            }
            Text(
                text = display,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text("▼", style = MaterialTheme.typography.bodyMedium)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                query = ""
            },
        ) {
            // Menu 内容列固定宽度 —— M3 DropdownMenu 外层用 width(IntrinsicSize.Max)
            // 包裹, 会对子级做 intrinsic 测量; 内部 LazyColumn 需在固定尺寸处短路。
            Column(modifier = Modifier.width(320.dp).padding(vertical = 4.dp)) {
                // Search field — pinned above the list, filters in real time.
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource("font_search_placeholder")) },
                )
                // 枚举完成前 filteredSystem 为空列表占位, 加载态由下方分支单独处理。
                val shownSystem = filteredSystem ?: emptyList<FontEntry>()
                // LazyColumn(SubcomposeLayout) 不支持 intrinsic 测量, 而 M3 DropdownMenu
                // 的内容列用 width(IntrinsicSize.Max), 会逐层查询子级 intrinsic 尺寸 ——
                // 因此 LazyColumn 必须有显式宽高(查询在 SizeNode 处快速返回, 不下钻)。
                val itemRows = when {
                    systemEntries == null || shownSystem.isEmpty() -> 1
                    else -> shownSystem.size
                }
                // DropdownMenuItem 最小高 48dp; 总行数 = 置顶的 LXGW + 列表行, 按行数
                // 估算高度 —— 不足一屏时菜单自动变矮, 超过时封顶滚动。
                val listHeight = ((itemRows + 1) * 48 + 8).coerceAtMost(340).dp
                LazyColumn(modifier = Modifier.width(320.dp).height(listHeight)) {
                    // The bundled font stays pinned as the first option even
                    // while a search filter is active.
                    item(key = "lxgw_wen_kai_mono") {
                        FontDropDownItem(
                            label = stringResource("font_lxgw_label"),
                            previewFamily = bundledFamily,
                            selected = current == FONT_LXGW_WEN_KAI_MONO,
                            onClick = {
                                expanded = false
                                query = ""
                                onChange(FONT_LXGW_WEN_KAI_MONO)
                            },
                        )
                    }
                    when {
                        systemEntries == null -> item(key = "loading") {
                            FontDropDownItem(label = stringResource("font_enum_in_progress"), enabled = false) {}
                        }
                        shownSystem.isEmpty() -> item(key = "empty") {
                            FontDropDownItem(
                                label = if (searching) stringResource("font_no_match")
                                else stringResource("font_not_found"),
                                enabled = false,
                            ) {}
                        }
                        else -> items(shownSystem, key = { it.family }) { entry ->
                            val file = entry.file
                            // SystemFonts picks the Regular file per family;
                            // typefaces load lazily/async inside the platform font loader.
                            val previewFamily = remember(entry.family) {
                                if (file != null) FontFamily(PlatformFont(file)) else null
                            }
                            FontDropDownItem(
                                label = entry.family,
                                previewFamily = previewFamily,
                                selected = entry.family == current,
                                enabled = file != null,
                                onClick = {
                                    expanded = false
                                    query = ""
                                    onChange(entry.family)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One font row in the dropdown. The label is rendered with [previewFamily]
 * when available so each entry shows itself; entries without a loadable file
 * (or the transient loading/empty states) render disabled in the default face.
 */
@Composable
private fun FontDropDownItem(
    label: String,
    previewFamily: FontFamily? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    androidx.compose.material3.DropdownMenuItem(
        modifier = Modifier.fillMaxWidth(),
        text = {
            Text(
                text = label,
                fontFamily = previewFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        enabled = enabled,
        trailingIcon = { if (selected) CheckMark() },
        onClick = onClick,
    )
}

/** Small primary-colored check used to mark the currently selected dropdown entry. */
@Composable
private fun CheckMark() {
    Text("✓", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

/**
 * Live font preview row. Renders a sample string with the family resolved
 * from [fontFamilyId] (via [rememberAppFontFamily] — the same resolver the
 * app typography uses), at the chosen size. The preview therefore shows the
 * real rendering, including the fallback a system font degrades to when it
 * can't be loaded.
 */
@Composable
private fun FontPreviewRow(fontSize: Int, fontFamilyId: String) {
    val family = rememberAppFontFamily(fontFamilyId)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource("font_preview_label"),
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

