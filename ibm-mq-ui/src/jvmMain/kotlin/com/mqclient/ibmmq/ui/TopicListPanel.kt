package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.ui.properties.MqObjectPropertiesDialog
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogState
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 主题列表（导航 `topics` Tab）：复用 [rememberMqObjectListState] 统一加载状态机 +
 * 通用 [ObjectListToolbar] + [ObjectListPanel]，与所有对象类型列表页同一套加载语义。
 *
 * 主题无队列深度阈值、无系统/临时隐藏，工具栏只保留搜索 + 刷新。
 */
@Composable
fun TopicListPanel(
    connectionId: String,
    onOpenProperties: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var searchText by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    // 对象属性对话框（双击卡片打开；关闭即清空状态）。
    var propertiesDialog by remember(connectionId) { mutableStateOf<ObjectPropertiesDialogState?>(null) }
    propertiesDialog?.let { dialogState ->
        MqObjectPropertiesDialog(state = dialogState, onDismiss = { propertiesDialog = null })
    }

    val state = rememberMqObjectListState(
        connectionId = connectionId,
        objectType = MqObjectType.TOPIC,
        loadFailedText = { e -> stringResource("topic_load_failed").withArg(e.message ?: e::class.simpleName ?: "") },
        scanFailedText = { e -> stringResource("topic_scan_failed").withArg(e.message ?: e::class.simpleName ?: "") },
    )

    LoadStateContent(
        state = state.loadState,
        onRetry = state.retry,
        loadingMessage = stringResource("topic_loading"),
        modifier = Modifier.fillMaxSize(),
        // 空列表的呈现交给 ObjectListPanel（其内部自带空态 + 工具栏），故此处恒返回 false。
        emptyCheck = { false },
        content = { topics ->
            val filtered = topics
                .filter { it.name.contains(searchText, ignoreCase = true) }
                .filter { showSystem || !isSystemObject(it.name) }
            ObjectListPanel(
                items = filtered.map { topic ->
                    topic.toTopicCardData(
                        // 拓扑查看在最前，属性按钮恒在最后（各对象类型尾按钮一致）。
                        actions = listOf(topologyViewAction(connectionId, topic.name)) +
                            TopicCardActions.create(
                                snapshot = topic,
                                onOpenProperties = {
                                propertiesDialog = ObjectPropertiesDialogState(
                                    connectionId = connectionId,
                                    objectType = MqObjectType.TOPIC,
                                    objectName = topic.name,
                                )
                            },
                            ),
                    )
                },
                onCardClick = { card -> onOpenProperties(card.id) },
                onCardDoubleClick = { card ->
                    propertiesDialog = ObjectPropertiesDialogState(
                        connectionId = connectionId,
                        objectType = MqObjectType.TOPIC,
                        objectName = card.id,
                    )
                },
                toolbarContent = {
                    ObjectListToolbar(
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        onRefresh = state.refresh,
                        refreshing = state.isRefreshing,
                        placeholder = stringResource("topic_search_placeholder"),
                        refreshText = stringResource("topic_refresh"),
                        showSystemToggle = true,
                        showSystem = showSystem,
                        onShowSystemChange = { showSystem = !showSystem },
                    )
                },
                loading = false,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}
