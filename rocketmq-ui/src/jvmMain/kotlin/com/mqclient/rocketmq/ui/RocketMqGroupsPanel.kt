package com.mqclient.rocketmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
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
import com.mqclient.uicommon.command.ToolbarButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

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
                placeholder = stringResource("rocketmq_ui_group_search"),
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
                    stringResource("rocketmq_ui_group_loading")
                },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { _ ->
            ObjectListPanel(
                emptyContent = {
                    ObjectListEmptyState(stringResource("rocketmq_ui_group_empty"))
                },
                items =
                    state.filtered.map { info ->
                        ObjectCardData(
                            id = info.name,
                            title = info.name,
                            typeIcon = Icons.Default.Group,
                            typeLabel = stringResource("rocketmq_ui_nav_groups"),
                            statusText =
                                listOf(
                                    stringResource("rocketmq_ui_group_lag").withArg(
                                        info.totalLag?.toString() ?: "-",
                                    ),
                                    stringResource("rocketmq_ui_group_members").withArg(
                                        info.memberCount?.toString() ?: "-",
                                    ),
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
