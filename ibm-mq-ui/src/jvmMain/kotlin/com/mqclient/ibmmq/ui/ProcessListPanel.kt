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
 * 进程定义列表（导航 `processDefinitions` Tab）：复用 [rememberMqObjectListState] 统一加载状态机 +
 * 通用 [ObjectListToolbar] + [ObjectListPanel]，与所有对象类型列表页同一套加载语义。
 *
 * 进程定义无队列深度阈值、无系统/临时隐藏，工具栏只保留搜索 + 刷新。
 */
@Composable
fun ProcessListPanel(
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
        objectType = MqObjectType.PROCESS,
        loadFailedText = { e -> stringResource("process_definition_load_failed").withArg(e.message ?: e::class.simpleName ?: "") },
        scanFailedText = { e -> stringResource("process_definition_scan_failed").withArg(e.message ?: e::class.simpleName ?: "") },
    )

    LoadStateContent(
        state = state.loadState,
        onRetry = state.retry,
        loadingMessage = stringResource("process_definition_loading"),
        modifier = Modifier.fillMaxSize(),
        // 空列表的呈现交给 ObjectListPanel（其内部自带空态 + 工具栏），故此处恒返回 false。
        emptyCheck = { false },
        content = { processes ->
            val filtered = processes
                .filter { it.name.contains(searchText, ignoreCase = true) }
                .filter { showSystem || !isSystemObject(it.name) }
            ObjectListPanel(
                items = filtered.map { process ->
                    process.toProcessCardData(
                        actions = ProcessCardActions.create(
                            snapshot = process,
                            onOpenProperties = {
                                propertiesDialog = ObjectPropertiesDialogState(
                                    connectionId = connectionId,
                                    objectType = MqObjectType.PROCESS,
                                    objectName = process.name,
                                )
                            },
                        ),
                    )
                },
                onCardClick = { card -> onOpenProperties(card.id) },
                onCardDoubleClick = { card ->
                    propertiesDialog = ObjectPropertiesDialogState(
                        connectionId = connectionId,
                        objectType = MqObjectType.PROCESS,
                        objectName = card.id,
                    )
                },
                toolbarContent = {
                    ObjectListToolbar(
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        onRefresh = state.refresh,
                        refreshing = state.isRefreshing,
                        placeholder = stringResource("process_definition_search_placeholder"),
                        refreshText = stringResource("process_definition_refresh"),
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
