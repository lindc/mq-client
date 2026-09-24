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
 * 服务列表（导航 `services` Tab）：复用 [rememberMqObjectListState] 统一加载状态机 +
 * 通用 [ObjectListToolbar] + [ObjectListPanel]，与所有对象类型列表页同一套加载语义。
 *
 * 服务无队列深度阈值、无系统/临时隐藏，工具栏只保留搜索 + 刷新。
 */
@Composable
fun ServiceListPanel(
    connectionId: String,
    onOpenProperties: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var searchText by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val state = rememberMqObjectListState(
        connectionId = connectionId,
        objectType = MqObjectType.SERVICE,
        loadFailedText = { e -> stringResource("service_load_failed").withArg(e.message ?: e::class.simpleName ?: "") },
        scanFailedText = { e -> stringResource("service_scan_failed").withArg(e.message ?: e::class.simpleName ?: "") },
    )

    LoadStateContent(
        state = state.loadState,
        onRetry = state.retry,
        loadingMessage = stringResource("service_loading"),
        modifier = Modifier.fillMaxSize(),
        // 空列表的呈现交给 ObjectListPanel（其内部自带空态 + 工具栏），故此处恒返回 false。
        emptyCheck = { false },
        content = { services ->
            val filtered = services
                .filter { it.name.contains(searchText, ignoreCase = true) }
                .filter { showSystem || !isSystemObject(it.name) }
            ObjectListPanel(
                items = filtered.map { service ->
                    service.toServiceCardData(
                        actions = ServiceCardActions.create(
                            snapshot = service,
                            onOpenProperties = { onOpenProperties(service.name) },
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
                        placeholder = stringResource("service_search_placeholder"),
                        refreshText = stringResource("service_refresh"),
                        trailingContent = {
                            SystemObjectToggleButton(
                                showSystem = showSystem,
                                onToggle = { showSystem = !showSystem },
                                showText = stringResource("toolbar_show_system"),
                                hideText = stringResource("toolbar_hide_system"),
                            )
                        },
                    )
                },
                loading = false,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}
