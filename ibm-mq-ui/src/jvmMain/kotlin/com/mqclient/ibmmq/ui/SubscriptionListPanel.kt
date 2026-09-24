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
 * 订阅列表（导航 `subscriptions` Tab）：复用 [rememberMqObjectListState] 统一加载状态机 +
 * 通用 [ObjectListToolbar] + [ObjectListPanel]，与所有对象类型列表页同一套加载语义。
 *
 * 订阅无队列深度阈值、无系统/临时隐藏，工具栏只保留搜索 + 刷新。
 */
@Composable
fun SubscriptionListPanel(
    connectionId: String,
    onOpenProperties: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var searchText by remember { mutableStateOf("") }
    val state = rememberMqObjectListState(
        connectionId = connectionId,
        objectType = MqObjectType.SUBSCRIPTION,
        loadFailedText = { e -> stringResource("subscription_load_failed").withArg(e.message ?: e::class.simpleName ?: "") },
        scanFailedText = { e -> stringResource("subscription_scan_failed").withArg(e.message ?: e::class.simpleName ?: "") },
    )

    LoadStateContent(
        state = state.loadState,
        onRetry = state.retry,
        loadingMessage = stringResource("subscription_loading"),
        modifier = Modifier.fillMaxSize(),
        // 空列表的呈现交给 ObjectListPanel（其内部自带空态 + 工具栏），故此处恒返回 false。
        emptyCheck = { false },
        content = { subscriptions ->
            val filtered = subscriptions.filter { it.name.contains(searchText, ignoreCase = true) }
            ObjectListPanel(
                items = filtered.map { subscription ->
                    subscription.toSubscriptionCardData(
                        actions = SubscriptionCardActions.create(
                            snapshot = subscription,
                            onOpenProperties = { onOpenProperties(subscription.name) },
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
                        placeholder = stringResource("subscription_search_placeholder"),
                        refreshText = stringResource("subscription_refresh"),
                    )
                },
                loading = false,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}
