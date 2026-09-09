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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.app.fonts.FontEntry
import com.mqclient.app.fonts.SystemFonts
import com.mqclient.core.DEFAULT_SEED_COLOR
import com.mqclient.core.FONT_LXGW_WEN_KAI_MONO
import com.mqclient.core.FONT_SYSTEM_MONO
import com.mqclient.core.FONT_SYSTEM_SANS
import com.mqclient.core.FONT_SYSTEM_SERIF
import com.mqclient.core.ThemeConfig
import com.mqclient.core.ThemeMode
import com.mqclient.uicommon.PressableButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mq_client.app_ui.generated.resources.Res
import mq_client.app_ui.generated.resources.lxgw_wen_kai_mono_regular
import androidx.compose.ui.text.platform.Font as PlatformFont
import org.jetbrains.compose.resources.Font as ResourceFont

/** Display label of the bundled font — the only built-in entry, pinned first in the dropdown. */
private const val LXGW_LABEL = "霞鹜文楷 Mono"

/**
 * Display-only labels for font ids that older builds could persist into
 * [ThemeConfig.fontFamily] (System Sans/Serif/Mono). They are no longer
 * offered in the dropdown, but a stale config.json may still carry one, so
 * the collapsed field shows its old label instead of a raw id. Persisted
 * values are never rewritten here.
 */
private val LegacyFontLabels: Map<String, String> = mapOf(
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
 * string in config.json). The typography layer does not consume
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
            // Bundled font shows its Chinese label; anything else shows its own
            // name — a persisted system family is self-describing, and legacy
            // system_sans/serif/mono ids map to their old display labels.
            val display = when (current) {
                FONT_LXGW_WEN_KAI_MONO -> LXGW_LABEL
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
                    placeholder = { Text("搜索字体…") },
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
                            label = LXGW_LABEL,
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
                            FontDropDownItem(label = "正在枚举系统字体…", enabled = false) {}
                        }
                        shownSystem.isEmpty() -> item(key = "empty") {
                            FontDropDownItem(
                                label = if (searching) "没有匹配的字体" else "未发现系统字体",
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
 * Live font preview row. Renders a sample string with the **bundled**
 * LXGW face at the chosen size; the `fontFamily` selection is not wired
 * here (nor in Theme.kt) on purpose — the dropdown already persists the
 * choice (built-in ids or real system family names via [SystemFonts]),
 * and rendering will start consuming it once the message-bus rework
 * lands, so preview and app typography switch over in one step.
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