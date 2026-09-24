package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStore
import com.mqclient.core.CoreModule
import com.mqclient.ibmmq.IbmMQConfig
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.ibmMQConfigFromOptions
import com.mqclient.ibmmq.event.IbmMQEvents
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.mqui.command.ToolbarButton
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadState
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.uicommon.LocalSnackbarHostState
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 占位宽度：搜索框与刷新按钮之间的间距。 */
private val ToolbarSpacing = 8.dp

/** 初始 / 重试加载时 Loading 至少可见的时长，避免缓存命中过快导致进度圈"闪不过去"。 */
private const val MIN_LOADING_MS = 400L

/**
 * 队列列表（导航 `queues` Tab 的内容）：接 mq-ui 通用 [ObjectListPanel]。
 *
 * 数据姿势与 QMGR 属性面板一致：直接经 [IbmMQModule.serviceOrNull] 调
 * ibm-mq-service。队列快照来自**扫描缓存**（[IbmMQService.cachedObjects]，只读
 * 不碰 MQ）；「刷新」按钮触发 [IbmMQService.scanObjects] 重扫，扫描完成的事件
 * （OBJECTS / MESSAGES / SCAN_COMPLETED）驱动列表自动刷新 —— 手动与定时扫描
 * 共享同一条响应链路。
 *
 * 尚未做的事：队列详情 Tab（双击目标）与队列属性 Dialog——单击 / 双击经
 * [onOpenProperties] / [onOpenMessages] 回调留给宿主；操作按钮目前有
 * 属性（全部队列）与消息处理（仅普通本地队列），回调由宿主提供（暂为空动作）。
 */
