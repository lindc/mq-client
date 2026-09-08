package com.mqclient.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * The H/S/V slider trio for free-form color picking.
 *
 * [expanded] is owned by the caller (the "更多颜色..." button in
 * [ColorSeedSection]) so the palette grid and the advanced controls are one
 * coherent disclosure rather than two separate toggles. When collapsed this
 * composable renders nothing.
 *
 * [onChange] fires on every slider move; parents debounce / batch via
 * [remember]-based draft state.
 */
@Composable
internal fun HsvColorPicker(
    initial: Color,
    expanded: Boolean,
    onChange: (Color) -> Unit,
) {
    var h by remember { mutableStateOf(rgbToHue(initial)) }
    var s by remember { mutableStateOf(rgbToSaturation(initial)) }
    var v by remember { mutableStateOf(rgbToValue(initial)) }

    // Re-emit on any slider move. Push the current h/s/v back to the caller.
    fun emit() {
        onChange(hsvToColor(h, s, v))
    }

    if (!expanded) return

    Column(modifier = Modifier.fillMaxWidth()) {
        SliderRow("色相 (H)", h, 0f, 360f) {
            h = it
            emit()
        }
        SliderRow("饱和度 (S)", s, 0f, 1f) {
            s = it
            emit()
        }
        SliderRow("明度 (V)", v, 0f, 1f) {
            v = it
            emit()
        }
    }
}

/** Single labeled slider row used by the three HSV channels. */
@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                "%.2f".format(value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
        )
    }
}

/* ----------------------------- HSV ↔ RGB ----------------------------- */

/** Compose's [Color] stores 0-1 floats; we extract hue/sat/val from RGB. */
internal fun rgbToHue(color: Color): Float {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    if (delta == 0f) return 0f
    return when (max) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
}

internal fun rgbToSaturation(color: Color): Float {
    val max = maxOf(color.red, color.green, color.blue)
    val min = minOf(color.red, color.green, color.blue)
    return if (max == 0f) 0f else (max - min) / max
}

internal fun rgbToValue(color: Color): Float = maxOf(color.red, color.green, color.blue)

/** Convert HSV back to a Compose [Color] (alpha pinned at 1.0 — seed is always opaque). */
internal fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r1 + m, g1 + m, b1 + m)
}

/** Compose's `Color.value` is a ULong; top 32 bits hold RGBA, low 32 hold the
 *  float-channel bits we don't need for opaque seed storage. */
internal fun formatHex(color: Color): String =
    "#%08X".format((color.value shr 32).toLong() and 0xFFFFFFFFL)