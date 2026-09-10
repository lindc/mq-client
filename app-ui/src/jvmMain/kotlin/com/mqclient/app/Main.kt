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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.mqclient.aipanel.AiContext
import com.mqclient.aipanel.rememberAiPanelState
import com.mqclient.core.Config
import com.mqclient.core.ConfigStore
import com.mqclient.core.ConnectionStore
import com.mqclient.core.CoreModule
import com.mqclient.core.Language
import com.mqclient.core.ModuleRegistry
import com.mqclient.core.ThemeMode
import com.mqclient.core.event.EventNames
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.LanguageResources
import com.mqclient.uicommon.i18n.stringResource
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
import mq_client.app_ui.generated.resources.logo
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Matches the title bar height `MacosDecoratedWindow` uses. */
private val TitleBarHeight = 52.dp

/** Maps the persisted [Language] enum to the string-table language code used as the resource file suffix. */
private fun Language.toLanguageCode(): String = when (this) {
    Language.ENGLISH -> LanguageResources.LANGUAGE_EN_US
    Language.SIMPLIFIED_CHINESE -> LanguageResources.LANGUAGE_ZH_CN
}

fun main() {
    // 启动准备放在窗口创建之前：这里的同步 IO（生成密钥 / 读配置 / 载字符串表）
    // 不该进组合体 —— 组合发生在窗口创建之后、首帧之前（会卡首帧），而且组合体
    // 不保证只执行一次（重组会重跑），更不该顺手在里面做文件 IO。
    ConfigStore.initOnStartup()
    val bootConfig = ConfigStore.loadConfig()
    runBlocking { LanguageResources.load(bootConfig.general.language.toLanguageCode()) }

    nucleusApplication { AppRoot(bootConfig, onExit = { exitApplication() }) }
}

/**
 * 窗口内容的根节点。走到这里说明窗口已经建好、正要出首帧，
 * 所以启动 IO 已经在 [main] 里做完了。
 *
 * [onExit] 由调用方传入（`ApplicationScope.exitApplication()` 是接收者函数，
 * 抽到普通 @Composable 里就取不到了）。
 */
