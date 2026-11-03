package com.mqclient.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.DecoratedWindowDefaults
import dev.nucleusframework.window.DecoratedWindowScope
import dev.nucleusframework.window.WindowControls
import dev.nucleusframework.window.utils.linux.linuxTitleBarIcons

/**
 * Window controls that always show all three buttons.
 *
 * Nucleus' stock [WindowControls] derives its slots from the desktop's own
 * layout: on GNOME it reads `org.gnome.desktop.wm.preferences button-layout`,
 * which on this machine is `appmenu:close` — close only. So on Linux the row is
 * drawn here instead, with the stock GNOME/KDE artwork; the platform behaviour
 * is kept elsewhere (macOS reserves the AppKit traffic-light footprint,
 * Windows draws its own caption buttons).
 */
@Composable
internal fun DecoratedWindowScope.WindowControlButtons(
    isActive: Boolean,
    isMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onCloseRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (Platform.Current != Platform.Linux) {
        WindowControls(modifier = modifier)
        return
    }

    val icons = linuxTitleBarIcons()
    val buttonSize = DecoratedWindowDefaults.lightTitleBarStyle().metrics.titlePaneButtonSize
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        LinuxControlButton(
            icon = icons.minimize,
            iconHover = icons.minimizeHover,
            iconPressed = icons.minimizePressed,
            size = buttonSize,
            contentDescription = "Minimize",
            onClick = onMinimize,
        )
        if (isMaximized) {
            LinuxControlButton(
                icon = icons.restore,
                iconHover = icons.restoreHover,
                iconPressed = icons.restorePressed,
                size = buttonSize,
                contentDescription = "Restore",
                onClick = onToggleMaximize,
            )
        } else {
            LinuxControlButton(
                icon = icons.maximize,
                iconHover = icons.maximizeHover,
                iconPressed = icons.maximizePressed,
                size = buttonSize,
                contentDescription = "Maximize",
                onClick = onToggleMaximize,
            )
        }
        LinuxControlButton(
            icon = icons.close,
            iconHover = if (isActive) icons.closeHoverFocused else icons.closeHover,
            iconPressed = if (isActive) icons.closePressedFocused else icons.closePressed,
            size = buttonSize,
            contentDescription = "Close",
            onClick = onCloseRequest,
        )
    }
}

/**
 * A single GNOME/KDE caption button. The default `TitleBarStyle` leaves the
 * icon tint `Color.Unspecified`, so the artwork is used as-is; the close
 * button's hover/pressed variants carry their own colours.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LinuxControlButton(
    icon: Painter,
    iconHover: Painter,
    iconPressed: Painter,
    size: DpSize,
    contentDescription: String,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier =
            Modifier
                .size(size)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) {
                    hovered = false
                    pressed = false
                }
                .onPointerEvent(PointerEventType.Press) { pressed = true }
                .onPointerEvent(PointerEventType.Release) { pressed = false },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter =
                when {
                    pressed -> iconPressed
                    hovered -> iconHover
                    else -> icon
                },
            contentDescription = contentDescription,
        )
    }
}
