package com.mqclient.kafka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.mqui.command.ToolbarButton
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch

/**
 * Consumer Group 列表页（导航项 `groups` 的 Tab 内容）：与 [TopicListPanel] 同一
 * 套骨架（实时查询 + 卡片网格 + 搜索），数据换成 listConsumerGroups +
 * describeConsumerGroups。连接观察走 [KafkaPanelGate] 共享层 —— 未连接自动隐式
 * 连接、连接中显示「正在连接…」、失败显示原因 + 重试，与主题页行为一致。
 *
 * 卡片摘要：组状态（Stable / Empty / Dead…，行业术语不翻译）· 成员数；简单组
 * （无分配协议）加锁样式标记。组内主题/位点赞不进 v1 卡片，详情后续再做。
 */
@Composable
fun ConsumerGroupListPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { KafkaConsumerGroupsState(connectionId) }
    val scope = rememberCoroutineScope()
    // 连接状态可观察（侧边栏同源）：进入 Tab 自动加载；状态一变重跑（隐式连接
    // 的发起与宿主侧栏同一行为，本页负责「状态到了就把界面翻过来」）。
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
                placeholder = stringResource("kafka_ui_group_search_placeholder"),
                modifier = Modifier.weight(1f),
            )
            // 受限账号下被跳过的无权组数：轻提示，不算错误（0 时不占位）。
            if (state.skippedCount > 0) {
                Text(
                    text = stringResource("kafka_ui_group_skipped").withArg(state.skippedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    stringResource("kafka_ui_group_loading")
                },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { _ ->
            ObjectListPanel(
                items = state.filtered.map { it.toCardData() },
                onCardClick = {},
                onCardDoubleClick = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Group → 卡片数据：类型图标 + 状态 / 成员数摘要，简单组加锁标记。 */
@Composable
private fun KafkaGroupInfo.toCardData(): ObjectCardData = ObjectCardData(
    id = name,
    title = name,
    typeIcon = Icons.Default.Group,
    typeLabel = stringResource("kafka_ui_nav_groups"),
    statusIcon = if (simple) Icons.Default.Lock else null,
    statusText =
        listOf(
            state,
            stringResource("kafka_ui_group_members").withArg(members),
            if (simple) stringResource("kafka_ui_group_simple") else null,
        ).filterNotNull()
            .joinToString(" · "),
    actions = emptyList(),
)
