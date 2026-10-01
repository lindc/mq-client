package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.pcf.isSystemObject
import com.mqclient.ibmmq.ui.properties.MqObjectPropertiesDialog
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogState
import com.mqclient.mqui.command.DangerousCommandDialog
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.mqui.CommandOutcome
import com.mqclient.mqui.PollOutcome
import com.mqclient.mqui.pollUntil
import com.mqclient.uicommon.LocalSnackbarHostState
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 监听器列表（导航 `listeners` Tab）：复用 [rememberMqObjectListState] 统一加载状态机 +
 * 通用 [ObjectListToolbar] + [ObjectListPanel]，与所有对象类型列表页同一套加载语义。
 *
 * 监听器无队列深度阈值、无系统/临时隐藏，工具栏只保留搜索 + 刷新。
 *
 * 卡片上的【启动 / 停止】走现有 MQSC 命令通道（[IbmMQModule.mqscExecutorOrNull] →
 * `START/STOP LISTENER` 文本经 `MQCMD_ESCAPE` 下发）：
 *  - 启动直接执行；停止是危险操作，先弹 [DangerousCommandDialog] 二次确认；
 *  - 命令返回成功≠状态已变：IBM MQ 的 START/STOP 是异步的，需等监听器真正起来/停下。
 *    因此命令成功后进入**轮询**：每隔 [LISTENER_POLL_INTERVAL_MS] 用
 *    [com.mqclient.ibmmq.IbmMQService.pollListenerStatus] 查一次真实状态，直到达到目标态
 *    （RUNNING / NOT_RUNNING）或 [LISTENER_POLL_TIMEOUT_MS] 超时；
 *  - 轮询期间对应卡片显示内联转圈并禁用按钮（[executingIdsFor]），不显示旧图标；
 *  - 成功经 Snackbar 提示；超时提示「操作已发送，但状态未变更，请手动刷新」；
 *    轮询/查询出错不静默，提示错误原因；状态本身始终取查询到的真实值。
 */

/** 监听器启停后轮询真实状态的间隔（1s）。 */
private const val LISTENER_POLL_INTERVAL_MS = 1_000L

/** 监听器启停后轮询的最长等待（30s）：超过则认为状态未在预期内变更，提示手动刷新。 */
private const val LISTENER_POLL_TIMEOUT_MS = 30_000L