@Composable
fun QueueListPanel(
    connectionId: String,
    onOpenProperties: (String) -> Unit = {},
    onOpenMessages: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 加载状态收拢为单一 LoadState：初始 Loading（记住连接 id，切换连接自动重置）。
    var loadState by remember(connectionId) {
        mutableStateOf<LoadState<List<MqObjectSnapshot>>>(LoadState.Loading)
    }
    // 刷新（保留旧数据）时的小指示器；与整页 Loading 区分开。
    var isRefreshing by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    // 默认隐藏系统 / 临时队列（SYSTEM.* / AMQ.*），列表更清爽；按钮切换显示。
    var showSystemQueues by remember { mutableStateOf(false) }

    // 队列深度告警阈值（%）：从连接配置读取（queueDepthWarningThreshold，默认 85）。
    val warningThreshold = remember(connectionId) {
        ConnectionStore.connections.value.firstOrNull { it.id == connectionId }
            ?.let { ibmMQConfigFromOptions(it.options).queueDepthWarningThreshold }
            ?: IbmMQConfig.DEFAULT_QUEUE_DEPTH_WARNING_THRESHOLD
    }

    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    /** 保证 Loading 至少渲染一帧：缓存命中 / 扫描过快时 Loading→Success 会被同帧合并，进度圈闪不过去。 */
    suspend fun delayUntilMin(start: Long) {
        val elapsed = System.currentTimeMillis() - start
        if (elapsed < MIN_LOADING_MS) delay(MIN_LOADING_MS - elapsed)
    }

    /** 初次 / 重试加载：置 Loading → 读缓存（空则扫一轮），由缓存是否就绪决定如何落地。 */
    suspend fun loadInitial() {
        loadState = LoadState.Loading
        val start = System.currentTimeMillis()
        val service = IbmMQModule.serviceOrNull() ?: run {
            // 模块未就绪：保持 Loading 等事件总线（理论上不会到这，contentFor 已拦过）。
            delayUntilMin(start)
            return
        }

        // 热缓存：直接落地，避免无谓的 Loading 闪烁。
        val cached = service.cachedObjects(connectionId, MqObjectType.QUEUE)
        if (cached.isNotEmpty()) {
            delayUntilMin(start)
            loadState = LoadState.Success(cached)
            return
        }

        // 冷缓存：触发扫描。扫描成功结束后仍读一次缓存——
        // 若连接此刻已就绪，scanObjects 会真正扫完并写缓存，这里能拿到数据；
        // 若连接尚未就绪，scanObjects 直接 no-op（connectedContext 为 null），缓存仍空，
        // 则保持 Loading，由连接就绪后真正的扫描经事件总线翻 Success（不提交空态，避免空白窗体）。
        runCatching { service.scanObjects(connectionId) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                delayUntilMin(start)
                loadState = LoadState.Error(
                    stringResource("queue_load_failed").withArg(error.message ?: error::class.simpleName ?: ""),
                    error,
                )
                return
            }

        val after = service.cachedObjects(connectionId, MqObjectType.QUEUE)
        delayUntilMin(start)
        if (after.isNotEmpty()) {
            loadState = LoadState.Success(after)
        }
        // 否则保持 Loading：真实扫描完成经事件总线（下方订阅）翻 Success，期间持续显示进度圈。
    }

    LaunchedEffect(connectionId) {
        loadInitial()

        // 扫描完成 → 重读缓存（三条扫描链路任一完成都刷新；payload 过滤本连接）。
        // 处于「加载中」或「已加载」时都刷新：覆盖「连接未就绪 → 稍后真正扫描完成」的落地。
        val bus = CoreModule.eventBus
        listOf(
            IbmMQEvents.OBJECTS_SCANNED,
            IbmMQEvents.MESSAGES_SCANNED,
            IbmMQEvents.SCAN_COMPLETED,
        ).forEach { name ->
            bus.on(name, this) { event ->
                if ((event.payload["connectionId"] as? String) == connectionId) {
                    val latest =
                        IbmMQModule.serviceOrNull()?.cachedObjects(connectionId, MqObjectType.QUEUE) ?: emptyList()
                    if (loadState is LoadState.Success || loadState is LoadState.Loading) {
                        loadState = LoadState.Success(latest)
                    }
                }
            }
        }
    }

    /** 刷新（保留旧数据）：置 isRefreshing，扫完由事件驱动重载；失败仅 snackbar 提示。 */
    fun refresh() {
        scope.launch {
            isRefreshing = true
            val service = IbmMQModule.serviceOrNull() ?: run { isRefreshing = false; return@launch }
            runCatching { service.scanObjects(connectionId) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    snackbarHostState.showSnackbar(
                        stringResource("queue_scan_failed").withArg(error.message ?: error::class.simpleName ?: ""),
                    )
                }
            // 成功后的缓存重载由 SCAN_COMPLETED 事件驱动（上面已订阅）；保留旧数据。
            isRefreshing = false
        }
    }

    LoadStateContent(
        state = loadState,
        onRetry = { scope.launch { loadInitial() } },
        loadingMessage = stringResource("queue_loading"),
        modifier = Modifier.fillMaxSize(),
        // 空列表的呈现交给 ObjectListPanel（其内部自带空态 + 工具栏，
        // 「显示系统」开关在空结果下仍需可用），故此处恒返回 false。
        emptyCheck = { false },
        content = { queues ->
            val filtered = queues.filter { q ->
                searchText.isEmpty() || q.name.contains(searchText, ignoreCase = true)
            }.filter { q ->
                showSystemQueues || !isSystemOrTempQueue(q.name)
            }

            ObjectListPanel(
                items = filtered.map { queue ->
                    queue.toQueueCardData(
                        warningThreshold = warningThreshold,
                        actions = QueueCardActions.create(
                            snapshot = queue,
                            onOpenProperties = { onOpenProperties(queue.name) },
                            onOpenMessages = { onOpenMessages(queue.name) },
                        ),
                    )
                },
                onCardClick = { card -> onOpenProperties(card.id) },
                onCardDoubleClick = { card -> onOpenMessages(card.id) },
                toolbarContent = {
                    QueueListToolbar(
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        onRefresh = ::refresh,
                        refreshing = isRefreshing,
                        showSystemQueues = showSystemQueues,
                        onToggleSystemQueues = { showSystemQueues = !showSystemQueues },
                    )
                },
                loading = false,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

/**
 * MqObjectSnapshot → ObjectCardData 的转换（[QueueCardAdapter]）。
 * 已迁移至 QueueCardAdapter.kt 的 toQueueCardData。
 */

/** 工具栏 Slot 内容：紧凑搜索框（左，占满剩余宽度）+ 刷新按钮（右），同高 32dp。 */
@Composable
private fun QueueListToolbar(
    searchText: String,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    showSystemQueues: Boolean,
    onToggleSystemQueues: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 刷新进行中：顶部细进度条（保留旧数据，不整页 Loading）；不改变按钮位置。
        if (refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ToolbarSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactSearchField(
                value = searchText,
                onValueChange = onSearchChange,
                placeholder = stringResource("queue_search_placeholder"),
                modifier = Modifier.weight(1f),
            )
            ToolbarButton(
                icon = Icons.Default.Refresh,
                text = stringResource("queue_refresh"),
                onClick = onRefresh,
                enabled = !refreshing,
            )
            // 系统队列显隐切换：文字是「点击后会发生的动作」——隐藏时显示「显示系统」。
            ToolbarButton(
                icon = if (showSystemQueues) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                text = stringResource(if (showSystemQueues) "queue_toolbar_hide_system" else "queue_toolbar_show_system"),
                onClick = onToggleSystemQueues,
            )
        }
    }
}

/** stringResource 无格式化参数；%s / %1$s 占位替换（全项目约定）。 */
private fun String.withArg(arg: Any): String =
    replace("%1\$s", arg.toString()).replace("%s", arg.toString())

/** 系统队列 / 临时队列判定：`SYSTEM.` 或 `AMQ.` 开头（大小写不敏感），默认隐藏。 */
internal fun isSystemOrTempQueue(queueName: String): Boolean {
    val name = queueName.uppercase()
    return name.startsWith("SYSTEM.") || name.startsWith("AMQ.")
}
