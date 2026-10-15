package com.mqclient.kafka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.kafka.ui.ai.KafkaAiChainBuilder
import com.mqclient.kafka.ui.ai.askAiCardAction
import com.mqclient.mqui.command.ToolbarButton
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.uicommon.LocalAiAsk
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch

/**
 * Topic 列表页（导航项 `topics` 的 Tab 内容）：实时查询 + 卡片网格，复用 mq-ui 的
 * 通用列表组件（CompactSearchField / ToolbarButton / LoadStateContent /
 * ObjectListPanel），与 ibm-mq 的对象列表页同一套视觉语言。
 *
 * 数据口径：全部主题（含 Kafka 内部主题 —— 卡片上以锁图标 + 文案标记，不做开关，
 * 数量不多且一眼可辨）；搜索按名称包含匹配。进入 Tab 自动加载，工具栏「刷新」重拉。
 * 未连接时**自动隐式连接**（宿主侧栏同一行为），期间显示「正在连接…」，失败显示
 * 原因 + 重试 —— 不再出现「请先在右键菜单连接」的引导文案。
 */
@Composable
fun TopicListPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { KafkaTopicsState(connectionId) }
    val scope = rememberCoroutineScope()
    // 「询问 AI」宿主回调：宿主没 Provide（独立预览等）时为 null —— AI 按钮不显示。
    val aiAsk = LocalAiAsk.current
    // 连接状态可观察（侧边栏同源）：进入 Tab 自动加载；未连接/失败 → 隐式连接 +
    // Loading，连接成功/失败状态一变，这里重跑（导航点击的隐式连接由宿主侧栏
    // 触发，本页只负责「状态到了就把界面翻过来」）。
    val connectionStatus = rememberKafkaConnectionStatus(connectionId)
    LaunchedEffect(connectionId, connectionStatus) { state.load() }

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
                icon = Icons.Default.Refresh,
                text = stringResource("kafka_ui_refresh"),
                onClick = { scope.launch { state.load() } },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        LoadStateContent(
            state = state.loadState,
            onRetry = { scope.launch { state.retry() } },
            loadingMessage =
                if (connectionStatus == ConnectionStatus.Connecting) {
                    stringResource("kafka_ui_connecting")
                } else {
                    stringResource("kafka_ui_topic_loading")
                },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { _ ->
            ObjectListPanel(
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
