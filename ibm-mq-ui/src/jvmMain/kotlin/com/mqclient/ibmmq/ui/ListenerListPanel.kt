package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.dp
import com.mqclient.core.CoreModule
import com.mqclient.ibmmq.IbmMQModule
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

/**
 * 监听器列表（导航 `listeners` Tab 的内容）：复用 mq-ui 通用 [LoadStateContent] +
 * [ObjectListPanel]，与队列 / 通道 / 主题 / 订阅页同一套加载状态语义。
 *
 * 数据来自**扫描缓存**（`IbmMQService.cachedObjects`，只读不碰 MQ）；「刷新」触发
 * `IbmMQService.scanObjects` 重扫，扫描完成的事件（OBJECTS / MESSAGES / SCAN_COMPLETED）
 * 驱动列表自动刷新 —— 手动与定时扫描共享同一条响应链路。
 *
 * 监听器无队列深度阈值、无系统/临时隐藏，工具栏只保留搜索 + 刷新。加载态、错误重试、
 * 空态、刷新保留旧数据的策略与其余列表页完全一致。
 */
@Composable
fun ListenerListPanel(
    connectionId: String,
    onOpenProperties: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 加载状态收拢为单一 LoadState：初始 Loading（记住连接 id，切换连接自动重置）。
    var loadState by remember(connectionId) {
        mutableStateOf<LoadState<List<MqObjectSnapshot>>>(LoadState.Loading)
    }
    // 刷新（保留旧数据）时的小指示器；与整页 Loading 区分开。
    var isRefreshing by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

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
            delayUntilMin(start)
            return
        }

        // 热缓存：直接落地，避免无谓的 Loading 闪烁。
        val cached = service.cachedObjects(connectionId, MqObjectType.LISTENER)
        if (cached.isNotEmpty()) {
            delayUntilMin(start)
            loadState = LoadState.Success(cached)
            return
        }

        // 冷缓存：触发扫描。连接就绪则扫完写缓存、此处能拿到数据；未就绪则 scanObjects
        // no-op（connectedContext 为 null），缓存仍空 → 保持 Loading，等连接就绪后真正
        // 的扫描经事件总线翻 Success（不提交空态，避免空白窗体）。
        runCatching { service.scanObjects(connectionId) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                delayUntilMin(start)
                loadState = LoadState.Error(
                    stringResource("listener_load_failed").withArg(error.message ?: error::class.simpleName ?: ""),
                    error,
                )
                return
            }

        val after = service.cachedObjects(connectionId, MqObjectType.LISTENER)
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
                        IbmMQModule.serviceOrNull()?.cachedObjects(connectionId, MqObjectType.LISTENER) ?: emptyList()
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
                        stringResource("listener_scan_failed").withArg(error.message ?: error::class.simpleName ?: ""),
                    )
                }
            // 成功后的缓存重载由 SCAN_COMPLETED 事件驱动（上面已订阅）；保留旧数据。
            isRefreshing = false
        }
    }

    LoadStateContent(
        state = loadState,
        onRetry = { scope.launch { loadInitial() } },
        loadingMessage = stringResource("listener_loading"),
        modifier = Modifier.fillMaxSize(),
        // 空列表的呈现交给 ObjectListPanel（其内部自带空态 + 工具栏），故此处恒返回 false。
        emptyCheck = { false },
        content = { listeners ->
            val filtered = listeners.filter { l ->
                searchText.isEmpty() || l.name.contains(searchText, ignoreCase = true)
            }

            ObjectListPanel(
                items = filtered.map { listener ->
                    listener.toListenerCardData(
                        actions = ListenerCardActions.create(
                            snapshot = listener,
                            onOpenProperties = { onOpenProperties(listener.name) },
                        ),
                    )
                },
                onCardClick = { card -> onOpenProperties(card.id) },
                onCardDoubleClick = { card -> onOpenProperties(card.id) },
                toolbarContent = {
                    ListenerListToolbar(
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        onRefresh = ::refresh,
                        refreshing = isRefreshing,
                    )
                },
                loading = false,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

/** 工具栏 Slot 内容：紧凑搜索框（左，占满剩余宽度）+ 刷新按钮（右），同高 32dp。 */
@Composable
private fun ListenerListToolbar(
    searchText: String,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
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
                placeholder = stringResource("listener_search_placeholder"),
                modifier = Modifier.weight(1f),
            )
            ToolbarButton(
                icon = Icons.Default.Refresh,
                text = stringResource("listener_refresh"),
                onClick = onRefresh,
                enabled = !refreshing,
            )
        }
    }
}

/** stringResource 无格式化参数；%s / %1$s 占位替换（全项目约定）。 */
private fun String.withArg(arg: Any): String =
    replace("%1\$s", arg.toString()).replace("%s", arg.toString())

/** 初始 / 重试加载时 Loading 至少可见的时长，避免缓存命中过快导致进度圈"闪不过去"。 */
private const val MIN_LOADING_MS = 400L
