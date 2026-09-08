package com.mqclient.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
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
    } catch (t: Exception) {
        System.err.println("[PresetPalette] failed to read palette: $t")
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
            val argb = parseArgb(hex) ?: return@mapNotNull null
            val cleanName = name.trim()
            if (cleanName.isEmpty()) null else PresetColor(cleanName, argb, group.trim())
        }
        .toList()

/** Tolerates `#`, `0x`, and a missing alpha channel (defaults to opaque). */
private fun parseArgb(hex: String): Long? {
    val cleaned = hex.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    val parsed = cleaned.toLongOrNull(16) ?: return null
    return if (cleaned.length == 6) (0xFF000000L or parsed) else parsed
}