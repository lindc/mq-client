package com.mqclient.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.DecoratedWindowDefaults
import dev.nucleusframework.window.DecoratedWindowScope
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowControls
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.macOSLargeCornerRadius
import dev.nucleusframework.window.utils.linux.linuxTitleBarIcons
import dev.nucleusframework.window.windowDragArea
import mq_client.app_ui.generated.resources.Res
import mq_client.app_ui.generated.resources.ic_menu
import org.jetbrains.compose.resources.painterResource

/** Matches the title bar height `MacosDecoratedWindow` uses. */
private val TitleBarHeight = 52.dp

fun main() = nucleusApplication {
    val closeApp: () -> Unit = ::exitApplication
    DecoratedWindow(onCloseRequest = closeApp, title = "MQ Client") {
        // Drives LocalIsDarkTheme (the control icons follow it).
        NucleusDecoratedWindowTheme(isDark = false) {
            AppTheme(isDarkTheme = false) {
                // Colour the native window's own background: the Tao backend
                // otherwise keeps its dark default (0xFF2B2B2B) and the frame
                // shows up black. Pulled from the theme so they always match.
                WindowBackground(MaterialTheme.colorScheme.background)
                // Lives here, not inside MainWindow, because the title bar's
                // hamburger and the navigation suite are two halves of one toggle.
                var navigationVisible by remember { mutableStateOf(true) }
                WindowScaffold(
                modifier = Modifier.macOSLargeCornerRadius(),
                titleBar = {
                    Box(Modifier.fillMaxWidth().height(TitleBarHeight).windowDragArea()) {
                        TitleBarPanel(onToggleNavigation = { navigationVisible = !navigationVisible })
                        WindowControlButtons(
                            isActive = state.isActive,
                            isMaximized = state.isMaximized,
                            onMinimize = { nucleusWindow.setMinimized(true) },
                            onToggleMaximize = { nucleusWindow.setMaximized(!state.isMaximized) },
                            onCloseRequest = closeApp,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                },
                // Docked gives the title bar its own row, so the content (and the
                // sidebar in it) starts *below* the drag band and stays clickable.
                // Overlay paints the bar on top of the content, and its
                // windowDragArea swallows clicks in the top strip — which is
                // exactly where the sidebar's first items live.
                titleBarPlacement = TitleBarPlacement.Docked,
            ) { _ ->
                MainWindow(isNavigationVisible = navigationVisible)
            }
            }
        }
    }
}

/**
 * Left end of the title bar: the navigation toggle, a divider, and the stretch
 * the breadcrumb trail will grow into.
 *
 * The row spans the full bar so the empty stretch stays part of the drag area,
 * while the [IconButton] still swallows its own clicks the same way the window
 * controls do.
 */
@Composable
private fun TitleBarPanel(onToggleNavigation: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleNavigation) {
            Icon(
                painter = painterResource(Res.drawable.ic_menu),
                contentDescription = "Toggle navigation",
            )
        }
        VerticalDivider(modifier = Modifier.height(24.dp))
        // Breadcrumb trail goes here.
        Box(Modifier.weight(1f))
    }
}

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
private fun DecoratedWindowScope.WindowControlButtons(
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
