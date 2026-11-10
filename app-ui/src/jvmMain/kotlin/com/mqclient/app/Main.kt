package com.mqclient.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.mqclient.ai.adapter.AiHttp
import com.mqclient.aiservice.AiChatPersistence
import com.mqclient.aiservice.AiConnectionRepository
import com.mqclient.aiui.AiChainTarget
import com.mqclient.aiui.AiContext
import com.mqclient.aiui.rememberAiPanelState
import com.mqclient.core.Config
import com.mqclient.core.ConfigStore
import com.mqclient.core.ConnectionStore
import com.mqclient.core.CoreModule
import com.mqclient.core.Language
import com.mqclient.core.ModuleI18nTable
import com.mqclient.core.ModuleRegistry
import com.mqclient.core.ThemeMode
import com.mqclient.core.database.DatabaseManager
import com.mqclient.core.database.DriverFactory
import com.mqclient.core.event.EventNames
import com.mqclient.core.concurrent.BlockingCalls
import com.mqclient.core.log.Log
import com.mqclient.message.interpreter.MessageInterpreters
import com.mqclient.rocketmq.RocketMqLogging
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.LanguageResources
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.LocalSnackbarHostState
import com.mqclient.uicommon.ThemedSnackbarHost
import dev.nucleusframework.application.DecoratedWindow
import mq_client.app_ui.generated.resources.Res
import mq_client.app_ui.generated.resources.logo
import org.jetbrains.compose.resources.painterResource
import io.github.vinceglb.filekit.FileKit
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.macOSLargeCornerRadius
import dev.nucleusframework.window.windowDragArea
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Matches the title bar height `MacosDecoratedWindow` uses. */
private val TitleBarHeight = 52.dp

/** 本文件的日志 tag；与上报错误时的 source（`app.main`）同一命名。 */
private const val LOG_TAG = "app.main"

/** Maps the persisted [Language] enum to the string-table language code used as the resource file suffix. */
private fun Language.toLanguageCode(): String = when (this) {
    Language.ENGLISH -> LanguageResources.LANGUAGE_EN_US
    Language.SIMPLIFIED_CHINESE -> LanguageResources.LANGUAGE_ZH_CN
}

/**
 * 全量 i18n 表：模块私有表（AdapterUI.i18nTables）+ 解释器私有表（registry 从各
 * 解释器模块的 classpath 资源读出，表结构与 ModuleI18nTable 同形，此处包一层）。
 * 两处 [LanguageResources.load]（启动 + 语言热切换）都走这里，别只合其一。
 */
private fun allI18nTables(): List<ModuleI18nTable> =
    ModuleRegistry.i18nTables + MessageInterpreters.i18nTables.map {
        ModuleI18nTable(base = it.base, reader = it.reader)
    }

