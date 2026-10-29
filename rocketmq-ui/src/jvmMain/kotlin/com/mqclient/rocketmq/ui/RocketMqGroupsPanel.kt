package com.mqclient.rocketmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.rocketmq.RocketMqEvents
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.ui.RocketMqCreateGroupDialog
import com.mqclient.rocketmq.ui.ai.RocketMqAiChainBuilder
import com.mqclient.rocketmq.ui.ai.askAiCardAction
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.LocalAiAsk
import com.mqclient.uicommon.command.ToolbarButton
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/** 状态语义色：成员在线绿 / 积压告警橙 / 零成员有积压红（与 Topic 属性对话框同口径）。 */
private val StatusGreen = Color(0xFF2E7D32)
private val LagOrange = Color(0xFFED6C02)
private val StatusRed = Color(0xFFD32F2F)

/**
 * 消费组列表页（导航项 `groups` 的 Tab 内容）：缓存优先 + 事件驱动的卡片网格。
 * 卡片：组名 + Lag（快扫）/ 成员数（慢扫 best-effort）。
 */
@Composable
fun RocketMqGroupsPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { RocketMqGroupsState(connectionId) }
    val connectionStatus = rememberRocketMqConnectionStatus(connectionId)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var propsGroup by remember { mutableStateOf<RocketMqGroupCardInfo?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    // 「询问 AI」宿主回调：宿主没 Provide（独立预览等）时为 null —— AI 按钮不显示。
    val aiAsk = LocalAiAsk.current
    val showSystem by RocketMqModule.showSystemObjectsFlow(connectionId).collectAsState(false)
    LaunchedEffect(connectionId) { state.load() }
    LaunchedEffect(connectionId, connectionStatus) { state.onStatusChanged(connectionStatus) }
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RocketMqEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onObjectsScanned()
        }
    }
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RocketMqEvents.LAG_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onLagScanned()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactSearchField(
                value = state.search,
                onValueChange = { state.search = it },
                placeholder = stringResource("rocketmq_ui_group_search"),
                    modifier = Modifier.weight(1f),
                )
                ToolbarButton(
                    icon = if (showSystem) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    text = stringResource("rocketmq_ui_group_toggle_system"),
                    onClick = { RocketMqModule.setShowSystemObjects(connectionId, !showSystem) },
                )
                ToolbarButton(
                    icon = Icons.Default.Add,
                text = stringResource("rocketmq_ui_group_add"),
                onClick = { showCreate = true },
            )
            ToolbarButton(
                icon = Icons.Default.Refresh,
                text = stringResource("rocketmq_ui_refresh"),
                onClick = { state.requestScan() },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        LoadStateContent(
            state = state.loadState,
            onRetry = { state.retry() },
            loadingMessage =
                if (connectionStatus == ConnectionStatus.Connecting) {
                    stringResource("rocketmq_ui_connecting")
                } else {
                    stringResource("rocketmq_ui_group_loading")
                },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { _ ->
            ObjectListPanel(
                emptyContent = {
                    ObjectListEmptyState(stringResource("rocketmq_ui_group_empty"))
                },
                items =
                    state.filtered(showSystem).map { info ->
                        // 四角布局：左上组名 / 右上 Lag（>0 橙）/ 左下状态点 + 成员数 /
                        // 右下属性 ⓘ。Lag 与成员数在 service 层已兜底为 0，不再有 null。
                        val lag = info.totalLag ?: 0
                        val members = info.memberCount ?: 0
                        ObjectCardData(
                            id = info.name,
                            title = info.name,
                            typeIcon = Icons.Default.Group,
                            typeLabel = stringResource("rocketmq_ui_nav_groups"),
                            metricValue = stringResource("rocketmq_ui_group_lag").withArg(lag),
                            metricColor = if (lag > 0) LagOrange else null,
                            statusIcon = Icons.Filled.Circle,
                            statusIconTint = when {
                                members > 0 -> StatusGreen
                                members == 0 && lag > 0 -> StatusRed
                                else -> null
                            },
                            statusText = stringResource("rocketmq_ui_group_members").withArg(members),
                            actions = buildList {
                                // AI 绝对最左（与 ibm-mq / kafka / rabbitmq 的拼接顺序一致）。
                                if (aiAsk != null) {
                                    add(
                                        askAiCardAction {
                                            RocketMqAiChainBuilder.buildForGroup(connectionId, info.name)
                                                ?.let(aiAsk::onAsk)
                                        },
                                    )
                                }
                                add(
                                    CardAction(
                                        id = "properties",
                                        icon = Icons.Default.Info,
                                        tooltip = "rocketmq_ui_group_props_action",
                                        onClick = { propsGroup = info },
                                    ),
                                )
                            },
                        )
                    },
                onCardClick = {},
                onCardDoubleClick = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        }

        propsGroup?.let { info ->
            RocketMqGroupPropertiesDialog(
                connectionId = connectionId,
                groupName = info.name,
                onDismiss = { propsGroup = null },
                onDeleted = {
                    propsGroup = null
                    state.requestScan()
                    scope.launch {
                        snackbarHostState.showSnackbar(stringResource("rocketmq_ui_group_deleted"))
                    }
                },
            )
        }

        if (showCreate) {
            RocketMqCreateGroupDialog(
                connectionId = connectionId,
                onDismiss = { showCreate = false },
                onCreated = {
                    showCreate = false
                    state.requestScan()
                    scope.launch {
                        snackbarHostState.showSnackbar(stringResource("rocketmq_ui_group_create_success"))
                    }
                },
            )
        }

        ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
