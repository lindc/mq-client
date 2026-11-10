package com.mqclient.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import com.mqclient.core.CoreModule
import com.mqclient.uicommon.i18n.stringResource
import mq_client.app_ui.generated.resources.Res

/** Path to the bundled preset-palette manifest inside composeResources. */
private const val PalettePath = "files/palette/palette.txt"

/**
 * One swatch in the preset palette, parsed from `files/palette/palette.txt`.
 *
 * [argb] is a packed 0xAARRGGBB long — the same representation
 * [com.mqclient.core.ThemeConfig.seedColor] uses, so picking a swatch is a
 * direct assignment with no conversion.
 */
internal data class PresetColor(
    val name: String,
    val argb: Long,
    val group: String,
) {
    val color: Color get() = Color(argb)
}

/**
 * Loads the palette from the packaged resource and keeps it in a module-level
 * cache so the file is read once per process rather than on every
 * recomposition of the settings dialog.
 */
private var cachedPalette: List<PresetColor>? = null

/** Palette for the appearance tab; empty until the resource finishes loading. */
@Composable
internal fun rememberPresetPalette(): List<PresetColor> {
    val palette by produceState(initialValue = cachedPalette ?: emptyList()) {
        val loaded = cachedPalette ?: loadPalette().also { cachedPalette = it }
        value = loaded
    }
    return palette
}

private suspend fun loadPalette(): List<PresetColor> =
    try {
        parsePalette(Res.readBytes(PalettePath).decodeToString())
    } catch (error: Exception) {
        // 预设色块整个消失不是小事情 —— 用户会以为主题选色坏了，要说清楚原因。
        CoreModule.reportError(
            message = stringResource("app_palette_read_failed"),
            source = "app-ui",
            error = error,
        )
        emptyList()
    }

/**
 * Parses tab-separated `name \t #RRGGBB \t group` lines. Lines that don't
 * split into exactly three fields are dropped, which is what makes the
 * header comments in the file harmless.
 */
private fun parsePalette(raw: String): List<PresetColor> =
    raw.lineSequence()
        .map { it.split('\t') }
        .filter { it.size == 3 }
        .mapNotNull { (name, hex, group) ->
            val argb = parseHexArgb(hex) ?: return@mapNotNull null
            val cleanName = name.trim()
            if (cleanName.isEmpty()) null else PresetColor(cleanName, argb, group.trim())
        }
        .toList()
