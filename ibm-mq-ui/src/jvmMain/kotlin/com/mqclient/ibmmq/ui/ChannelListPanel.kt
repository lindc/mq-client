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
import com.mqclient.ibmmq.ui.ai.askAiCardAction
import com.mqclient.ibmmq.ui.ai.askAiClick
import com.mqclient.ibmmq.ui.ai.isCardAiTarget
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogHost
import com.mqclient.ibmmq.ui.properties.rememberObjectPropertiesDialogController
import com.mqclient.mqui.command.DangerousCommandDialog
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.mqui.CommandOutcome
import com.mqclient.mqui.PollOutcome
import com.mqclient.mqui.pollUntil
import com.mqclient.mqui.common.rememberDebouncedValue
import com.mqclient.uicommon.LocalAiAsk
import com.mqclient.uicommon.LocalSnackbarHostState
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.Job
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
 *  - 命令返回成功≠状态已变：IBM MQ 的 STOP/START 是异步的，需等通道真正停下/起来。
 *    因此命令成功后进入**轮询**：每隔 [CHANNEL_POLL_INTERVAL_MS] 用
 *    [com.mqclient.ibmmq.IbmMQService.pollChannelStatus] 查一次真实状态，直到达到目标态
 *    （STOPPED / RUNNING）或 [CHANNEL_POLL_TIMEOUT_MS] 超时；
 *  - 轮询期间对应卡片显示内联转圈并禁用按钮（[executingIdsFor]），不显示旧图标；
 *  - 成功经 Snackbar 提示；超时提示「操作已发送，但状态未变更，请手动刷新」；
 *    轮询/查询出错不静默，提示错误原因；状态本身始终取查询到的真实值。
 */

/** 通道启停后轮询真实状态的间隔（1s）。 */
private const val CHANNEL_POLL_INTERVAL_MS = 1_000L

/** 通道启停后轮询的最长等待（30s）：超过则认为状态未在预期内变更，提示手动刷新。 */
private const val CHANNEL_POLL_TIMEOUT_MS = 30_000L

