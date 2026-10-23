package com.mqclient.kafka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
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
import com.mqclient.core.concurrent.runCatchingCancellable
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.kafka.KafkaEvents
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.ui.ai.KafkaAiChainBuilder
import com.mqclient.kafka.ui.ai.askAiCardAction
import com.mqclient.uicommon.command.ToolbarButton
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.uicommon.LocalAiAsk
import com.mqclient.uicommon.DangerButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch

/**
 * Topic 列表页（导航项 `topics` 的 Tab 内容）：**缓存优先 + 事件驱动**的卡片网格，
 * 复用 mq-ui 的通用列表组件（CompactSearchField / ToolbarButton / LoadStateContent /
 * ObjectListPanel），与 ibm-mq 的对象列表页同一套视觉语言与消费模式：进页读扫描
 * 缓存，`kafka.scan.objects.completed` 事件驱动刷新，「刷新」触发一轮手动慢扫。
 * 数据口径：扫描任务拉的全部主题（含 Kafka 内部主题 —— 卡片上以锁图标 + 文案标记）；
 * 搜索按名称包含匹配。未连接时自动隐式连接，期间显示「正在连接…」。
 */
@Composable
fun TopicListPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { KafkaTopicsState(connectionId) }
    // 创建主题对话框开关（工具栏按钮 / 空态引导共用）。
    var showCreate by remember(connectionId) { mutableStateOf(false) }
    // 属性对话框的 topic 名；null = 关闭。
    var propsTopic by remember(connectionId) { mutableStateOf<String?>(null) }
    // 消息对话框的 topic 名（卡片 ✉ / 双击打开）；null = 关闭。
    var messagesTopic by remember(connectionId) { mutableStateOf<String?>(null) }
    // 清空消息确认框的 topic 名（卡片 🧹 触发）；null = 关闭。内部主题不显示该动作。
    var clearTopic by remember(connectionId) { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 「询问 AI」宿主回调：宿主没 Provide（独立预览等）时为 null —— AI 按钮不显示。
    val aiAsk = LocalAiAsk.current
    // 进入 Tab 首次加载（组合期一次，含隐式连接兜底）；之后的状态变化走
    // onStatusChanged —— 断开不再被面板静默重连（宿主会收掉断开连接的 Tab）。
    val connectionStatus = rememberKafkaConnectionStatus(connectionId)
    LaunchedEffect(connectionId) { state.load() }
    LaunchedEffect(connectionId, connectionStatus) { state.onStatusChanged(connectionStatus) }
    // 扫描事件驱动：慢扫完成 → 读缓存重建列表（on(scope = this) 随 effect 离开自动取消）。
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(KafkaEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onObjectsScanned()
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
                placeholder = stringResource("kafka_ui_search_placeholder"),
                modifier = Modifier.weight(1f),
            )
            ToolbarButton(
                icon = Icons.Default.Add,
                text = stringResource("kafka_ui_topic_create"),
                onClick = { showCreate = true },
            )
            ToolbarButton(
                icon = Icons.Default.Refresh,
                text = stringResource("kafka_ui_refresh"),
                onClick = { state.requestScan() },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        LoadStateContent(
            state = state.loadState,
            onRetry = { state.retry() },
            loadingMessage =
                if (connectionStatus == ConnectionStatus.Connecting) {
                    stringResource("kafka_ui_connecting")
                } else {
                    stringResource("kafka_ui_topic_loading")
                },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { _ ->
            ObjectListPanel(
                emptyContent = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ObjectListEmptyState(stringResource("kafka_ui_topic_empty"))
                        ToolbarButton(
                            icon = Icons.Default.Add,
                            text = stringResource("kafka_ui_topic_create"),
                            onClick = { showCreate = true },
                        )
                    }
                },
                items =
                    state.filtered.map { info ->
                        info.toCardData(
                            actions = buildList {
                                if (aiAsk != null) {
                                    add(
                                        askAiCardAction {
                                            KafkaAiChainBuilder.build(connectionId, info)?.let(aiAsk::onAsk)
                                        },
                                    )
                                }
                                // 消息对话框（浏览 / 发送）—— 双击卡片同效。
                                add(
                                    CardAction(
                                        id = "messages",
                                        icon = Icons.Default.Email,
                                        tooltip = "kafka_ui_topic_messages",
                                        onClick = { messagesTopic = info.name },
                                    ),
                                )
                                add(
                                    CardAction(
                                        id = "properties",
                                        icon = Icons.Default.Info,
                                        tooltip = "kafka_ui_topic_props",
                                        onClick = { propsTopic = info.name },
                                    ),
                                )
                                // 清空消息（deleteRecords 推位移起点到末端）：破坏性操作，
                                // 走确认框；内部主题（__consumer_offsets 等）绝不可清。
                                if (!info.internal) {
                                    add(
                                        CardAction(
                                            id = "clear_records",
                                            icon = Icons.Default.DeleteSweep,
                                            tooltip = "kafka_ui_topic_clear",
                                            onClick = { clearTopic = info.name },
                                        ),
                                    )
                                }
                            },
                        )
                    },
                onCardClick = {},
                onCardDoubleClick = { card -> messagesTopic = card.id },
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
        KafkaCreateTopicDialog(
            connectionId = connectionId,
            onDismiss = { showCreate = false },
        )
    }

    // Topic 属性对话框：删除成功 → 关对话框 + Snackbar 提示（列表经 scanNow 事件刷新）。
    propsTopic?.let { topicName ->
        KafkaTopicPropertiesDialog(
            connectionId = connectionId,
            topicName = topicName,
            onDismiss = { propsTopic = null },
            onDeleted = {
                scope.launch {
                    snackbarHostState.showSnackbar(stringResource("kafka_ui_topic_props_delete_done"))
                }
            },
        )
    }

    // 消息对话框（卡片 ✉ / 双击）：分区数从列表状态传入，起点选择区的 Partition 下拉用。
    messagesTopic?.let { topicName ->
        KafkaMessagesDialog(
            connectionId = connectionId,
            topicName = topicName,
            partitionCount = state.filtered.firstOrNull { it.name == topicName }?.partitions,
            onDismiss = { messagesTopic = null },
        )
    }

    // 清空消息确认（danger 档）：成功 Snackbar 报分区数；失败显示根因。
    clearTopic?.let { topicName ->
        AlertDialog(
            onDismissRequest = { clearTopic = null },
            title = { Text(stringResource("kafka_ui_topic_clear_confirm_title")) },
            text = {
                Text(
                    text = stringResource("kafka_ui_topic_clear_confirm_text").withArg(topicName),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                DangerButton(
                    onClick = {
                        clearTopic = null
                        scope.launch {
                            runCatchingCancellable {
                                KafkaModule.serviceOrNull()?.clearTopicRecords(connectionId, topicName)
                                    ?: error("服务未初始化")
                            }.onSuccess { count ->
                                snackbarHostState.showSnackbar(
                                    stringResource("kafka_ui_topic_clear_done").withArg(count),
                                )
                            }.onFailure { error ->
                                val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
                                snackbarHostState.showSnackbar(cause.message ?: error::class.simpleName ?: "unknown")
                            }
                        }
                    },
                ) { Text(stringResource("kafka_ui_topic_clear_confirm")) }
            },
            dismissButton = {
                SecondaryButton(
                    onClick = { clearTopic = null },
                ) { Text(stringResource("common_cancel")) }
            },
        )
    }
}

/** Topic → 卡片数据：类型图标 + 分区 / 副本摘要，内部主题加锁标记。 */
@Composable
private fun KafkaTopicInfo.toCardData(actions: List<CardAction> = emptyList()): ObjectCardData = ObjectCardData(
    id = name,
    title = name,
    typeIcon = Icons.Default.Forum,
    typeLabel = stringResource("kafka_ui_nav_topics"),
    statusIcon = if (internal) Icons.Default.Lock else null,
    statusText =
        listOf(
            stringResource("kafka_ui_topic_partitions").withArg(partitions),
            stringResource("kafka_ui_topic_replicas").withArg(replicas),
            if (internal) stringResource("kafka_ui_topic_internal") else null,
        ).filterNotNull()
            .joinToString(" · "),
    actions = actions,
)
