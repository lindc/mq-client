package com.mqclient.rocketmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.mqclient.rocketmq.RocketMqTopicSnapshot
import com.mqclient.rocketmq.ui.RocketMqTopicPropertiesDialog
import com.mqclient.rocketmq.ui.ai.RocketMqAiChainBuilder
import com.mqclient.rocketmq.ui.ai.askAiCardAction
import kotlinx.coroutines.launch
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.LocalAiAsk
import com.mqclient.uicommon.command.ToolbarButton
import com.mqclient.uicommon.i18n.stringResource

/**
 * 主题列表页（导航项 `topics` 的 Tab 内容）：缓存优先 + 事件驱动的卡片网格。
 * 卡片：主题名 + 读/写队列数 + 路由权限（慢扫逐 topic 路由的投影）；操作：属性
 * 对话框（概览 + 路由信息 + 删除）。
 */
@Composable
fun RocketMqTopicsPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { RocketMqTopicsState(connectionId) }
    val connectionStatus = rememberRocketMqConnectionStatus(connectionId)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var propsTopic by remember { mutableStateOf<RocketMqTopicSnapshot?>(null) }
    var messagesTopic by remember { mutableStateOf<String?>(null) }
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
                    placeholder = stringResource("rocketmq_ui_topic_search"),
                    modifier = Modifier.weight(1f),
                )
                ToolbarButton(
                    icon = if (showSystem) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    text = stringResource("rocketmq_ui_topic_toggle_system"),
                    onClick = { RocketMqModule.setShowSystemObjects(connectionId, !showSystem) },
                )
                ToolbarButton(
                    icon = Icons.Default.Add,
                    text = stringResource("rocketmq_ui_topic_add"),
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
                        stringResource("rocketmq_ui_topic_loading")
                    },
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { _ ->
                ObjectListPanel(
                    emptyContent = {
                        ObjectListEmptyState(stringResource("rocketmq_ui_topic_empty"))
                    },
                    items =
                        state.filtered(showSystem).map { topic ->
                            // 精简卡片：只留图标 + 名称（队列/权限详情在属性对话框）；
                            // 右下 ✉️ = 消息处理入口（浏览/发送双 Tab 对话框）。
                            ObjectCardData(
                        id = topic.name,
                        title = topic.name,
                        typeIcon = Icons.Default.Description,
                        typeLabel = stringResource("rocketmq_ui_nav_topics"),
                            actions = buildList {
                                // AI 绝对最左（与 ibm-mq / kafka / rabbitmq 的拼接顺序一致）。
                                if (aiAsk != null) {
                                    add(
                                        askAiCardAction {
                                            RocketMqAiChainBuilder.buildForTopic(connectionId, topic.name)
                                                ?.let(aiAsk::onAsk)
                                        },
                                    )
                                }
                                add(
                                    CardAction(
                                        id = "messages",
                                        icon = Icons.Default.Email,
                                        tooltip = "rocketmq_ui_topic_action_messages",
                                        onClick = { messagesTopic = topic.name },
                                    ),
                                )
                                add(
                                    CardAction(
                                        id = "properties",
                                        icon = Icons.Default.Info,
                                        tooltip = "rocketmq_ui_topic_props_action",
                                        onClick = { propsTopic = topic },
                                    ),
                                )
                            },
                            )
                        },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        propsTopic?.let { topic ->
            RocketMqTopicPropertiesDialog(
                connectionId = connectionId,
                topicName = topic.name,
                onDismiss = { propsTopic = null },
                onDeleted = { state.requestScan() },
            )
        }

        // 消息对话框（卡片 ✉）：浏览 + 发送双 Tab，外壳与 KafkaMessagesDialog 同构。
        messagesTopic?.let { topicName ->
            RocketMqMessagesDialog(
                connectionId = connectionId,
                topicName = topicName,
                onDismiss = { messagesTopic = null },
            )
        }

        if (showCreate) {
            RocketMqCreateTopicDialog(
                connectionId = connectionId,
                onDismiss = { showCreate = false },
                onCreated = {
                    showCreate = false
                    state.requestScan()
                    scope.launch {
                        snackbarHostState.showSnackbar(stringResource("rocketmq_ui_topic_create_success"))
                    }
                },
            )
        }

        ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** 路由权限位 → 可读文本（6 = 读写、4 = 只写、2 = 只读）。 */
@Composable
internal fun formatPerm(perm: Int): String = when {
    perm and 6 == 6 -> stringResource("rocketmq_ui_topic_perm_rw")
    perm and 4 == 4 -> stringResource("rocketmq_ui_topic_perm_w")
    perm and 2 == 2 -> stringResource("rocketmq_ui_topic_perm_r")
    else -> stringResource("rocketmq_ui_topic_perm_none")
}