@Composable
fun ChannelListPanel(
    connectionId: String,
    onOpenProperties: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var searchText by remember { mutableStateOf("") }
    // 防抖：输入框立即回显，过滤用 300ms 后生效的值（通道多时逐键过滤会一直重建卡片）。
    val appliedSearch = rememberDebouncedValue(searchText)
    var showSystem by remember { mutableStateOf(false) }
    // 对象属性对话框（属性按钮打开；统一入口持有状态与渲染）。
    val propertiesController = rememberObjectPropertiesDialogController(connectionId)
    ObjectPropertiesDialogHost(propertiesController)

    val state = rememberMqObjectListState(
        connectionId = connectionId,
        objectType = MqObjectType.CHANNEL,
        loadFailedText = { e -> stringResource("ibm_mq_channel_load_failed").withArg(e.message ?: e::class.simpleName ?: "") },
        scanFailedText = { e -> stringResource("ibm_mq_channel_scan_failed").withArg(e.message ?: e::class.simpleName ?: "") },
    )
    val scope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    // 正在执行启停的通道：name → 目标态（"RUNNING" / "STOPPED"）。按钮转圈禁用 +
    // 状态文案改显「启动中 / 停止中」都以此为准（命令异步生效，执行期间不显示真实状态）。
    var executingActions by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingStop by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 在途轮询任务集合：支持多通道并发轮询，并在切换连接 / 离开页面时统一取消，
    // 避免对旧连接空轮询或复位错乱。（普通集合即可，无需驱动重组。）
    val pollJobs = remember { mutableSetOf<Job>() }

    // 切换连接或离开页面：取消在途轮询，并复位执行态（避免上一连接的通道残留转圈）。
    DisposableEffect(connectionId) {
        onDispose {
            pollJobs.forEach { it.cancel() }
            pollJobs.clear()
            executingActions = emptyMap()
        }
    }

    /** 当前状态是否已是目标态：target 为 RUNNING / STOPPED。 */
    fun isTargetStatus(status: String, target: String): Boolean = when (target) {
        "RUNNING" -> status in ChannelCardActions.RUNNING_LIKE
        "STOPPED" -> status in ChannelCardActions.STOPPED_LIKE
        else -> false
    }

    /**
     * 执行通道启停。命令返回成功≠状态已变（IBM MQ 的 STOP/START 异步），故走通用
     * [pollUntil]：执行命令后按 [CHANNEL_POLL_INTERVAL_MS] 轮询真实状态
     * （[com.mqclient.ibmmq.IbmMQService.pollChannelStatus]），直到达到 [target] 目标态
     * 或 [CHANNEL_POLL_TIMEOUT_MS] 超时。
     *
     *  - 命令失败：提示原因，按钮立即恢复；
     *  - 轮询命中目标态：提示成功，按钮切换为新图标；
     *  - 轮询超时：提示「操作已发送，但状态未在预期时间内变更，请手动刷新」，按钮恢复、状态保留真实值；
     *  - 轮询/查询出错：不静默，提示错误原因。
     *
     * 全程在协程里等待，不阻塞 UI 线程；轮询期间 [executingActions] 含该通道，
     * 按钮保持「执行中」（禁用 + 转圈），状态文案显示「启动中 / 停止中」，不显示旧图标。
     */
    suspend fun runChannelCommand(name: String, command: String, successKey: String, target: String) {
        val service = IbmMQModule.serviceOrNull() ?: return
        val executor = IbmMQModule.mqscExecutorOrNull() ?: return
        executingActions = executingActions + (name to target)
        try {
            val outcome = pollUntil(
                command = {
                    val result = executor.executeSingle(connectionId, command, 1)
                    CommandOutcome(result.success, mqrcUserMessage(result.errorCode, result.errorMessage))
                },
                query = { service.pollChannelStatus(connectionId, name) },
                isTarget = { isTargetStatus(it, target) },
                intervalMs = CHANNEL_POLL_INTERVAL_MS,
                timeoutMs = CHANNEL_POLL_TIMEOUT_MS,
            )
            when (outcome) {
                // showSnackbar 挂起到提示关闭才返回——另起协程弹提示，finally 里的按钮
                // 复位不被拖住（注释承诺的「按钮立即恢复」）。
                is PollOutcome.CommandFailed ->
                    scope.launch { snackbarHostState.showSnackbar(stringResource("ibm_mq_operation_failed").withArg(outcome.errorMessage ?: "")) }
                is PollOutcome.Reached ->
                    scope.launch { snackbarHostState.showSnackbar(stringResource(successKey).withArg(name)) }
                is PollOutcome.Timeout -> {
                    // lastError 可空：先绑定到 when 分支里的局部 val —— 既不需要 `!!`，
                    // 也不依赖跨协程的 smart-cast（outcome 本身是不可变的，但把非空
                    // 判定挪进 launch 之后编译器就不再保证）。
                    val lastError = outcome.lastError
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (lastError != null) {
                                stringResource("ibm_mq_channel_operation_timeout_error").withArg(lastError)
                            } else {
                                stringResource("ibm_mq_channel_operation_timeout")
                            },
                        )
                    }
                }
            }
        } finally {
            executingActions = executingActions - name
        }
    }

    fun executingIdsFor(name: String): Set<String> =
        if (name in executingActions) setOf("channel.start", "channel.stop") else emptySet()

    fun startChannel(name: String) {
        val job = scope.launch {
            runChannelCommand(name, "START CHANNEL('$name')", "ibm_mq_channel_start_success", "RUNNING")
        }
        pollJobs.add(job)
        job.invokeOnCompletion { pollJobs.remove(job) }
    }

    fun requestStopChannel(name: String, status: String?) {
        // RETRYING 的通道必须用 FORCE 才能停下；其余用默认的 QUIESCE（优雅停止）。
        val mode = if (status == "RETRYING") "MODE(FORCE)" else "MODE(QUIESCE)"
        pendingStop = name to "STOP CHANNEL('$name') $mode"
    }

    LoadStateContent(
        state = state.loadState,
        onRetry = state.retry,
        loadingMessage = stringResource("ibm_mq_channel_loading"),
        modifier = Modifier.fillMaxSize(),
        // 空列表的呈现交给 ObjectListPanel（其内部自带空态 + 工具栏），故此处恒返回 false。
        emptyCheck = { false },
        content = { channels ->
            // 「询问 AI」宿主入口（null = 宿主未提供，卡片不显示 AI 图标）。
            val aiAsk = LocalAiAsk.current
            val filtered = channels
                .filter { it.name.contains(appliedSearch, ignoreCase = true) }
                .filter { showSystem || !isSystemObject(it.name) }
            ObjectListPanel(
                items = filtered.map { channel ->
                    channel.toChannelCardData(
                        // 图标区顺序：AI（绝对最左）→ 拓扑查看 → 启停 → 属性
                        //（属性按钮恒在最后，各对象类型尾按钮一致）。
                        actions = buildList {
                            aiAsk?.let {
                                val onAsk = askAiClick(connectionId, MqObjectType.CHANNEL, channel.name, it::onAsk)
                                if (isCardAiTarget(MqObjectType.CHANNEL, channel)) add(askAiCardAction(onAsk))
                            }
                            add(topologyViewAction(connectionId, channel.name))
                            addAll(
                                ChannelCardActions.create(
                                    snapshot = channel,
                                    onOpenProperties = {
                                        propertiesController.open(MqObjectType.CHANNEL, channel.name)
                                    },
                                    onStart = { startChannel(channel.name) },
                                    onStop = { requestStopChannel(channel.name, channel.channelStatus) },
                                ),
                            )
                        },
                        executingActionIds = executingIdsFor(channel.name),
                        executingTarget = executingActions[channel.name],
                    )
                },
                toolbarContent = {
                    ObjectListToolbar(
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        onRefresh = state.refresh,
                        refreshing = state.isRefreshing,
                        placeholder = stringResource("ibm_mq_channel_search_placeholder"),
                        refreshText = stringResource("ibm_mq_channel_refresh"),
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
                            runChannelCommand(name, command, "ibm_mq_channel_stop_success", "STOPPED")
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
