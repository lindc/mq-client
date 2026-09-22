package com.mqclient.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.aiui.AiContext
import com.mqclient.aiui.AiPanel
import com.mqclient.aiui.AiPanelState
import com.mqclient.app.connection.ConnectionActionsImpl
import com.mqclient.app.connection.ConnectionMenuDialogs
import com.mqclient.app.connection.ConnectionMenuState
import com.mqclient.app.connection.ConnectFailure
import com.mqclient.app.connection.TestResultData
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStore
import com.mqclient.core.CoreModule
import com.mqclient.core.ModuleRegistry
import com.mqclient.core.event.getBoolean
import com.mqclient.core.event.getLong
import com.mqclient.core.event.getString

/**
 * Window widths at or above this get the full tree sidebar; narrower windows
 * collapse the navigation to the [ModuleRail] pill column.
 */
private val WideSidebarThreshold = 840.dp

private val TreeSidebarWidth = 280.dp
private val RailSidebarWidth = 92.dp

private val StatusBarHeight = 28.dp

private val TabStripHeight = 40.dp
private val TabHeight = 28.dp
private val TabMaxWidth = 180.dp

@Composable
fun MainWindow(
    isNavigationVisible: Boolean,
    connections: List<ConnectionInfo>,
    selection: NavSelection?,
    onSelect: (NavSelection) -> Unit,
    aiPanelVisible: Boolean,
    aiPanelState: AiPanelState,
    aiContext: AiContext?,
    onDismissAiPanel: () -> Unit,
) {
    // 连接列表由调用方（Main.kt 里 collectAsState 一次）传进来 —— 这里原来是
    // `remember { ConnectionStore.connections }`，会把快照 remember 死，连接变化永远看不到。
    val uiByService = remember { ModuleRegistry.uis.associateBy { it.serviceId } }

    // 右键菜单的动作与对话框状态：动作实现（ConnectionActionsImpl）是无 UI 的回调集，
    // 对话框集中在文件尾的 ConnectionMenuDialogs 里渲染。
    val menuState = remember { ConnectionMenuState() }
    val connectionActions = remember(menuState) { ConnectionActionsImpl(menuState) }
    val propertiesServiceIds =
        remember { ModuleRegistry.propertiesUis.mapTo(mutableSetOf()) { it.serviceId } }

    // 测试连接的结果（约束：弹出结果对话框）：横跨所有模块的后缀订阅 —— 事件约定
    // 见 app-core ConnectionActions 的文档，宿主不关心是哪个模块发的。
    LaunchedEffect(Unit) {
        CoreModule.eventBus.onSuffix(".connection.test.completed", this) { event ->
            val connectionId = event.getString("connectionId") ?: return@onSuffix
            menuState.testResult = TestResultData(
                connectionName =
                    ConnectionStore.connections.value
                        .firstOrNull { it.id == connectionId }?.name ?: connectionId,
                success = event.getBoolean("success") ?: false,
                message = event.getString("message").orEmpty(),
                durationMs = event.getLong("durationMs") ?: 0L,
            )
        }
    }

    // 显式连接失败（右键菜单 / 模块面板的连接按钮）→ 错误对话框：没有这个提示的话，
    // 连接失败只剩状态点变红，用户不知道错在哪。`.connection.failed` 只有模块的
    // connect 链路会发（扫描心跳失败发的是自己的 `.scan.messages.failed`），所以这条
    // 订阅不会把后台任务失败也弹成对话框。「查看属性」的先连接流程自己消费失败事件
    // （见 ConnectionActionsImpl.showQmgrProperties），期间跳过，避免双重弹窗。
    LaunchedEffect(Unit) {
        CoreModule.eventBus.onSuffix(".connection.failed", this) { event ->
            if (menuState.connectingForProperties != null) return@onSuffix
            val connectionId = event.getString("connectionId") ?: return@onSuffix
            menuState.connectError = ConnectFailure(
                connectionId = connectionId,
                connectionName =
                    ConnectionStore.connections.value
                        .firstOrNull { it.id == connectionId }?.name ?: connectionId,
                message = event.getString("message"),
            )
        }
    }

    // Level-2 rows (queue/listener/topic) each get their own closable tab;
    // level-1 rows keep showing the connection's page directly.
    var openTabs by remember { mutableStateOf(listOf<NavSelection>()) }
    var activeTab by remember { mutableStateOf<NavSelection?>(null) }

    val connectionName = { sel: NavSelection ->
        connections.firstOrNull { it.id == sel.connectionId }?.name ?: sel.connectionId
    }

    fun openTab(tab: NavSelection) {
        if (tab !in openTabs) openTabs = openTabs + tab
        activeTab = tab
    }

    fun closeTab(tab: NavSelection) {
        val index = openTabs.indexOf(tab)
        openTabs = openTabs - tab
        if (activeTab != tab) return
        // Fall back to the neighbour on the right, then to the connection page.
        val next = openTabs.getOrNull(index) ?: openTabs.lastOrNull()
        activeTab = next
        onSelect(next ?: NavSelection(tab.connectionId))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= WideSidebarThreshold
            Row(modifier = Modifier.fillMaxSize()) {
                if (isNavigationVisible && connections.isNotEmpty()) {
                    val sidebarModifier =
                        Modifier
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                    val onNavSelect: (NavSelection) -> Unit = {
                        onSelect(it)
                        if (it.objectType != null) openTab(it) else activeTab = null
                    }
                    if (wide) {
                        ModuleTree(
                            connections = connections,
                            selection = selection,
                            onSelect = onNavSelect,
                            actions = connectionActions,
                            propertiesServiceIds = propertiesServiceIds,
                            modifier = sidebarModifier.width(TreeSidebarWidth),
                        )
                    } else {
                        ModuleRail(
                            connections = connections,
                            selection = selection,
                            onSelect = onNavSelect,
                            modifier = sidebarModifier.width(RailSidebarWidth),
                        )
                    }
                    VerticalDivider()
                }
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    if (openTabs.isNotEmpty()) {
                        ObjectTabStrip(
                            tabs = openTabs,
                            active = activeTab,
                            tabTitle = { "${connectionName(it)} / ${it.objectType}" },
                            onSelect = {
                                activeTab = it
                                onSelect(it)
                            },
                            onClose = ::closeTab,
                        )
                    }
                    // Content area: renders the middleware module's own UI for a
                    // connection, or the placeholder workspace when an object type
                    // (queue/listener/topic) is picked.
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                        val current = selection
                        when {
                            current == null -> {
                                Text(
                                    "Select a connection from the sidebar",
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }

                            current.objectType == null -> {
                                val connection = connections.firstOrNull { it.id == current.connectionId }
                                val ui = connection?.let { uiByService[it.serviceId] }
                                if (ui != null) {
                                    ui.content()
                                } else {
                                    Text(
                                        "Module UI missing for ${connection?.serviceId}",
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                            }

                            else -> {
                                ObjectTypeWorkspace(
                                    serviceName = connectionName(current),
                                    objectType = current.objectType,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
                if (aiPanelVisible) {
                    AiPanel(
                        state = aiPanelState,
                        currentContext = aiContext,
                        onDismiss = onDismissAiPanel,
                    )
                }
            }
        }
        StatusBar(currentModuleName = selection?.let { connectionName(it) })
    }
    // 右键菜单引出的对话框（删除确认 / 正在连接 / 连接失败 / 测试结果 / 队列管理器属性）。
    ConnectionMenuDialogs(state = menuState, actions = connectionActions)
}

/**
 * Tab strip above the content area: one closable tab per opened object type.
 * Tabs are a plain scrolling row rather than M3's `TabRow`, which has no
 * affordance for a close button.
 */
@Composable
private fun ObjectTabStrip(
    tabs: List<NavSelection>,
    active: NavSelection?,
    tabTitle: (NavSelection) -> String,
    onSelect: (NavSelection) -> Unit,
    onClose: (NavSelection) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(TabStripHeight)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                ObjectTab(
                    title = tabTitle(tab),
                    active = tab == active,
                    onClick = { onSelect(tab) },
                    onClose = { onClose(tab) },
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ObjectTab(
    title: String,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val containerColor =
        when {
            active -> MaterialTheme.colorScheme.secondaryContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        }

    Row(
        modifier =
            Modifier
                .height(TabHeight)
                .widthIn(max = TabMaxWidth)
                .clip(RoundedCornerShape(6.dp))
                .background(containerColor)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        CloseButton(onClick = onClose)
    }
}

/** Small round "×" that closes its tab; sized to stay inside the tab's height. */
@Composable
private fun CloseButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier =
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(
                    if (hovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "×",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Placeholder workspace for a connection's object type
 * (queue / listener / topic) until the real object views land.
 */
@Composable
private fun ObjectTypeWorkspace(
    serviceName: String,
    objectType: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$serviceName / $objectType",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Objects of this type will be listed here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusBar(currentModuleName: String?) {
    Column {
        HorizontalDivider()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(StatusBarHeight)
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentModuleName ?: "No module selected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Kept free for the connection/queue counters that come later.
            Spacer(Modifier.weight(1f))
        }
    }
}
