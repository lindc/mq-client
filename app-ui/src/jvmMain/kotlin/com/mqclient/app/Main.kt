package com.mqclient.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

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
                var showAbout by remember { mutableStateOf(false) }
                var showNewConnection by remember { mutableStateOf(false) }
                WindowScaffold(
                modifier = Modifier.macOSLargeCornerRadius(),
                titleBar = {
                    Box(Modifier.fillMaxWidth().height(TitleBarHeight).windowDragArea()) {
                        TitleBarPanel(
                            onToggleNavigation = { navigationVisible = !navigationVisible },
                            onShowAbout = { showAbout = true },
                            onShowNewConnection = { showNewConnection = true },
                            onExit = { exitApplication() },
                        )
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
            if (showAbout) {
                AboutDialog(onDismiss = { showAbout = false })
            }
            if (showNewConnection) {
                NewConnectionDialog(onDismiss = { showNewConnection = false })
            }
            }
        }
    }
}

/**
 * Left end of the title bar: the navigation toggle, a divider, the menu bar
 * (文件 / 帮助), and the stretch the breadcrumb trail will grow into.
 *
 * The row spans the full bar so the empty stretch stays part of the drag area,
 * while the [IconButton] and [MenuBarItem]s still swallow their own clicks the
 * same way the window controls do.
 */
@Composable
private fun TitleBarPanel(
    onToggleNavigation: () -> Unit,
    onShowAbout: () -> Unit,
    onShowNewConnection: () -> Unit,
    onExit: () -> Unit,
) {
    var fileMenuExpanded by remember { mutableStateOf(false) }
    var helpMenuExpanded by remember { mutableStateOf(false) }

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
        MenuBarItem("文件", fileMenuExpanded, { fileMenuExpanded = it }) {
            DropdownMenuItem(
                text = { Text("新建连接") },
                onClick = onShowNewConnection,
            )
            DropdownMenuItem(
                text = { Text("退出") },
                onClick = onExit,
            )
        }
        MenuBarItem("帮助", helpMenuExpanded, { helpMenuExpanded = it }) {
            DropdownMenuItem(
                text = { Text("关于") },
                onClick = { onShowAbout() },
            )
        }
        // Breadcrumb trail goes here.
        Box(Modifier.weight(1f))
    }
}

/**
 * A single top-level menu button that opens a [DropdownMenu] on click. Lives in
 * the title bar, so it behaves like a desktop menu bar item.
 */
@Composable
private fun MenuBarItem(
    label: String,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        TextButton(onClick = { onExpandChange(true) }) {
            Text(label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandChange(false) },
            content = content,
        )
    }
}

/**
 * Custom About dialog: top panel with the app name, a two-tab body, and a
 * bottom panel holding the close button at the bottom-right.
 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.size(560.dp, 460.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                // Top panel: app name.
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "MQClient",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                var selectedTab by remember { mutableStateOf(0) }
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("关于") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("使用库列表") })
                }

                // Tab body.
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                    when (selectedTab) {
                        0 -> AboutTab()
                        1 -> LibraryList()
                    }
                }

                // Bottom panel: close button, bottom-right.
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(end = 16.dp, bottom = 8.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    PressableButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun AboutTab() {
    Row(Modifier.fillMaxSize()) {
        AnalogClock(Modifier.size(180.dp).align(Alignment.CenterVertically))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("软件说明", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("连接mq", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Classic three-hand clock that ticks every second off the system clock. */
@Composable
private fun AnalogClock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = LocalTime.now()
        }
    }
    // Capture theme colours outside the Canvas: the draw lambda has no Composer,
    // so it cannot read CompositionLocals like MaterialTheme.colorScheme.
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val error = MaterialTheme.colorScheme.error
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f

        // Point at `angleDeg` clockwise from 12 o'clock, `dist` from centre.
        fun point(angleDeg: Float, dist: Float): Offset {
            val rad = angleDeg * Math.PI.toFloat() / 180f
            return Offset(cx + sin(rad) * dist, cy - cos(rad) * dist)
        }

        drawCircle(color = outline, radius = r, style = Stroke(1.5.dp.toPx()))

        // Hour ticks; longer at the quarters.
        val inner = r - 4.dp.toPx()
        for (i in 0 until 12) {
            val len = if (i % 3 == 0) 12.dp.toPx() else 6.dp.toPx()
            val a = i * 30f
            drawLine(
                color = outline,
                start = point(a, inner),
                end = point(a, inner + len),
                strokeWidth = if (i % 3 == 0) 2.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        val sec = now.second + now.nano / 1_000_000_000f
        val min = now.minute + sec / 60f
        val hour = (now.hour % 12) + min / 60f

        drawLine(onSurface, Offset(cx, cy), point(hour * 30f, r * 0.5f), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(onSurface, Offset(cx, cy), point(min * 6f, r * 0.75f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(error, Offset(cx, cy), point(sec * 6f, r * 0.85f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(onSurface, radius = 3.dp.toPx(), center = Offset(cx, cy))
    }
}

private data class Library(
    val name: String,
    val protocol: String,
)

private val FakeLibraries =
    listOf(
        Library("Apache Kafka", "Kafka Protocol"),
        Library("RabbitMQ", "AMQP 0.9.1"),
        Library("Apache ActiveMQ", "OpenWire / JMS"),
        Library("IBM MQ", "MQI"),
        Library("Apache RocketMQ", "Remoting"),
        Library("Apache Artemis", "AMQP / OpenWire"),
        Library("Apache Pulsar", "Pulsar Protocol"),
        Library("NATS", "NATS Protocol"),
        Library("ZeroMQ", "ZMQ"),
        Library("Apache Qpid", "AMQP"),
    )

/**
 * Library list with alternating row backgrounds. The scrollbar only shows up
 * when the rows outgrow the body (Scrollbar hides itself otherwise).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryList() {
    val scrollState = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            FakeLibraries.forEachIndexed { index, lib ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(if (index % 2 == 0) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(lib.name, Modifier.weight(1f), textAlign = TextAlign.Start)
                    Text(lib.protocol, Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/** Empty New-connection dialog — content is intentionally left blank for now. */
@Composable
private fun NewConnectionDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PressableButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("新建连接") },
    )
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
