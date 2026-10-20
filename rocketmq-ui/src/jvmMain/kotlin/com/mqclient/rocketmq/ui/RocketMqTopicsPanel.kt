package com.mqclient.rocketmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.rocketmq.RocketMqEvents
import com.mqclient.rocketmq.RocketMqTopicSnapshot
import com.mqclient.uicommon.command.ToolbarButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 主题列表页（导航项 `topics` 的 Tab 内容）：缓存优先 + 事件驱动的卡片网格。
 * 卡片：主题名 + 读写队列数 / 路由权限（慢扫逐 topic 路由的投影）。
 */
@Composable
fun RocketMqTopicsPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { RocketMqTopicsState(connectionId) }
    val connectionStatus = rememberRocketMqConnectionStatus(connectionId)
    LaunchedEffect(connectionId) { state.load() }
    LaunchedEffect(connectionId, connectionStatus) { state.onStatusChanged(connectionStatus) }
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RocketMqEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onObjectsScanned()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
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
                    state.filtered.map { topic ->
                        ObjectCardData(
                            id = topic.name,
                            title = topic.name,
                            typeIcon = Icons.Default.Description,
                            typeLabel = stringResource("rocketmq_ui_nav_topics"),
                            statusText =
                                listOf(
                                    stringResource("rocketmq_ui_topic_read_queues").withArg(topic.readQueueNums),
                                    stringResource("rocketmq_ui_topic_write_queues").withArg(topic.writeQueueNums),
                                    stringResource("rocketmq_ui_topic_perm").withArg(formatPerm(topic.perm)),
                                ).joinToString(" · "),
                            actions = emptyList(),
                        )
                    },
                onCardClick = {},
                onCardDoubleClick = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
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

