package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.uicommon.i18n.stringResource

/**
 * 通道列表（导航 `channels` Tab）：复用 [rememberMqObjectListState] 统一加载状态机 +
 * 通用 [ObjectListToolbar] + [ObjectListPanel]，与所有对象类型列表页同一套加载语义
 * （Loading→Success 经事件总线翻转、刷新保留旧数据、空态交给 ObjectListPanel）。
 *
 * 通道无队列深度阈值、无系统/临时隐藏，工具栏只保留搜索 + 刷新。
 */
@Composable
fun ChannelListPanel(
    connectionId: String,
    onOpenProperties: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var searchText by remember { mutableStateOf("") }
    val state = rememberMqObjectListState(
        connectionId = connectionId,
        objectType = MqObjectType.CHANNEL,
        loadFailedText = { e -> stringResource("channel_load_failed").withArg(e.message ?: e::class.simpleName ?: "") },
        scanFailedText = { e -> stringResource("channel_scan_failed").withArg(e.message ?: e::class.simpleName ?: "") },
    )

    LoadStateContent(
        state = state.loadState,
        onRetry = state.retry,
        loadingMessage = stringResource("channel_loading"),
        modifier = Modifier.fillMaxSize(),
        // 空列表的呈现交给 ObjectListPanel（其内部自带空态 + 工具栏），故此处恒返回 false。
        emptyCheck = { false },
        content = { channels ->
            val filtered = channels.filter { it.name.contains(searchText, ignoreCase = true) }
            ObjectListPanel(
                items = filtered.map { channel ->
                    channel.toChannelCardData(
                        actions = ChannelCardActions.create(
                            snapshot = channel,
                            onOpenProperties = { onOpenProperties(channel.name) },
                        ),
                    )
                },
                onCardClick = { card -> onOpenProperties(card.id) },
                onCardDoubleClick = { card -> onOpenProperties(card.id) },
                toolbarContent = {
                    ObjectListToolbar(
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        onRefresh = state.refresh,
                        refreshing = state.isRefreshing,
                        placeholder = stringResource("channel_search_placeholder"),
                        refreshText = stringResource("channel_refresh"),
                    )
                },
                loading = false,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}