@Composable
private fun AppRoot(bootConfig: Config, onExit: () -> Unit) {
    // 事件总线接线：只在首次组合时跑一次（@Composable lambda 会随重组重复执行）。
    remember { bootstrapEvents() }
    // 语言热切换：设置保存时 Main 会广播 core.language.changed，这里重载字符串表。
    // LanguageResources 换表时会 bump 内部的 Compose state，所以所有
    // stringResource() 读取方（整棵界面树）自动重组，不需要让全树换 key。
    LaunchedEffect(Unit) {
        CoreModule.eventBus.on(EventNames.LANGUAGE_CHANGED, this) { event ->
            val language = event.payload["language"] as? Language ?: return@on
            LanguageResources.load(language.toLanguageCode())
        }
    }
    var appConfig by remember { mutableStateOf(bootConfig) }
    // 连接列表只在这一处 collect（原来 Main.kt 读三次、MainWindow 还 remember 死了一次），
    // 往下按参数传，谁都不再隐式读全局快照。
    val connections by ConnectionStore.connections.collectAsState()

    // 关窗即退出：先收掉所有事件订阅与模块 scope（应用 scope 由 CoreModule 持有），
    // 再结束进程。
    val closeApp: () -> Unit = {
        CoreModule.shutdown()
        onExit()
    }
    DecoratedWindow(onCloseRequest = closeApp, title = "MQ Client") {
        // Resolve dark/light once at the top so the window-chrome theme
        // (NucleusDecoratedWindowTheme) and the content theme (AppTheme)
        // share a single source of truth — otherwise the title bar would
        // pick one mode and the body the other. SYSTEM defers to the
        // platform's isSystemInDarkTheme().
        val isDark = when (appConfig.theme.themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
        // Drives LocalIsDarkTheme (the control icons follow it).
            NucleusDecoratedWindowTheme(isDark = isDark) {
            AppTheme(theme = appConfig.theme, isDarkTheme = isDark) {
                // The title bar's breadcrumb and the sidebar both read this, so
                // the selection lives above MainWindow.
                var selection by remember {
                    mutableStateOf(connections.firstOrNull()?.let { NavSelection(it.id) })
                }
                // Colour the native window's own background: the Tao backend
                // otherwise keeps its dark default (0xFF2B2B2B) and the frame
                // shows up black. Pulled from the theme so they always match.
                WindowBackground(MaterialTheme.colorScheme.background)
                // Lives here, not inside MainWindow, because the title bar's
                // hamburger and the navigation suite are two halves of one toggle.
                var navigationVisible by remember { mutableStateOf(true) }
                var showAbout by remember { mutableStateOf(false) }
                var showNewConnection by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                // Right-side AI assistant panel — default hidden, toggled by
                // Cmd/Ctrl+Shift+A or via the menu entry below.
                var aiPanelVisible by remember { mutableStateOf(false) }
                val aiPanelState = rememberAiPanelState(appConfig.ai)

                // Project the current main-UI selection into the small DTO the
                // AI panel expects, so the panel doesn't need to know about
                // NavSelection / ConnectionStore.
                val aiContext: AiContext? = selection?.let { sel ->
                    val conn = connections.firstOrNull { it.id == sel.connectionId }
                    AiContext(
                        connectionName = conn?.name ?: sel.connectionId,
                        serviceId = conn?.serviceId,
                        objectType = sel.objectType,
                    )
                }
                WindowScaffold(
                    modifier = Modifier.macOSLargeCornerRadius().onPreviewKeyEvent { event ->
                        // Cmd+Shift+A on macOS, Ctrl+Shift+A elsewhere — toggles
                        // the AI panel no matter which sub-component has focus.
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.A &&
                            event.isShiftPressed &&
                            (event.isMetaPressed || event.isCtrlPressed)
                        ) {
                            aiPanelVisible = !aiPanelVisible
                            true
                        } else {
                            false
                        }
                    },
                    titleBar = {
                        Box(Modifier.fillMaxWidth().height(TitleBarHeight).windowDragArea()) {
                            TitleBarPanel(
                                breadcrumb =
                                    selection?.let { sel ->
                                        val name =
                                            connections
                                                .firstOrNull { it.id == sel.connectionId }
                                                ?.name ?: sel.connectionId
                                        listOfNotNull(name, sel.objectType)
                                    } ?: emptyList(),
                                onToggleNavigation = { navigationVisible = !navigationVisible },
                                onShowNewConnection = { showNewConnection = true },
                                onShowSettings = { showSettings = true },
                                onShowAbout = { showAbout = true },
                                onToggleAiPanel = { aiPanelVisible = !aiPanelVisible },
                                onExit = closeApp,
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
                    MainWindow(
                        isNavigationVisible = navigationVisible,
                        connections = connections,
                        selection = selection,
                        onSelect = { selection = it },
                        aiPanelVisible = aiPanelVisible,
                        aiPanelState = aiPanelState,
                        aiContext = aiContext,
                        onDismissAiPanel = { aiPanelVisible = false },
                    )
                }
                if (showAbout) {
                    AboutDialog(onDismiss = { showAbout = false })
                }
                if (showNewConnection) {
                    NewConnectionDialog(onDismiss = { showNewConnection = false })
                }
                if (showSettings) {
                    com.mqclient.app.settings.SettingsDialog(
                        initial = appConfig,
                        onDismiss = { showSettings = false },
                        // Returns null on success, or the reason the write
                        // failed — the dialog stays open and surfaces it.
                        onSave = { newConfig ->
                            val failure = ConfigStore.saveConfig(newConfig).exceptionOrNull()
                            if (failure == null) {
                                // Only adopt the draft once it's on disk:
                                // otherwise the UI would show settings the
                                // next launch won't have.
                                val previous = appConfig
                                appConfig = newConfig
                                showSettings = false
                                // 广播设置变更（核心事件）：主题 / i18n / 需要重载配置的模块
                                // 自己订阅就行，设置对话框不需要认识这些消费者。
                                CoreModule.eventBus.emit(
                                    name = EventNames.SETTINGS_CHANGED,
                                    payload = mapOf("config" to newConfig),
                                )
                                if (previous.general.language != newConfig.general.language) {
                                    CoreModule.eventBus.emit(
                                        name = EventNames.LANGUAGE_CHANGED,
                                        payload = mapOf("language" to newConfig.general.language),
                                    )
                                }
                                null
                            } else {
                                stringResource("settings_save_failed").format(
                                    failure.message ?: failure.javaClass.simpleName,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 打开事件总线并完成启动接线：
 *  1. 建应用级 scope（SupervisorJob + Dispatchers.Default，不用 GlobalScope）；
 *  2. CoreModule 拿到总线，之后模块读 CoreModule.eventBus 或注入的 ServiceContext；
 *  3. 各业务模块（SPI 发现）用 ServiceContext 注册自己的事件订阅 —— 新增模块不需要
 *     改这里；
 *  4. 等订阅真正开始收集后再广播 core.app.started：replay=0 时早于订阅者开始收集
 *     的事件会被丢弃（见 EventBus 的注释）。
 */
private fun bootstrapEvents() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("app"))
    CoreModule.initialize(scope)
    ModuleRegistry.initializeServices(CoreModule.eventBus, scope, CoreModule.jobScheduler)
    scope.launch {
        CoreModule.eventBus.awaitSubscribers()
        CoreModule.eventBus.emit(EventNames.APP_STARTED)
    }
}

/**
 * Left end of the title bar: the navigation toggle, a divider, the single
 * 文件 menu bar item, and the breadcrumb trail that follows it.
 *
 * The row spans the full bar so the empty stretch stays part of the drag area,
 * while the [IconButton] and [MenuBarItem]s still swallow their own clicks the
 * same way the window controls do.
 */
@Composable
private fun TitleBarPanel(
    breadcrumb: List<String>,
    onToggleNavigation: () -> Unit,
    onShowNewConnection: () -> Unit,
    onShowSettings: () -> Unit,
    onShowAbout: () -> Unit,
    onToggleAiPanel: () -> Unit,
    onExit: () -> Unit,
) {
    var fileMenuExpanded by remember { mutableStateOf(false) }

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
        MenuBarItem(stringResource("menu_file"), fileMenuExpanded, { fileMenuExpanded = it }) {
            DropdownMenuItem(
                text = { Text(stringResource("menu_new_connection")) },
                // The menu only collapses through its expanded state; an item
                // click does not dismiss it by itself.
                onClick = {
                    fileMenuExpanded = false
                    onShowNewConnection()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource("menu_settings")) },
                onClick = {
                    fileMenuExpanded = false
                    onShowSettings()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource("menu_ai_assistant")) },
                onClick = {
                    fileMenuExpanded = false
                    onToggleAiPanel()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource("menu_about")) },
                onClick = {
                    fileMenuExpanded = false
                    onShowAbout()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource("menu_exit")) },
                onClick = {
                    fileMenuExpanded = false
                    onExit()
                },
            )
        }
        // Only separate the two while there is actually a trail to show.
        if (breadcrumb.isNotEmpty()) {
            VerticalDivider(modifier = Modifier.height(16.dp).padding(horizontal = 8.dp))
        }
        Box(Modifier.weight(1f)) {
            BreadcrumbTrail(breadcrumb)
        }
    }
}

/**
 * `连接 > 对象` trail for whatever the content area is showing: the connection
 * name, plus the object type once a level-2 row (or its tab) is selected.
 */
@Composable
private fun BreadcrumbTrail(segments: List<String>) {
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                Text(
                    text = ">",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            Text(
                text = segment,
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (index == segments.lastIndex) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
 * Custom About dialog laid out like a product "about" card:
 *   - header band with the title and a close (✕) button on the right
 *   - a two-column body: the ticking clock (logo) on the left, app info +
 *     the supported-middleware list + action buttons on the right
 *   - a footer with the copyright line
 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    var showLicense by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Root box so the license dialog can overlay the card.
        Box {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.size(620.dp, 580.dp),
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Header band: title on the left, close button on the right.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource("about_title"),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            IconButton(onClick = onDismiss) {
                                Text(
                                    "✕",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }

                    // Body: clock column + info column.
                    Row(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                        // Left column: the analog clock (logo) with a "时钟" caption.
                        Column(
                            modifier = Modifier.width(120.dp).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            AnalogClock(Modifier.size(96.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource("titlebar_clock"), style = MaterialTheme.typography.labelMedium)
                        }

                        Spacer(Modifier.width(16.dp))

                        // Right column: app info, supported middleware, actions.
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            // App icon on the left, 4 text lines on the right.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(
                                    painter = painterResource(Res.drawable.logo),
                                    contentDescription = stringResource("about_logo_description"),
                                    modifier = Modifier.size(72.dp),
                                )
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text("MQClient", style = MaterialTheme.typography.headlineSmall)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        stringResource("about_version"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(stringResource("about_description_line1"), style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource("about_description_line2"), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            Text(stringResource("about_supported_middleware"), style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            // Bounded, scrollable area: the scrollbar only shows up
                            // when the list outgrows the max height (otherwise hidden).
                            Box(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                            ) {
                                val listScroll = rememberScrollState()
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .padding(end = 10.dp)
                                            .verticalScroll(listScroll),
                                ) {
                                    aboutMiddleware.forEach { lib ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(lib.name, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                                            VerticalDivider(
                                                modifier =
                                                    Modifier
                                                        .height(20.dp)
                                                        .padding(horizontal = 12.dp),
                                            )
                                            Text(
                                                lib.protocol,
                                                modifier = Modifier.weight(1f),
                                                textAlign = TextAlign.End,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                VerticalScrollbar(
                                    adapter = rememberScrollbarAdapter(listScroll),
                                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            // Action buttons: license viewer + close, equal width.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                PressableButton(
                                    onClick = { showLicense = true },
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource("license_view")) }
                                Spacer(Modifier.width(12.dp))
                                PressableButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource("common_close")) }
                            }
                        }
                    }

                    // Footer with the copyright line.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "© 2026 Your Name. All rights reserved.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (showLicense) {
                LicenseDialog(onDismiss = { showLicense = false })
            }
        }
    }
}

/**
 * Middleware / protocols listed in the About dialog.
 *
 * Sourced from [ModuleRegistry] (Java SPI) instead of being hardcoded here, so
 * adding a new `<mq>-service` module — together with its `protocol` field —
 * shows up in the About dialog automatically. Sorted by display name so the
 * ordering is stable across runs (SPI discovery order is not guaranteed).
 */
private val aboutMiddleware: List<Library> by lazy {
    ModuleRegistry.services
        .sortedBy { it.name }
        .map { Library(it.name, it.protocol) }
}

/** Simple dialog listing the open-source components this app bundles. */
@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    // Load the third-party list lazily from the packaged resource. The file is
    // bundled under composeResources/files/ so the Compose Resources Gradle
    // plugin picks it up; each line is "name,license,copyright".
    val components by produceState<List<ThirdPartyComponent>>(initialValue = emptyList()) {
        value = loadThirdPartyComponents()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { PressableButton(onClick = onDismiss) { Text(stringResource("common_close")) } },
        title = { Text(stringResource("license_title")) },
        text = { LibraryList(components, Modifier.size(380.dp, 300.dp)) },
    )
}

/** Path to the bundled third-party-component manifest inside composeResources. */
private const val ThirdPartyComponentPath = "files/third-party-component/third-party-component.txt"

/**
 * Parses the third-party-component manifest into [ThirdPartyComponent] rows.
 *
 * Uses an RFC 4180 subset: a field wrapped in double quotes may contain
 * commas and newlines; a literal `"` inside a quoted field is escaped as `""`.
 * Records that don't split into the three expected fields are skipped so a
 * stray blank line or a malformed entry never crashes the dialog.
 */
private suspend fun loadThirdPartyComponents(): List<ThirdPartyComponent> =
    try {
        parseComponents(Res.readBytes(ThirdPartyComponentPath).decodeToString())
    } catch (_: Exception) {
        emptyList()
    }

/**
 * State machine over a small CSV dialect. `inQuotes` flips when we see the
 * matching `"`, which lets commas and newlines pass through inside a quoted
 * field; a doubled `""` inside quotes becomes a single `"`. Records whose
 * field count isn't exactly three are dropped by the caller.
 */
private fun parseComponents(raw: String): List<ThirdPartyComponent> {
    val records = mutableListOf<List<String>>()
    val record = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false

    fun endField() {
        record.add(field.toString().trim())
        field.setLength(0)
    }

    fun endRecord() {
        endField()
        if (record.any { it.isNotEmpty() }) records.add(record.toList())
        record.clear()
    }

    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (inQuotes) {
            when (c) {
                '"' -> {
                    // Doubled quote inside a quoted field is a literal `"`.
                    if (i + 1 < raw.length && raw[i + 1] == '"') {
                        field.append('"')
                        i += 2
                    } else {
                        inQuotes = false
                        i++
                    }
                }
                else -> {
                    // Inside quotes we keep everything verbatim, including the
                    // newlines and commas the parser otherwise treats as
                    // delimiters.
                    field.append(c)
                    i++
                }
            }
        } else {
            when (c) {
                '"' -> {
                    inQuotes = true
                    i++
                }
                ',' -> {
                    endField()
                    i++
                }
                '\n' -> {
                    endRecord()
                    i++
                }
                '\r' -> {
                    endRecord()
                    i++
                    if (i < raw.length && raw[i] == '\n') i++
                }
                else -> {
                    field.append(c)
                    i++
                }
            }
        }
    }
    // Flush a trailing record if the file doesn't end with a newline.
    if (field.isNotEmpty() || record.isNotEmpty()) endRecord()

    return records
        .filter { it.size == 3 && it[0].isNotEmpty() }
        .map { ThirdPartyComponent(name = it[0], license = it[1], copyright = it[2]) }
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

/** One row in the "支持的中间件" list inside the About dialog. */
private data class Library(
    val name: String,
    val protocol: String,
)

/** One row in the open-source licenses dialog (loaded from the manifest file). */
private data class ThirdPartyComponent(
    val name: String,
    val license: String,
    val copyright: String,
)

/**
 * Library list with alternating row backgrounds. Each component occupies a
 * 3-line block (name / copyright / license) so the dialog reads as the
 * attribution card the mockup calls for. The scrollbar only shows up when the
 * list outgrows the body (Scrollbar hides itself otherwise).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryList(components: List<ThirdPartyComponent>, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            components.forEachIndexed { index, lib ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(if (index % 2 == 0) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = lib.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = lib.copyright,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "License: ${lib.license}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
        title = { Text(stringResource("menu_new_connection")) },
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