fun main() {
    // ⚠️ 第一条语句：把 RocketMQ 的日志落点压到 ~/.mqclient/logs/rocketmqlogs。
    // 必须早于任何 RocketMQ 类被加载 —— shaded logback 在首个日志类初始化时定格配置、
    // 只读一次，之后再设属性无效。RocketMqLogging 只依赖 java.io / System，不会反过来
    // 牵出 RocketMQ / Compose / app-core 类，所以放在这里安全（详见其 KDoc）。
    //
    // 它不再是唯一防线：配置本体 rmq.logback.xml 已随 rocketmq-service 的 resources
    // 发布，任何带 RocketMQ 的 classpath（模块测试、诊断工具、CLI）都会按名称优先选中
    // 它，无需先跑本行。
    RocketMqLogging.install()

    // 启动准备放在窗口创建之前：这里的同步 IO（生成密钥 / 读配置 / 载字符串表）
    // 不该进组合体 —— 组合发生在窗口创建之后、首帧之前（会卡首帧），而且组合体
    // 不保证只执行一次（重组会重跑），更不该顺手在里面做文件 IO。
    ConfigStore.initOnStartup()
    // FileKit 在 JVM 端要求先 init（只用于它的 app 目录），全应用只此一处；
    // 命令控制台与连接表单的文件选择器都依赖它，统一在入口初始化最稳妥。
    FileKit.init(appId = "mqclient")
    val bootConfig = ConfigStore.loadConfig()
    // 模块私有表（AdapterUI.i18nTables）与公共表合并进同一张运行时表 —— 触发一次
    // SPI 发现（ModuleRegistry 惰性加载），只读元数据、不初始化模块，安全。
    runBlocking {
        LanguageResources.load(bootConfig.general.language.toLanguageCode(), allI18nTables())
    }

    // 事件总线接线同样在窗口创建之前完成：任何组合里的 effect 都可能读到
    // CoreModule.eventBus（AppRoot 的语言订阅、MainWindow 的测试结果 / 连接失败
    // 订阅……），而 LaunchedEffect 之间**没有**可靠的先后保证 —— 这些 effect 挂在
    // 不同的组合层级上（MainWindow 还在 BoxWithConstraints 的布局期子组合里），
    // 曾经实测子层的订阅先于 bootstrap 跑，eventBus 还是 null 就炸了。总线先于
    // 一切组合存在，这类读取才谈得上安全。
    bootstrapEvents()

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
    // 语言热切换：设置保存时 Main 会广播 core.language.changed，这里重载字符串表。
    // LanguageResources 换表时会 bump 内部的 Compose state，所以所有
    // stringResource() 读取方（整棵界面树）自动重组，不需要让全树换 key。
    // （事件总线已在 main() 里初始化完毕，这里直接订阅即可。）
    LaunchedEffect(Unit) {
        CoreModule.eventBus.on(EventNames.LANGUAGE_CHANGED, this) { event ->
            val language = event.payload["language"] as? Language ?: return@on
            LanguageResources.load(language.toLanguageCode(), allI18nTables())
        }
    }
    var appConfig by remember { mutableStateOf(bootConfig) }
    // 连接列表只在这一处 collect（原来 Main.kt 读三次、MainWindow 还 remember 死了一次），
    // 往下按参数传，谁都不再隐式读全局快照。
    val connections by ConnectionStore.connections.collectAsState()
    // 应用级错误提示：CoreModule.reportError 写状态，这里只负责展示。用状态而不是只
    // 听事件，是因为启动早期（读配置 / 模块初始化）的错误会早于订阅、被总线直接丢掉。
    val appError by CoreModule.lastError.collectAsState()

    // 关窗即退出：先收尾，再 onExit。收尾里任何一步抛异常都不能把 [onExit] 带走 ——
    // 漏一步清理只是泄漏，漏掉 onExit 是窗口关不掉、进程退不出。
    val closeApp: () -> Unit = {
        try {
            shutdownApp()
        } finally {
            onExit()
        }
    }
    DecoratedWindow(
        onCloseRequest = closeApp,
        title = "MQ Client",
        // 用品牌 logo 替换运行时的默认 Java 咖啡杯图标（任务栏 / 标题栏）。
        // logo.png 已是 app-ui 的 composeResources，AboutDialog 同样引用。
        icon = painterResource(Res.drawable.logo),
    ) {
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

                // 「询问 AI」链路分析目标（ibm-mq 属性对话框 / 卡片经 LocalAiAsk 发起）：
                // 非空时 AI 面板进入分析模式（目标 chip + 链路上下文）。面板关闭 /
                // 手动 toggle / 退出分析时清空 —— 下次普通打开回到「按导航选中」的上下文。
                var aiChainTarget by remember { mutableStateOf<com.mqclient.uicommon.AiAskRequest?>(null) }

                // Project the current main-UI selection into the small DTO the
                // AI panel expects, so the panel doesn't need to know about
                // NavSelection / ConnectionStore.
                val aiContext: AiContext? = aiChainTarget?.let { req ->
                    val conn = connections.firstOrNull { it.id == req.connectionId }
                    AiContext(
                        connectionName = conn?.name ?: req.connectionId,
                        serviceId = conn?.serviceId,
                        objectType = req.objectType,
                        chain = AiChainTarget(
                            connectionId = req.connectionId,
                            objectType = req.objectType,
                            objectName = req.objectName,
                            targetLabel = req.targetLabel,
                            contextJson = req.contextJson,
                        ),
                    )
                } ?: selection?.let { sel ->
                    val conn = connections.firstOrNull { it.id == sel.connectionId }
                    AiContext(
                        connectionName = conn?.name ?: sel.connectionId,
                        serviceId = conn?.serviceId,
                        objectType = sel.objectType,
                    )
                }
                // 应用级 Snackbar 宿主：包一层 Scaffold + SnackbarHost，并通过
                // CompositionLocal 把 SnackbarHostState 下发给任意深层 Composable
                // （如各业务模块的命令控制台），避免每个模块各包一层 Scaffold。
                val snackbarHostState = remember { SnackbarHostState() }
                val appScope = rememberCoroutineScope()
                CompositionLocalProvider(
                    LocalSnackbarHostState.provides(snackbarHostState),
                    // 「询问 AI」宿主回调：业务模块（属性对话框 / 卡片按钮）发起链路分析
                    // 时，记录目标并打开 AI 面板。
                    com.mqclient.uicommon.LocalAiAsk provides com.mqclient.uicommon.AiAskStarter { request ->
                        aiChainTarget = request
                        aiPanelVisible = true
                    },
                ) {
                    Scaffold(
                        // Snackbar 样式的唯一出处（ui-common ThemedSnackbarHost）：
                        // secondaryContainer/onSecondaryContainer 跟主题走，不用 M3
                        // 默认的 inverseSurface 反色对 —— 本主题（MaterialKolor TonalSpot）
                        // 浅色模式下它对比度过低（深字配黑底）。
                        snackbarHost = { ThemedSnackbarHost(snackbarHostState) },
                    ) {
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
                            if (!aiPanelVisible) aiChainTarget = null
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
                                        val conn =
                                            connections
                                                .firstOrNull { it.id == sel.connectionId }
                                        val name = conn?.name ?: sel.connectionId
                                        // objectType 是导航项原始 id（如 "queues"）；
                                        // 与侧栏同源，经 NavigationItem.titleKey 解析
                                        // 成当前语言文案，解析不到再退回原始 id。
                                        val typeLabel =
                                            sel.objectType?.let { type ->
                                                conn
                                                    ?.let { c ->
                                                        ModuleRegistry.services
                                                            .firstOrNull { it.id == c.serviceId }
                                                            ?.navigationItems(c)
                                                            ?.firstOrNull { it.id == type }
                                                            ?.takeIf { it.titleKey.isNotEmpty() }
                                                            ?.titleKey
                                                    }
                                                    ?.let { stringResource(it) }
                                                    ?: type
                                            }
                                        listOfNotNull(name, typeLabel)
                                    } ?: emptyList(),
                                onToggleNavigation = { navigationVisible = !navigationVisible },
                                onShowNewConnection = { showNewConnection = true },
                                onShowSettings = { showSettings = true },
                                onShowAbout = { showAbout = true },
                                onToggleAiPanel = {
                                    aiPanelVisible = !aiPanelVisible
                                    if (!aiPanelVisible) aiChainTarget = null
                                },
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
                        onDismissAiPanel = {
                            aiPanelVisible = false
                            aiChainTarget = null
                        },
                        onClearAiChainTarget = { aiChainTarget = null },
                        // 编辑连接落盘后从磁盘重载内存 appConfig —— 与新建连接一致，
                        // 避免设置页的整份保存把刚改过的连接覆盖掉。列表本身由
                        // ConnectionStore.connections 的 StateFlow 订阅自动刷新。
                        onConnectionsChanged = {
                            ConfigStore.loadConfigOrNull()?.let { loaded -> appConfig = loaded }
                        },
                    )
                    }
                }
                }
                if (showAbout) {
                    AboutDialog(onDismiss = { showAbout = false })
                }
                if (showNewConnection) {
                    NewConnectionDialog(
                        // 连接名为空时的默认值，与设置页同一策略（连接 N）。
                        defaultName = "连接 ${appConfig.ai.connections.size + 1}",
                        onDismiss = { showNewConnection = false },
                        // 落盘已由模块面板完成；这里从磁盘重载内存 appConfig —— 设置
                        // 对话框的草稿基于它，不同步的话下次整份覆盖保存会抹掉新连接。
                        onConnectionCreated = {
                            ConfigStore.loadConfigOrNull()?.let { loaded -> appConfig = loaded }
                            // 「连接已创建」轻提示：对话框是独立窗口取不到
                            // LocalSnackbarHostState，统一挂宿主 —— 用这里持有的
                            // state 直接发。
                            appScope.launch {
                                snackbarHostState.showSnackbar(stringResource("common_connection_created"))
                            }
                        },
                    )
                }
                appError?.let { error ->
                    ErrorDialog(error = error, onDismiss = { CoreModule.clearLastError() })
                }
                if (showSettings) {
                    com.mqclient.app.settings.SettingsDialog(
                        initial = appConfig,
                        onDismiss = { showSettings = false },
                        // Returns null on success, or the reason the write
                        // failed — the dialog stays open and surfaces it.
                        onSave = { newConfig ->
                            // 无变更 = 保存成功但零副作用：不写盘、不发事件，
                            // 只关对话框（把值改回去再点保存是最常见路径）。
                            if (newConfig == appConfig) {
                                showSettings = false
                                null
                            } else {
                                // AI 连接快照规则（新建 / service_url 变化换新 UUID）在
                                // 落盘**前**定稿：磁盘上的 id 与随后广播的快照事件一致。
                                // activeConnectionId 指向的连接被换发新 id 时同步跟随。
                                val snapshotChanges = AiConnectionRepository.resolveSnapshotChanges(
                                    previous = appConfig.ai.connections,
                                    incoming = newConfig.ai.connections,
                                )
                                val effective =
                                    if (snapshotChanges.idRewrite.isEmpty()) newConfig
                                    else newConfig.copy(
                                        ai = newConfig.ai.copy(
                                            connections = snapshotChanges.connections,
                                            activeConnectionId =
                                            snapshotChanges.idRewrite[newConfig.ai.activeConnectionId]
                                                ?: newConfig.ai.activeConnectionId,
                                        ),
                                    )
                                val failure = ConfigStore.saveConfig(effective).exceptionOrNull()
                                if (failure == null) {
                                    // Only adopt the draft once it's on disk:
                                    // otherwise the UI would show settings the
                                    // next launch won't have.
                                    val previous = appConfig
                                    appConfig = effective
                                    showSettings = false
                                    // 广播设置变更（核心事件）：主题 / i18n / 需要重载配置的模块
                                    // 自己订阅就行，设置对话框不需要认识这些消费者。
                                    CoreModule.eventBus.emit(
                                        name = EventNames.SETTINGS_CHANGED,
                                        payload = mapOf("config" to effective),
                                    )
                                    // AI 快照事件放在 SETTINGS_CHANGED 之后：消费方（ai-panel
                                    // 落库）只关心快照本身，顺序无依赖，但先声明配置再声明快照
                                    // 读起来更符合因果。
                                    AiConnectionRepository.emitSnapshotsCreated(snapshotChanges.snapshotCandidates)
                                    if (previous.general.language != effective.general.language) {
                                        CoreModule.eventBus.emit(
                                            name = EventNames.LANGUAGE_CHANGED,
                                            payload = mapOf("language" to effective.general.language),
                                        )
                                    }
                                    null
                                } else {
                                    stringResource("settings_save_failed").format(
                                        failure.userMessage(),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 打开事件总线并完成启动接线（**由 [main] 在窗口创建之前调用**）：
 *  1. 建应用级 scope（SupervisorJob + Dispatchers.Default，不用 GlobalScope）；
 *  2. CoreModule 拿到总线，数据库也在这里初始化（JDBC 单连接 → 单槽 IO 调度器串行化），
 *     之后模块读 CoreModule.eventBus / DatabaseManager 或注入的 ServiceContext；
 *  3. 各业务模块（SPI 发现）用 ServiceContext 注册自己的事件订阅与定时任务
 *     （`ServiceContext.jobScheduler`，全进程一个调度器）—— 新增模块不需要改这里；
 *  4. 等订阅真正开始收集后再广播 core.app.started：replay=0 时早于订阅者开始收集
 *     的事件会被丢弃（见 EventBus 的注释）。
 */
private fun bootstrapEvents() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("app"))
    CoreModule.initialize(scope)
    // 数据库：JDBC 单连接必须串行，所以给它配一个「同时只跑一个任务」的 IO 调度器
    // （Dispatchers.IO 是 JVM 专有，所以接线在这里而不是 commonMain 的 DatabaseManager 里）。
    // 初始化失败（目录不可写等）不该拖垮启动 —— 报到错误通道，界面会提示用户。
    runCatching { DatabaseManager.initialize(DriverFactory(), Dispatchers.IO.limitedParallelism(1)) }
        .onFailure { error ->
            CoreModule.reportError(
                message = stringResource("app_db_init_failed").withArg(error.message ?: error::class.simpleName ?: "unknown"),
                source = "core.database",
                error = error,
            )
        }
    ModuleRegistry.initializeServices(CoreModule.eventBus, scope, CoreModule.jobScheduler)
    scope.launch {
        // AI 落库：建表 + 按保留天数清理 + 订阅快照/对话两个事件。DB 初始化失败时
        // register 会上报并整块缺席（对话照常，只是不落库）。
        AiChatPersistence.register(scope)
        CoreModule.eventBus.awaitSubscribers()
        CoreModule.eventBus.emit(EventNames.APP_STARTED)
    }
}

/**
 * 关窗收尾五步，顺序有讲究（和 DatabaseManager.close 的注释一致）：先停模块资源，
 * 再关 AI 的 HTTP 引擎，然后停调度器 / 订阅，再丢弃阻塞调用线程池，最后关库。
 *
 * 每一步单独兜住异常：任一步失败都不能把后面的收尾带走 —— 漏掉 [CoreModule.shutdown]
 * 会让调度器与全部订阅泄漏，漏掉 [DatabaseManager.close] 会把 JDBC 连接留在半关闭
 * 状态。失败只记日志：进程马上就退了，这时候弹提示用户也看不到。
 */
private fun shutdownApp() {
    // 1. 模块资源：连接 / 会话 / 订阅 / 定时任务（SPI 初始化逆序，见 disposeServices）。
    //    排在 CoreModule.shutdown() 之前 —— 这时总线与调度器还活着，
    //    模块收尾时发的事件、上报的失败才有人收。
    runCatching { ModuleRegistry.disposeServices() }
        .onFailure { error -> Log.warn(LOG_TAG, "disposeServices 失败：$error") }
    // 2. AI 的 Ktor 引擎：排在模块之后（此刻适配器不会再发请求）、CoreModule 之前
    //    （不依赖总线）。不关的话 CIO / Java 引擎的 selector / dispatcher 线程会
    //    留到 JVM 退出 —— 对桌面应用是「任务管理器里多几个线程」的可见泄漏。
    runCatching { AiHttp.close() }
        .onFailure { error -> Log.warn(LOG_TAG, "AiHttp.close 失败：$error") }
    // 3. 调度器与全部事件订阅（应用 scope 由 CoreModule 持有，随之级联取消）。
    runCatching { CoreModule.shutdown() }
        .onFailure { error -> Log.warn(LOG_TAG, "CoreModule.shutdown 失败：$error") }
    // 4. 阻塞调用线程池：模块已经收完，不会再提交新的阻塞调用。
    //    shutdownNow 丢弃在跑的调用（它们不响应中断，优雅等等于等十几分钟）。
    runCatching { BlockingCalls.shutdown() }
        .onFailure { error -> Log.warn(LOG_TAG, "BlockingCalls.shutdown 失败：$error") }
    // 5. 最后关库：调度器与订阅都停了之后再关，否则关闭途中还在跑的查询会打在
    //    已关的连接上（见 DatabaseManager.close 的注释）。
    runCatching { DatabaseManager.close() }
        .onFailure { error -> Log.warn(LOG_TAG, "DatabaseManager.close 失败：$error") }
}
