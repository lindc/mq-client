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
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.uicommon.command.ToolbarButton
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.mqui.objectlist.ObjectCardIndicator
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.mqui.state.LoadState
import com.mqclient.rabbitmq.RabbitMqEvents
import kotlinx.coroutines.launch
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 队列列表页（导航项 `queues` 的 Tab 内容）：缓存优先 + 事件驱动的卡片网格，与
 * kafka 的 TopicListPanel 同一套视觉语言（CompactSearchField / ToolbarButton /
 * ObjectListPanel）。卡片四角布局（共享 ObjectCard 槽位）：左上队列名 / 右上消息数
 * （积压且无消费者时警告橙）/ 左下状态色点 / 右下消费者指标（People 图标 + 数字，
 * 0 与快扫未到灰、有消费者正常色）+ 操作（查看消息、属性——第一阶段均为 Snackbar
 * 占位）。计数走快扫缓存，未扫到显示 "-"。
 */
@Composable
fun RabbitMqQueuesPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { RabbitMqQueuesState(connectionId) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var propsQueue by remember(connectionId) { mutableStateOf<RabbitMqQueueCardInfo?>(null) }
    var msgQueue by remember(connectionId) { mutableStateOf<RabbitMqQueueCardInfo?>(null) }
    var showCreate by remember(connectionId) { mutableStateOf(false) }
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
    // DLX routing key 跳转留下的「打开队列属性」焦点：列表就绪后消费一次（缓存里
    // 找得到才开；不存在的目标静默丢弃，与「找不到不跳转」的链接判定同一口径）。
    LaunchedEffect(state.loadState) {
        val s = state.loadState
        if (s is LoadState.Success) {
            RabbitMqUiHints.consumeObjectFocus(connectionId, "queue")?.let { name ->
                s.data.firstOrNull { it.name == name }?.let { propsQueue = it }
            }
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
                // 「只看异常队列」过滤 chip：概览健康度 Banner 点击跳转时自动开启。
                ToolbarButton(
                    icon = Icons.Default.FilterList,
                    text = stringResource("rabbitmq_ui_queue_problem_chip"),
                    isActive = state.problemOnly,
                    enabled = state.hasProblemQueues || state.problemOnly,
                    onClick = { state.problemOnly = !state.problemOnly },
                )
                ToolbarButton(
                    icon = Icons.Default.Add,
                    text = stringResource("rabbitmq_ui_queue_create"),
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
                            // 状态色点：running 绿 / idle 灰 / flow·down·crashed 红（危险）/
                            // 未知或快扫未到灰。文字颜色随色点（共享卡片「同色」约定）。
                            // 映射收敛在 rabbitMqQueueStateColor（与拓扑 severity 同口径）。
                            val statusTint = rabbitMqQueueStateColor(info.state)
                            val statusWord = when (info.state) {
                                // flow = 内存/磁盘告警被阻塞，必须直白说出「阻塞」。
                                "flow" -> stringResource("rabbitmq_ui_queue_state_blocked")
                                else -> info.state
                            }
                            // 消息数告警：> 0 且确认无消费者 → 警告橙（积压无人消费）。
                            val backlog = (info.messages ?: 0) > 0 && info.consumers == 0
                            val metricColor = if (backlog) RabbitMqWarningOrange else null
                            ObjectCardData(
                                id = info.name,
                                title = info.name,
                                typeIcon = Icons.Default.Forum,
                                typeLabel = stringResource("rabbitmq_ui_nav_queues"),
                                // 右上角：消息总数（Ready + Unacked）。
                                metricValue = stringResource("rabbitmq_ui_queue_field_messages")
                                    .withArg(info.messages.orDash()),
                                metricColor = metricColor,
                                // 左下：状态色点；running 只留绿点（共享卡 statusText=null
                                // 不渲染文字），idle 灰点灰字，flow/down 红点红字。
                                statusIcon = Icons.Filled.Circle,
                                statusIconTint = statusTint,
                                statusText = statusWord?.takeIf { it != "running" },
                                // 右下：消费者指标（icon + 数字，纯展示）+ 查看消息 / 属性
                                //（第一阶段 Snackbar 占位）。
                                indicators =
                                    listOf(
                                        ObjectCardIndicator(
                                            icon = Icons.Default.People,
                                            label = info.consumers.orDash(),
                                            // 纯数据弱化：统一 60% 次要灰，与可点操作区拉开层级。
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        ),
                                    ),
                                // 积压告警边框：默认态与悬停态都保留（共享卡 alertTint 槽位）。
                                alertTint = if (backlog) RabbitMqWarningOrange.copy(alpha = 0.5f) else null,
                                actions =
                                    listOf(
                                        CardAction(
                                            id = "messages",
                                            icon = Icons.Default.Email,
                                            tooltip = "rabbitmq_ui_queue_action_messages",
                                            onClick = { msgQueue = info },
                                        ),
                                        CardAction(
                                            id = "properties",
                                            icon = Icons.Default.Info,
                                            tooltip = "rabbitmq_ui_queue_action_props",
                                            onClick = { propsQueue = info },
                                        ),
                                    ),
                            )
                        },
                    onCardClick = {},
                    onCardDoubleClick = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // 创建队列对话框（工具栏「+ 创建队列」打开；成功后服务端广播 OBJECTS_SCANNED）。
        if (showCreate) {
            RabbitMqCreateQueueDialog(
                connectionId = connectionId,
                onDismiss = { showCreate = false },
            )
        }

        // 队列属性对话框（卡片 ⓘ 打开；删除成功后回调刷新 + Snackbar）。
        msgQueue?.let { info ->
        RabbitMqQueueMessagesDialog(
            connectionId = connectionId,
            queueName = info.name,
            onDismiss = { msgQueue = null },
        )
    }

    propsQueue?.let { info ->
            RabbitMqQueuePropertiesDialog(
                connectionId = connectionId,
                queueName = info.name,
                onDismiss = { propsQueue = null },
                onDeleted = {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            stringResource("rabbitmq_ui_queue_delete_done").withArg(info.name),
                        )
                    }
                },
            )
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
