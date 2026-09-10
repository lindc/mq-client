package com.mqclient.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mqclient.uicommon.i18n.stringResource
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

    // 外部换了颜色（例如点了预设色板）时，把滑块同步过去。
    //
    // 比较用打包后的 ARGB（8 位量化）而不是 Color 本身：自己拖动时父层存回来的正是
    // `hsvToColor(...)` 的量化结果，比 Color 的话 hsv→rgb→hsv 的往返误差会让这个 effect
    // 每次都把滑块「掰」回量化值，拖动手感发飘。
    LaunchedEffect(initial) {
        if (hsvToColor(h, s, v).toPackedArgb() != initial.toPackedArgb()) {
            h = rgbToHue(initial)
            s = rgbToSaturation(initial)
            v = rgbToValue(initial)
        }
    }

    // Re-emit on any slider move. Push the current h/s/v back to the caller.
    fun emit() {
        onChange(hsvToColor(h, s, v))
    }

    if (!expanded) return

    Column(modifier = Modifier.fillMaxWidth()) {
        SliderRow(stringResource("hsv_hue"), h, 0f, 360f) {
            h = it
            emit()
        }
        SliderRow(stringResource("hsv_saturation"), s, 0f, 1f) {
            s = it
            emit()
        }
        SliderRow(stringResource("hsv_value"), v, 0f, 1f) {
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

/**
 * 把 [Color] 打包成 `ThemeConfig.seedColor` 存的那个 0xAARRGGBB Long —— 也就是
 * `Color(argb)` 能原样读回来的表示。
 *
 * 不能拿 `Color.value` 做位移取色：它是 ULong 位域（低 6 位色空间 id、10 位 alpha、
 * 再往上三个 16 位通道），`value shr 32` 拿到的是绿/蓝通道的位，既不是 ARGB 也不是
 * 0，存进去就成了一个跟实际颜色无关的值。走 [toArgb] 做一次正规转换。
 */
internal fun Color.toPackedArgb(): Long = toArgb().toLong() and 0xFFFFFFFFL

/** `#AARRGGBB`（界面预览 / 日志用）。 */
internal fun formatHex(color: Color): String =
    "#%08X".format(color.toPackedArgb())