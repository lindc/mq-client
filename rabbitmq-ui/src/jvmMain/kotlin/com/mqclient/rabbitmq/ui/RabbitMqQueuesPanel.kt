package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.mqui.command.ToolbarButton
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.rabbitmq.RabbitMqEvents
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 队列列表页（导航项 `queues` 的 Tab 内容）：缓存优先 + 事件驱动的卡片网格，与
 * kafka 的 TopicListPanel 同一套视觉语言（CompactSearchField / ToolbarButton /
 * ObjectListPanel）。卡片：队列名 + 状态 / 消息数 / 消费者数（计数走快扫缓存，
 * 未扫到显示 "-"）。第一阶段无卡片操作（消息收发在后续阶段）。
 */
@Composable
fun RabbitMqQueuesPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { RabbitMqQueuesState(connectionId) }
    val snackbarHostState = remember { SnackbarHostState() }
    val connectionStatus = rememberRabbitMqConnectionStatus(connectionId)
    LaunchedEffect(connectionId) { state.load() }
    LaunchedEffect(connectionId, connectionStatus) { state.onStatusChanged(connectionStatus) }
    // 扫描事件驱动：慢扫重建列表 / 快扫刷计数（on(scope = this) 随 effect 离开自动取消）。
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RabbitMqEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onObjectsScanned()
        }
    }
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RabbitMqEvents.STATUS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onStatusScanned()
        }
    }

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
                    placeholder = stringResource("rabbitmq_ui_queue_search"),
                    modifier = Modifier.weight(1f),
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
                        stringResource("rabbitmq_ui_queue_loading")
                    },
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { _ ->
                ObjectListPanel(
                    emptyContent = {
                        ObjectListEmptyState(stringResource("rabbitmq_ui_queue_empty"))
                    },
                    items =
                        state.filtered.map { info ->
                            ObjectCardData(
                                id = info.name,
                                title = info.name,
                                typeIcon = Icons.Default.Forum,
                                typeLabel = stringResource("rabbitmq_ui_nav_queues"),
                                statusText =
                                    listOf(
                                        info.state
                                            ?.let { stringResource("rabbitmq_ui_queue_field_state").withArg(it) },
                                        stringResource("rabbitmq_ui_queue_field_messages")
                                            .withArg(info.messages.orDash()),
                                        stringResource("rabbitmq_ui_queue_field_consumers")
                                            .withArg(info.consumers.orDash()),
                                        if (info.durable) stringResource("rabbitmq_ui_queue_field_durable") else null,
                                    ).filterNotNull()
                                        .joinToString(" · "),
                                actions = emptyList(),
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
}

/** 计数展示（null = 快扫还没跑到该队列）：统一显示 "-" 占位。 */
internal fun Long?.orDash(): String = if (this == null || this < 0) "-" else toString()

internal fun Int?.orDash(): String = if (this == null || this < 0) "-" else toString()
