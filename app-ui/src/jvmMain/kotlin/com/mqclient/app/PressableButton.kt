package com.mqclient.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared colour for every action button in the app (dialog confirm/close,
 * About actions, etc.). Centralised here so all buttons stay in sync; the
 * native menu bar / dropdown menu items use Material's own styles and are
 * intentionally excluded.
 */
val ButtonColor = Color(0xFF203701)
val ButtonTextColor = Color.White

/**
 * A raised button that drops its shadow and shrinks slightly while pressed —
 * like a physical key being pushed in — then springs back on release.
 *
 * The shadow is driven by [shadowElevation] (0 while pressed), and the shrink
 * by animating [Modifier.scale] off the button's [MutableInteractionSource].
 */
@Composable
fun PressableButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    elevation: Dp = 2.dp,
    pressedScale: Float = 0.96f,
    containerColor: Color = ButtonColor,
    contentColor: Color = ButtonTextColor,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, label = "press-scale")
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = if (pressed) 0.dp else elevation,
        interactionSource = interactionSource,
        modifier = modifier.scale(scale),
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(
                modifier = Modifier.padding(contentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}
