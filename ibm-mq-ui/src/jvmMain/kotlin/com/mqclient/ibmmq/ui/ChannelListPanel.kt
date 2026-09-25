package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.mqui.command.DangerousCommandDialog
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.uicommon.LocalSnackbarHostState
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.launch

/**
 * 通道列表（导航 `channels` Tab）：复用 [rememberMqObjectListState] 统一加载状态机 +
 * 通用 [ObjectListToolbar] + [ObjectListPanel]，与所有对象类型列表页同一套加载语义
 * （Loading→Success 经事件总线翻转、刷新保留旧数据、空态交给 ObjectListPanel）。
 *
 * 通道无队列深度阈值、无系统/临时隐藏，工具栏只保留搜索 + 刷新。
 *
 * 卡片上的【启动 / 停止】走现有 MQSC 命令通道（[IbmMQModule.mqscExecutorOrNull] →
 * `START/STOP CHANNEL` 文本经 `MQCMD_ESCAPE` 下发）：
 *  - 启动直接执行；停止是危险操作，先弹 [DangerousCommandDialog] 二次确认；
 *  - 执行期间对应卡片显示内联转圈并禁用按钮（[executingActionIds]）；
 *  - 结果经 Snackbar 提示（失败带 [com.mqclient.ibmmq.command.MqrcTranslator] 原因），
 *    成功则 [rememberMqObjectListState.refresh] 拉取最新状态。
 */
@Composable
fun ChannelListPanel(
    connectionId: String,
    onOpenProperties: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var searchText by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val state = rememberMqObjectListState(
        connectionId = connectionId,
        objectType = MqObjectType.CHANNEL,
        loadFailedText = { e -> stringResource("channel_load_failed").withArg(e.message ?: e::class.simpleName ?: "") },
        scanFailedText = { e -> stringResource("channel_scan_failed").withArg(e.message ?: e::class.simpleName ?: "") },
    )
    val scope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    var executingNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingStop by remember { mutableStateOf<Pair<String, String>?>(null) }

    suspend fun runMqsc(name: String, command: String, successKey: String) {
        val executor = IbmMQModule.mqscExecutorOrNull() ?: return
        executingNames = executingNames + name
        val result = executor.executeSingle(connectionId, command, 1)
        executingNames = executingNames - name
        val message = if (result.success) {
            stringResource(successKey).withArg(name)
        } else {
            stringResource("operation_failed")
                .withArg(result.errorMessage ?: "MQRC ${result.errorCode ?: "?"}")
        }
        snackbarHostState.showSnackbar(message)
        // 成功才刷新（状态已变）；失败保留原状，不浪费一次全量扫描。
        if (result.success) state.refresh()
    }

    fun executingIdsFor(name: String): Set<String> =
        if (name in executingNames) setOf("channel.start", "channel.stop") else emptySet()

    fun startChannel(name: String) {
        scope.launch { runMqsc(name, "START CHANNEL('$name')", "channel_start_success") }
    }

    fun requestStopChannel(name: String, status: String?) {
        // RETRYING 的通道必须用 FORCE 才能停下；其余用默认的 QUIESCE（优雅停止）。
        val mode = if (status == "RETRYING") "MODE(FORCE)" else "MODE(QUIESCE)"
        pendingStop = name to "STOP CHANNEL('$name') $mode"
    }

    LoadStateContent(
        state = state.loadState,
        onRetry = state.retry,
        loadingMessage = stringResource("channel_loading"),
        modifier = Modifier.fillMaxSize(),
        // 空列表的呈现交给 ObjectListPanel（其内部自带空态 + 工具栏），故此处恒返回 false。
        emptyCheck = { false },
        content = { channels ->
            val filtered = channels
                .filter { it.name.contains(searchText, ignoreCase = true) }
                .filter { showSystem || !isSystemObject(it.name) }
            ObjectListPanel(
                items = filtered.map { channel ->
                    channel.toChannelCardData(
                        actions = ChannelCardActions.create(
                            snapshot = channel,
                            onOpenProperties = { onOpenProperties(channel.name) },
                            onStart = { startChannel(channel.name) },
                            onStop = { requestStopChannel(channel.name, channel.channelStatus) },
                        ),
                        executingActionIds = executingIdsFor(channel.name),
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
                        showSystemToggle = true,
                        showSystem = showSystem,
                        onShowSystemChange = { showSystem = !showSystem },
                    )
                },
                loading = false,
                modifier = Modifier.fillMaxSize(),
            )
            pendingStop?.let { (name, command) ->
                DangerousCommandDialog(
                    command = command,
                    onConfirm = {
                        pendingStop = null
                        scope.launch { runMqsc(name, command, "channel_stop_success") }
                    },
                    onDismiss = { pendingStop = null },
                )
            }
        },
    )
}
