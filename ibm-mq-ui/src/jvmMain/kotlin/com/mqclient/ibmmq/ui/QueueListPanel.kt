package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mqclient.core.ConnectionStore
import com.mqclient.ibmmq.IbmMQConfig
import com.mqclient.ibmmq.ibmMQConfigFromOptions
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.pcf.isSystemOrTempQueue
import com.mqclient.ibmmq.ui.properties.MqObjectPropertiesDialog
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogState
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 队列列表（导航 `queues` Tab）：复用 [rememberMqObjectListState] 统一加载状态机 +
 * 通用 [ObjectListToolbar]（追加「显示系统」开关）+ [ObjectListPanel]，与所有对象类型
 * 列表页同一套加载语义（Loading→Success 经事件总线翻转、刷新保留旧数据、空态交给
 * ObjectListPanel）。
 *
 * 队列相比其他类型多了两样东西，且都收敛在页面层、不污染共享状态机：
 * - 默认隐藏系统 / 临时队列（SYSTEM.* / AMQ.*），工具栏「显示系统」开关切换；
 * - 队列深度告警阈值（来自连接配置），交给 [toQueueCardData] 决定卡片高亮。
 */
@Composable
fun QueueListPanel(
    connectionId: String,
    onOpenProperties: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var searchText by remember { mutableStateOf("") }
    // 默认隐藏系统 / 临时队列（SYSTEM.* / AMQ.*），列表更清爽；按钮切换显示。
    var showSystem by remember { mutableStateOf(false) }

    // 队列深度告警阈值（%）：从连接配置读取（queueDepthWarningThreshold，默认 85）。
    val warningThreshold = remember(connectionId) {
        ConnectionStore.connections.value.firstOrNull { it.id == connectionId }
            ?.let { ibmMQConfigFromOptions(it.options).queueDepthWarningThreshold }
            ?: IbmMQConfig.DEFAULT_QUEUE_DEPTH_WARNING_THRESHOLD
    }

    // 对象属性对话框（双击卡片打开；关闭即清空状态）。
    var propertiesDialog by remember(connectionId) { mutableStateOf<ObjectPropertiesDialogState?>(null) }
    propertiesDialog?.let { dialogState ->
        MqObjectPropertiesDialog(state = dialogState, onDismiss = { propertiesDialog = null })
    }

    // 消息处理对话框（本地队列卡片 ✉ 按钮打开；浏览页已实装，发送页待实装）。
    var messagesDialogQueue by remember(connectionId) { mutableStateOf<String?>(null) }
    messagesDialogQueue?.let { queueName ->
        QueueMessagesDialog(
            connectionId = connectionId,
            queueName = queueName,
            onDismiss = { messagesDialogQueue = null },
        )
    }

    val state = rememberMqObjectListState(
        connectionId = connectionId,
        objectType = MqObjectType.QUEUE,
        loadFailedText = { e -> stringResource("queue_load_failed").withArg(e.message ?: e::class.simpleName ?: "") },
        scanFailedText = { e -> stringResource("queue_scan_failed").withArg(e.message ?: e::class.simpleName ?: "") },
    )

    LoadStateContent(
        state = state.loadState,
        onRetry = state.retry,
        loadingMessage = stringResource("queue_loading"),
        modifier = Modifier.fillMaxSize(),
        // 空列表的呈现交给 ObjectListPanel（其内部自带空态 + 工具栏，
        // 「显示系统」开关在空结果下仍需可用），故此处恒返回 false。
        emptyCheck = { false },
        content = { queues ->
            // 过滤 + 卡片数据构建较重（每张卡新建 action lambda，ObjectCardData 恒不等），
            // remember 隔离：搜索敲键 / isRefreshing 翻转之外不重建，网格不跟随全量重组。
            val filtered = remember(queues, searchText, showSystem, warningThreshold) {
                queues
                    .filter { q -> q.name.contains(searchText, ignoreCase = true) }
                    .filter { q -> showSystem || !isSystemOrTempQueue(q.name) }
                    .map { queue ->
                        queue.toQueueCardData(
                            warningThreshold = warningThreshold,
                            // 拓扑查看在最前，属性按钮恒在最后（各对象类型尾按钮一致）。
                            actions = listOf(topologyViewAction(connectionId, queue.name)) +
                                QueueCardActions.create(
                                    snapshot = queue,
                                    onOpenProperties = {
                                propertiesDialog = ObjectPropertiesDialogState(
                                    connectionId = connectionId,
                                    objectType = MqObjectType.QUEUE,
                                    objectName = queue.name,
                                )
                            },
                                    onOpenMessages = { messagesDialogQueue = queue.name },
                                ),
                        )
                    }
            }

            ObjectListPanel(
                items = filtered,
                onCardClick = { card -> onOpenProperties(card.id) },
                onCardDoubleClick = { card ->
                    propertiesDialog = ObjectPropertiesDialogState(
                        connectionId = connectionId,
                        objectType = MqObjectType.QUEUE,
                        objectName = card.id,
                    )
                },
                toolbarContent = {
                    ObjectListToolbar(
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        onRefresh = state.refresh,
                        refreshing = state.isRefreshing,
                        placeholder = stringResource("queue_search_placeholder"),
                        refreshText = stringResource("queue_refresh"),
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
