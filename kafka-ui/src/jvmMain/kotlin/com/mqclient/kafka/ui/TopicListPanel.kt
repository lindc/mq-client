package com.mqclient.kafka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.kafka.KafkaEvents
import com.mqclient.kafka.ui.ai.KafkaAiChainBuilder
import com.mqclient.kafka.ui.ai.askAiCardAction
import com.mqclient.mqui.command.ToolbarButton
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.uicommon.LocalAiAsk
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

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
                            actions =
                                if (aiAsk != null) {
                                    listOf(
                                        askAiCardAction {
                                            KafkaAiChainBuilder.build(connectionId, info)?.let(aiAsk::onAsk)
                                        },
                                    )
                                } else {
                                    emptyList()
                                },
                        )
                    },
                onCardClick = {},
                onCardDoubleClick = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showCreate) {
        KafkaCreateTopicDialog(
            connectionId = connectionId,
            onDismiss = { showCreate = false },
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
