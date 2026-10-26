package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.uicommon.command.ToolbarButton
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.rabbitmq.RabbitMqEvents
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.ui.ai.RabbitMqAiChainBuilder
import com.mqclient.rabbitmq.ui.ai.askAiCardAction
import com.mqclient.uicommon.LocalAiAsk
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch

/**
 * 交换机列表页（导航项 `exchanges` 的 Tab 内容）：与 RabbitMqQueuesPanel 同构，
 * 数据只来自对象慢扫（exchanges 无计数字段）。卡片：交换机名 + 类型 / durable /
 * autoDelete（/ internal）；name="" 的默认交换机显示「(AMQP default)」。
 */
@Composable
fun RabbitMqExchangesPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { RabbitMqExchangesState(connectionId) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val connectionStatus = rememberRabbitMqConnectionStatus(connectionId)
    LaunchedEffect(connectionId) { state.load() }
    LaunchedEffect(connectionId, connectionStatus) { state.onStatusChanged(connectionStatus) }
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RabbitMqEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onObjectsScanned()
        }
    }

    // 创建 / 属性两个对话框。
    var showCreate by remember(connectionId) { mutableStateOf(false) }
    var propsExchange by remember(connectionId) { mutableStateOf<RabbitMqExchangeCardInfo?>(null) }
    var sendExchange by remember(connectionId) { mutableStateOf<RabbitMqExchangeCardInfo?>(null) }
    // 「询问 AI」宿主回调：宿主没 Provide（独立预览等）时为 null —— AI 按钮不显示。
    val aiAsk = LocalAiAsk.current

    Box(modifier.fillMaxSize()) {
        Column(modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactSearchField(
                    value = state.search,
                    onValueChange = { state.search = it },
                    placeholder = stringResource("rabbitmq_ui_exchange_search"),
                    modifier = Modifier.weight(1f),
                )
                // 「显示系统对象」开关：默认交换机与 amq.* 的显隐总闸（默认关），
                // 眼睛图标随状态切换（与添加 / 刷新同为扁平工具栏按钮）。
                ToolbarButton(
                    icon = if (state.showSystemObjects) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    text = stringResource("rabbitmq_ui_exchange_toggle_system"),
                    onClick = { state.showSystemObjects = !state.showSystemObjects },
                )
                ToolbarButton(
                    icon = Icons.Default.Add,
                    text = stringResource("rabbitmq_ui_exchange_create"),
                    onClick = { showCreate = true },
                )
                ToolbarButton(
                    icon = Icons.Default.Refresh,
                    text = stringResource("rabbitmq_ui_refresh"),
                    onClick = { state.requestScan() },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            LoadStateContent(
                state = state.loadState,
                onRetry = { state.retry() },
                loadingMessage =
                    if (connectionStatus == ConnectionStatus.Connecting) {
                        stringResource("rabbitmq_ui_connecting")
                    } else {
                        stringResource("rabbitmq_ui_exchange_loading")
                    },
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { _ ->
                ObjectListPanel(
                    emptyContent = {
                        ObjectListEmptyState(stringResource("rabbitmq_ui_exchange_empty"))
                    },
                    items =
                        state.filtered.map { info ->
                            ObjectCardData(
                                id = info.name,
                                title = state.displayName(info),
                                typeIcon = Icons.Default.CallSplit,
                                typeLabel = stringResource("rabbitmq_ui_nav_exchanges"),
                                statusText =
                                    listOf(
                                        stringResource("rabbitmq_ui_exchange_field_type").withArg(info.type),
                                        if (info.durable) stringResource("rabbitmq_ui_exchange_field_durable") else null,
                                        if (info.autoDelete) stringResource("rabbitmq_ui_exchange_field_auto_delete") else null,
                                        if (info.internal) stringResource("rabbitmq_ui_exchange_field_internal") else null,
                                    ).filterNotNull()
                                        .joinToString(" · "),
                                actions =
                                    buildList {
                                        // AI 绝对最左（三家同一拼接约定）；系统交换机
                                        // 也开放（默认交换机是排查路由问题的关键对象）。
                                        if (aiAsk != null) {
                                            add(
                                                askAiCardAction {
                                                    RabbitMqAiChainBuilder.buildForExchange(connectionId, info.name)
                                                        ?.let(aiAsk::onAsk)
                                                },
                                            )
                                        }
                                        // 发送消息：开发辅助，所有交换机可发（默认交换机编址 amq.default）。
                                        add(
                                            CardAction(
                                                id = "send",
                                                icon = Icons.Default.Email,
                                                tooltip = "rabbitmq_ui_send_action",
                                                onClick = { sendExchange = info },
                                            ),
                                        )
                                        // 属性：所有交换机可看（含 Bindings 表与删除入口）。
                                        // 删除统一收拢在属性对话框 footer（默认 / amq.* 隐藏）。
                                        add(
                                            CardAction(
                                                id = "properties",
                                                icon = Icons.Default.Info,
                                                tooltip = "rabbitmq_ui_exchange_props_action",
                                                onClick = { propsExchange = info },
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
        ThemedSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showCreate) {
        RabbitMqCreateExchangeDialog(
            connectionId = connectionId,
            onDismiss = { showCreate = false },
        )
    }

    sendExchange?.let { info ->
        RabbitMqSendMessageDialog(
            connectionId = connectionId,
            exchangeName = info.name,
            displayName = state.displayName(info),
            onDismiss = { sendExchange = null },
        )
    }

    propsExchange?.let { info ->
        RabbitMqExchangePropertiesDialog(
            connectionId = connectionId,
            exchangeName = info.name,
            displayName = state.displayName(info),
            type = info.type,
            durable = info.durable,
            autoDelete = info.autoDelete,
            internal = info.internal,
            onDismiss = { propsExchange = null },
            onDeleted = {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        stringResource("rabbitmq_ui_exchange_delete_done").withArg(info.name),
                    )
                }
            },
        )
    }
}
