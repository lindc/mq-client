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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.kafka.KafkaEvents
import com.mqclient.mqui.command.ToolbarButton
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * Consumer Group 列表页（导航项 `groups` 的 Tab 内容）：与 [TopicListPanel] 同一套
 * 骨架（缓存优先 + 事件驱动 + 卡片网格 + 搜索）：进页读扫描缓存，`kafka.scan.objects.
 * completed` 事件驱动刷新，「刷新」触发一轮手动慢扫。连接观察走 [KafkaPanelGate]
 * 共享层 —— 未连接自动隐式连接、连接中显示「正在连接…」、失败显示原因 + 重试。
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
                placeholder = stringResource("kafka_ui_group_search_placeholder"),
                modifier = Modifier.weight(1f),
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