@Composable
fun ListenerListPanel(
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
        objectType = MqObjectType.LISTENER,
        loadFailedText = { e -> stringResource("listener_load_failed").withArg(e.message ?: e::class.simpleName ?: "") },
        scanFailedText = { e -> stringResource("listener_scan_failed").withArg(e.message ?: e::class.simpleName ?: "") },
    )
    val scope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    // 正在执行启停的监听器：name → 目标态（"RUNNING" / "NOT_RUNNING"）。按钮转圈禁用 +
    // 状态文案改显「启动中 / 停止中」都以此为准（命令异步生效，执行期间不显示真实状态）。
    var executingActions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingStop by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 在途轮询任务集合：支持多监听器并发轮询，并在切换连接 / 离开页面时统一取消，
    // 避免对旧连接空轮询或复位错乱。（普通集合即可，无需驱动重组。）
    val pollJobs = remember { mutableSetOf<Job>() }

    // 切换连接或离开页面：取消在途轮询，并复位执行态（避免上一连接的监听器残留转圈）。
    DisposableEffect(connectionId) {
        onDispose {
            pollJobs.forEach { it.cancel() }
            pollJobs.clear()
            executingActions = emptyMap()
        }
    }

    /** 当前状态是否已是目标态：target 为 RUNNING / NOT_RUNNING。 */
    fun isTargetStatus(status: String, target: String): Boolean = when (target) {
        "RUNNING" -> status in ListenerCardActions.RUNNING_LIKE
        "NOT_RUNNING" -> status in ListenerCardActions.STOPPED_LIKE
        else -> false
    }

    /**
     * 执行监听器启停。命令返回成功≠状态已变（IBM MQ 的 START/STOP 异步），故走通用
     * [pollUntil]：执行命令后按 [LISTENER_POLL_INTERVAL_MS] 轮询真实状态
     * （[com.mqclient.ibmmq.IbmMQService.pollListenerStatus]），直到达到 [target] 目标态
     * 或 [LISTENER_POLL_TIMEOUT_MS] 超时。
     *
     *  - 命令失败：提示原因，按钮立即恢复；
     *  - 轮询命中目标态：提示成功，按钮切换为新图标；
     *  - 轮询超时：提示「操作已发送，但状态未在预期时间内变更，请手动刷新」，按钮恢复、状态保留真实值；
     *  - 轮询/查询出错：不静默，提示错误原因。
     *
     * 全程在协程里等待，不阻塞 UI 线程；轮询期间 [executingActions] 含该监听器，
     * 按钮保持「执行中」（禁用 + 转圈），状态文案显示「启动中 / 停止中」，不显示旧图标。
     */
    suspend fun runListenerCommand(name: String, command: String, successKey: String, target: String) {
        val service = IbmMQModule.serviceOrNull() ?: return
        val executor = IbmMQModule.mqscExecutorOrNull() ?: return
        executingActions = executingActions + (name to target)
        try {
            val outcome = pollUntil(
                command = {
                    val result = executor.executeSingle(connectionId, command, 1)
                    CommandOutcome(result.success, result.errorMessage ?: "MQRC ${result.errorCode ?: "?"}")
                },
                query = { service.pollListenerStatus(connectionId, name) },
                isTarget = { isTargetStatus(it, target) },
                intervalMs = LISTENER_POLL_INTERVAL_MS,
                timeoutMs = LISTENER_POLL_TIMEOUT_MS,
            )
            when (outcome) {
                // showSnackbar 挂起到提示关闭才返回——另起协程弹提示，finally 里的按钮
                // 复位不被拖住（注释承诺的「按钮立即恢复」）。
                is PollOutcome.CommandFailed ->
                    scope.launch { snackbarHostState.showSnackbar(stringResource("operation_failed").withArg(outcome.errorMessage ?: "")) }
                is PollOutcome.Reached ->
                    scope.launch { snackbarHostState.showSnackbar(stringResource(successKey).withArg(name)) }
                is PollOutcome.Timeout ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (outcome.lastError != null) {
                                stringResource("listener_operation_timeout_error").withArg(outcome.lastError!!)
                            } else {
                                stringResource("listener_operation_timeout")
                            },
                        )
                    }
            }
        } finally {
            executingActions = executingActions - name
        }
    }

    fun executingIdsFor(name: String): Set<String> =
        if (name in executingActions) setOf("listener.start", "listener.stop") else emptySet()

    fun startListener(name: String) {
        val job = scope.launch {
            runListenerCommand(name, "START LISTENER('$name')", "listener_start_success", "RUNNING")
        }
        pollJobs.add(job)
        job.invokeOnCompletion { pollJobs.remove(job) }
    }

    fun requestStopListener(name: String) {
        pendingStop = name to "STOP LISTENER('$name')"
    }

    LoadStateContent(
        state = state.loadState,
        onRetry = state.retry,
        loadingMessage = stringResource("listener_loading"),
        modifier = Modifier.fillMaxSize(),
        // 空列表的呈现交给 ObjectListPanel（其内部自带空态 + 工具栏），故此处恒返回 false。
        emptyCheck = { false },
        content = { listeners ->
            val filtered = listeners
                .filter { it.name.contains(searchText, ignoreCase = true) }
                .filter { showSystem || !isSystemObject(it.name) }
            ObjectListPanel(
                items = filtered.map { listener ->
                    listener.toListenerCardData(
                        actions = ListenerCardActions.create(
                            snapshot = listener,
                            onOpenProperties = {
                                propertiesDialog = ObjectPropertiesDialogState(
                                    connectionId = connectionId,
                                    objectType = MqObjectType.LISTENER,
                                    objectName = listener.name,
                                )
                            },
                            onStart = { startListener(listener.name) },
                            onStop = { requestStopListener(listener.name) },
                        ),
                        executingActionIds = executingIdsFor(listener.name),
                        executingTarget = executingActions[listener.name],
                    )
                },
                onCardClick = { card -> onOpenProperties(card.id) },
                onCardDoubleClick = { card ->
                    propertiesDialog = ObjectPropertiesDialogState(
                        connectionId = connectionId,
                        objectType = MqObjectType.LISTENER,
                        objectName = card.id,
                    )
                },
                toolbarContent = {
                    ObjectListToolbar(
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        onRefresh = state.refresh,
                        refreshing = state.isRefreshing,
                        placeholder = stringResource("listener_search_placeholder"),
                        refreshText = stringResource("listener_refresh"),
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
                        val job = scope.launch {
                            runListenerCommand(name, command, "listener_stop_success", "NOT_RUNNING")
                        }
                        pollJobs.add(job)
                        job.invokeOnCompletion { pollJobs.remove(job) }
                    },
                    onDismiss = { pendingStop = null },
                )
            }
        },
    )
}
