@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.mqclient.ibmmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.messaging.MessageBrowseSession
import com.mqclient.ibmmq.messaging.MessageSummary
import com.mqclient.ibmmq.messaging.browseErrorText
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.LanguageResources
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 「加载更多」每批条数（串行翻页）。 */
private const val PAGE_SIZE = 100

/** 表格固定列宽：时间 / 大小 / 持久化 / 优先级 / 操作（MessageId、CorrelationId 弹性）。 */
private val COL_TIME = 160.dp
private val COL_SIZE = 80.dp
private val COL_PERSISTENCE = 70.dp
private val COL_PRIORITY = 60.dp
private val COL_DETAIL = 80.dp
private val ROW_HPADDING = 16.dp

/** 表格时间列的展示格式（MQMD PutDate+PutTime 截掉百分秒）。 */
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** 自定义时间的展示格式（选择器粒度到分钟）。 */
private val DISPLAY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 时间范围预设；CUSTOM 的起止由自定义输入框给出（留空 = 该侧不限制）。 */
private enum class BrowseTimeRange(val labelKey: String) {
    LAST_5_MIN("msg_time_5min"),
    LAST_1_HOUR("msg_time_1hour"),
    LAST_24_HOURS("msg_time_24hour"),
    ALL("msg_time_all"),
    CUSTOM("msg_time_custom"),
}

/**
 * 浏览页三态：初始加载 / 失败（带原因 + 重试）/ 就绪。「队列为空」「过滤无结果」
 * 在就绪态内按列表与过滤结果分流，不单独建模。
 */
private sealed interface BrowseUiState {
    data object Loading : BrowseUiState
    data class Failed(val reason: String) : BrowseUiState
    data class Ready(
        val messages: List<MessageSummary>,
        /** CURDEPTH（最近一次刷新时查询的服务端总数）。 */
        val depth: Int,
        /** 浏览光标已到队尾（返回不足一页即视为耗尽）。 */
        val exhausted: Boolean,
        val loadingMore: Boolean = false,
        val refreshing: Boolean = false,
    ) : BrowseUiState
}

/**
 * 「浏览消息」页：工具栏（搜索 / 时间范围 / 总数 + 刷新）+ 消息表格 + 底部
 * （已加载进度 + 加载更多）。数据经 [MessageBrowseSession] 游标浏览，纯 browse
 * 不消费消息；搜索与时间过滤只作用于已加载列表（本地过滤，不重新 browse）。
 *
 * 会话生命周期跟本页组合走：切到「发送消息」tab 或关掉对话框都会关会话，
 * 切回来重新打开并加载首页。
 */
@Composable
internal fun MessageBrowseTab(
    connectionId: String,
    queueName: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    var uiState by remember(queueName) { mutableStateOf<BrowseUiState>(BrowseUiState.Loading) }
    var session by remember(queueName) { mutableStateOf<MessageBrowseSession?>(null) }
    // 初始加载 / 失败重试共用这条 effect：retryKey++ 触发重跑，重跑前先关旧会话。
    var retryKey by remember(queueName) { mutableIntStateOf(0) }

    LaunchedEffect(queueName, retryKey) {
        session?.close()
        session = null
        try {
            val service = IbmMQModule.serviceOrNull()
                ?: throw IllegalStateException("服务未初始化")
            val opened = service.openBrowseSession(connectionId, queueName)
            session = opened
            val depth = service.getQueueDepth(connectionId, queueName)
            val messages = opened.browseNext(PAGE_SIZE)
            uiState = BrowseUiState.Ready(messages, depth, exhausted = messages.size < PAGE_SIZE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = BrowseUiState.Failed(browseErrorText(e))
        }
    }
    DisposableEffect(queueName) {
        onDispose {
            session?.close()
            session = null
        }
    }

    // 搜索：输入即时回显，防抖 300ms 后进过滤；只过滤已加载消息，不触发 browse。
    var rawSearch by remember { mutableStateOf("") }
    var appliedSearch by remember { mutableStateOf("") }
    LaunchedEffect(rawSearch) {
        delay(300)
        appliedSearch = rawSearch
    }

    var timeRange by remember { mutableStateOf(BrowseTimeRange.ALL) }
    var customStart by remember { mutableStateOf<LocalDateTime?>(null) }
    var customEnd by remember { mutableStateOf<LocalDateTime?>(null) }
    var showCustomTimeDialog by remember { mutableStateOf(false) }

    fun showCopied(text: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbarHostState.showSnackbar(stringResource("msg_copied")) }
    }

    fun loadMore() {
        val opened = session ?: return
        val current = uiState as? BrowseUiState.Ready ?: return
        if (current.loadingMore || current.exhausted) return
        scope.launch {
            uiState = current.copy(loadingMore = true)
            try {
                val more = opened.browseNext(PAGE_SIZE)
                val ready = uiState as? BrowseUiState.Ready ?: return@launch
                uiState = ready.copy(
                    messages = ready.messages + more,
                    exhausted = more.size < PAGE_SIZE,
                    loadingMore = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (uiState as? BrowseUiState.Ready)?.let { uiState = it.copy(loadingMore = false) }
                scope.launch { snackbarHostState.showSnackbar(browseErrorText(e)) }
            }
        }
    }

    fun refresh() {
        val opened = session ?: return
        val current = uiState as? BrowseUiState.Ready ?: return
        if (current.refreshing || current.loadingMore) return
        scope.launch {
            uiState = current.copy(refreshing = true)
            try {
                val depth = IbmMQModule.serviceOrNull()
                    ?.getQueueDepth(connectionId, queueName) ?: current.depth
                // BROWSE_FIRST 重置光标到队首，重取首页替换列表（不动用户已加载的更多页）。
                opened.reset()
                val messages = opened.browseNext(PAGE_SIZE)
                uiState = BrowseUiState.Ready(messages, depth, exhausted = messages.size < PAGE_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (uiState as? BrowseUiState.Ready)?.let { uiState = it.copy(refreshing = false) }
                scope.launch { snackbarHostState.showSnackbar(browseErrorText(e)) }
            }
        }
    }

    val ready = uiState as? BrowseUiState.Ready
    val filtered = ready?.messages?.let {
        filterMessages(it, appliedSearch, timeRange, customStart, customEnd)
    } ?: emptyList()

    Column(modifier = modifier.fillMaxSize()) {
        // ---- 1. 工具栏：搜索（左）+ 时间范围（中）+ 总数 / 刷新（右）----
        BrowseToolbar(
            rawSearch = rawSearch,
            onSearchChange = { rawSearch = it },
            timeRange = timeRange,
            onRangeChange = { range ->
                timeRange = range
                if (range == BrowseTimeRange.CUSTOM) showCustomTimeDialog = true
            },
            totalText = when {
                ready == null -> ""
                ready.messages.size >= ready.depth -> stringResource("msg_browse_total").withArg(ready.depth)
                else -> stringResource("msg_browse_total_loaded")
                    .withArgs(ready.depth, ready.messages.size)
            },
            refreshing = ready?.refreshing == true,
            onRefresh = ::refresh,
        )
        HorizontalDivider()

        // ---- 2. 表格 / 状态区（对话框是独立窗口，够不到主窗口的 SnackbarHost，
        //      局部放一个，复制提示 / 加载更多失败都打在这里）----
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val state = uiState) {
                BrowseUiState.Loading -> BrowseLoadingView(Modifier.fillMaxSize())
                is BrowseUiState.Failed -> BrowseErrorView(
                    reason = state.reason,
                    onRetry = { retryKey++ },
                    modifier = Modifier.fillMaxSize(),
                )
                is BrowseUiState.Ready -> when {
                    state.messages.isEmpty() ->
                        ObjectListEmptyState(message = stringResource("msg_browse_empty"))
                    filtered.isEmpty() ->
                        ObjectListEmptyState(message = stringResource("msg_browse_filter_empty"))
                    else -> MessageTable(filtered, onCopy = ::showCopied)
                }
            }
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // ---- 3. 底部：已加载进度 + 加载更多 ----
        if (ready != null && ready.messages.isNotEmpty()) {
            HorizontalDivider()
            BrowseBottomBar(
                loadedCount = ready.messages.size,
                depth = ready.depth,
                exhausted = ready.exhausted,
                loadingMore = ready.loadingMore,
                onLoadMore = ::loadMore,
            )
        }
    }

    if (showCustomTimeDialog) {
        CustomTimeRangeDialog(
            initialStart = customStart,
            initialEnd = customEnd,
            onConfirm = { start, end ->
                customStart = start
                customEnd = end
                showCustomTimeDialog = false
            },
            onDismiss = { showCustomTimeDialog = false },
        )
    }
}

/* ------------------------------- 工具栏 ------------------------------- */

@Composable
private fun BrowseToolbar(
    rawSearch: String,
    onSearchChange: (String) -> Unit,
    timeRange: BrowseTimeRange,
    onRangeChange: (BrowseTimeRange) -> Unit,
    totalText: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HPADDING, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactSearchField(
            value = rawSearch,
            onValueChange = onSearchChange,
            placeholder = stringResource("msg_browse_search_hint"),
            modifier = Modifier.weight(1f),
        )
        // 时间范围下拉（对已加载消息做本地过滤）。
        Box {
            PressableButton(
                onClick = { menuExpanded = true },
                standardMinWidth = false,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(stringResource(timeRange.labelKey))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                BrowseTimeRange.entries.forEach { range ->
                    DropdownMenuItem(
                        text = { Text(stringResource(range.labelKey)) },
                        onClick = {
                            menuExpanded = false
                            onRangeChange(range)
                        },
                    )
                }
            }
        }
        if (totalText.isNotEmpty()) {
            Text(
                text = totalText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = stringResource("msg_browse_refresh"))
            }
        }
    }
}

/* ------------------------------- 表格 ------------------------------- */

@Composable
private fun MessageTable(
    messages: List<MessageSummary>,
    onCopy: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 表头：列宽与行单元格一一对应（ID 两列弹性，其余固定）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = ROW_HPADDING, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TableHeaderText(stringResource("msg_col_message_id"), Modifier.weight(1f))
            TableHeaderText(stringResource("msg_col_correlation_id"), Modifier.weight(1f))
            TableHeaderText(stringResource("msg_col_time"), Modifier.width(COL_TIME))
            TableHeaderText(stringResource("msg_col_size"), Modifier.width(COL_SIZE))
            TableHeaderText(stringResource("msg_col_persistence"), Modifier.width(COL_PERSISTENCE))
            TableHeaderText(stringResource("msg_col_priority"), Modifier.width(COL_PRIORITY))
            TableHeaderText(stringResource("msg_col_detail"), Modifier.width(COL_DETAIL))
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages, key = { it.messageId }) { message ->
                MessageRow(message, onCopy)
            }
        }
    }
}

@Composable
private fun TableHeaderText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun MessageRow(
    message: MessageSummary,
    onCopy: (String) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = if (hovered) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(background)
            .padding(horizontal = ROW_HPADDING, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IdCell(message.messageId, Modifier.weight(1f), onCopy)
        IdCell(message.correlationId, Modifier.weight(1f), onCopy)
        Text(
            text = message.putDateTime?.format(TIME_FORMATTER) ?: "-",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(COL_TIME),
        )
        Text(
            text = formatSize(message.dataLength),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(COL_SIZE),
        )
        Box(modifier = Modifier.width(COL_PERSISTENCE)) {
            PersistenceIcon(message.persistence)
        }
        Text(
            text = message.priority.toString(),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(COL_PRIORITY),
        )
        Box(modifier = Modifier.width(COL_DETAIL)) {
            // 本轮禁用：Message Detail 对话框下一步实装。
            PressableButton(
                onClick = {},
                enabled = false,
                standardMinWidth = false,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("msg_col_detail")) }
        }
    }
}

/** MessageId / CorrelationId 单元格：hover 显示全串，点击复制到剪贴板。 */
@Composable
private fun IdCell(
    text: String,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit,
) {
    HoverTooltip(text, modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onCopy(text) }
                .padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun PersistenceIcon(persistence: Int) {
    val persistent = persistence == 1 // MQPER_PERSISTENT
    val label = stringResource(
        if (persistent) "msg_persistence_persistent" else "msg_persistence_non_persistent",
    )
    HoverTooltip(label) {
        Icon(
            imageVector = if (persistent) Icons.Default.Lock else Icons.Default.LockOpen,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 轻量 hover 提示（项目里 ObjectCard 的 TooltipArea 同款做法）。 */
@Composable
private fun HoverTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
                tonalElevation = 2.dp,
            ) {
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(6.dp),
                )
            }
        },
        delayMillis = 400,
        modifier = modifier,
        content = content,
    )
}

/* ------------------------------- 底部 ------------------------------- */

@Composable
private fun BrowseBottomBar(
    loadedCount: Int,
    depth: Int,
    exhausted: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HPADDING, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val progressText = if (exhausted || loadedCount >= depth) {
            stringResource("msg_browse_loaded_all").withArg(loadedCount)
        } else {
            stringResource("msg_browse_loaded_of").withArgs(loadedCount, depth)
        }
        Text(
            text = progressText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (!exhausted && loadedCount < depth) {
            PressableButton(onClick = onLoadMore, enabled = !loadingMore) {
                Text(
                    stringResource(
                        if (loadingMore) "msg_browse_loading_more" else "msg_browse_load_more",
                    ),
                )
            }
        }
    }
}

/* ------------------------------- 状态视图 ------------------------------- */

@Composable
private fun BrowseLoadingView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource("msg_browse_loading"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BrowseErrorView(
    reason: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource("msg_browse_failed").withArg(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PressableButton(onClick = onRetry) { Text(stringResource("common_retry")) }
    }
}

/* --------------------------- 自定义时间范围 --------------------------- */

/** 自定义时间弹窗里正在编辑哪一侧。 */
private enum class TimeEditTarget { START, END }

@Composable
private fun CustomTimeRangeDialog(
    initialStart: LocalDateTime?,
    initialEnd: LocalDateTime?,
    onConfirm: (LocalDateTime?, LocalDateTime?) -> Unit,
    onDismiss: () -> Unit,
) {
    var start by remember { mutableStateOf(initialStart) }
    var end by remember { mutableStateOf(initialEnd) }
    // 当前打开的日期时间选择器（开始 / 结束），null = 没开。
    var picking by remember { mutableStateOf<TimeEditTarget?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("msg_time_custom")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeRangeRow(
                    label = stringResource("msg_time_start"),
                    value = start,
                    onPick = { picking = TimeEditTarget.START },
                    onClear = { start = null },
                )
                TimeRangeRow(
                    label = stringResource("msg_time_end"),
                    value = end,
                    onPick = { picking = TimeEditTarget.END },
                    onClear = { end = null },
                )
                Text(
                    text = stringResource("msg_time_blank_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            PressableButton(onClick = {
                // 结束时间取整到该分钟的最后一秒，含端点；两侧都空 = 不限制。
                onConfirm(start?.withSecond(0), end?.withSecond(59))
            }) { Text(stringResource("common_ok")) }
        },
        dismissButton = {
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_cancel")) }
        },
    )

    picking?.let { target ->
        val isStart = target == TimeEditTarget.START
        DateTimePickerDialog(
            title = stringResource(if (isStart) "msg_time_start" else "msg_time_end"),
            initial = if (isStart) start else end,
            onConfirm = { value ->
                if (isStart) start = value else end = value
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun TimeRangeRow(
    label: String,
    value: LocalDateTime?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(64.dp),
        )
        PressableButton(
            onClick = onPick,
            standardMinWidth = false,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(value?.format(DISPLAY_TIME_FORMATTER) ?: stringResource("msg_time_not_set"))
        }
        if (value != null) {
            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 日期 + 时间选择弹窗：分两步（先日期后时间）——M3 DatePicker 在桌面上的绘制
 * 宽度会超出测量宽度（压到旁边组件），竖排又超出对话框高度，并排 / 滚动都不可靠。 */
@Composable
private fun DateTimePickerDialog(
    title: String,
    initial: LocalDateTime?,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit,
) {
    // material3 内置文案（月份 / 星期 / Select date…）经 PlatformLocaleDelegate 读
    // JVM 默认 locale，不认项目自己的 i18n 表 —— 这里对齐应用语言，缺翻译的键回落英文。
    val appLanguage = LanguageResources.currentLanguage() ?: LanguageResources.LANGUAGE_ZH_CN
    remember(appLanguage) {
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag(appLanguage))
        true
    }
    // DatePicker 的毫秒值是 UTC 零点；经 epochDay 换算避免时区偏移差一天。
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initial?.toLocalDate()?.toEpochDay()?.times(86_400_000L),
    )
    val timeState = rememberTimePickerState(
        initialHour = initial?.hour ?: 0,
        initialMinute = initial?.minute ?: 0,
        is24Hour = true,
    )
    var phase by remember { mutableStateOf(PickerPhase.DATE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when (phase) {
                PickerPhase.DATE -> DatePicker(state = dateState, showModeToggle = false)
                PickerPhase.TIME -> TimePicker(state = timeState)
            }
        },
        confirmButton = {
            when (phase) {
                PickerPhase.DATE -> PressableButton(
                    onClick = { phase = PickerPhase.TIME },
                    enabled = dateState.selectedDateMillis != null,
                ) { Text(stringResource("msg_time_next")) }
                PickerPhase.TIME -> PressableButton(onClick = {
                    val millis = dateState.selectedDateMillis ?: return@PressableButton
                    val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    onConfirm(LocalDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute)))
                }) { Text(stringResource("common_ok")) }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 时间步可退回改日期；日期步只有取消。
                if (phase == PickerPhase.TIME) {
                    PressableButton(
                        onClick = { phase = PickerPhase.DATE },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { Text(stringResource("msg_time_back")) }
                }
                PressableButton(
                    onClick = onDismiss,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) { Text(stringResource("common_cancel")) }
            }
        },
    )
}

private enum class PickerPhase { DATE, TIME }

/* ------------------------------- 纯函数 ------------------------------- */

/** 本地过滤：hex 串大小写不敏感 contains + 时间范围（解析不出时间的消息只对「全部」可见）。 */
private fun filterMessages(
    messages: List<MessageSummary>,
    search: String,
    range: BrowseTimeRange,
    customStart: LocalDateTime?,
    customEnd: LocalDateTime?,
): List<MessageSummary> {
    val query = search.trim()
    val now = LocalDateTime.now()
    val start = when (range) {
        BrowseTimeRange.LAST_5_MIN -> now.minusMinutes(5)
        BrowseTimeRange.LAST_1_HOUR -> now.minusHours(1)
        BrowseTimeRange.LAST_24_HOURS -> now.minusHours(24)
        BrowseTimeRange.ALL -> null
        BrowseTimeRange.CUSTOM -> customStart
    }
    val end = if (range == BrowseTimeRange.CUSTOM) customEnd else null
    return messages.filter { message ->
        val matchesSearch = query.isEmpty() ||
            message.messageId.contains(query, ignoreCase = true) ||
            message.correlationId.contains(query, ignoreCase = true)
        if (!matchesSearch) return@filter false
        if (range == BrowseTimeRange.ALL) return@filter true
        val time = message.putDateTime ?: return@filter false
        (start == null || !time.isBefore(start)) && (end == null || !time.isAfter(end))
    }
}

/** 大小列：< 1KB 显示字节，否则 KB（1 位小数）。 */
private fun formatSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    else -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
}

/** 多占位文案：依次替换 %s（i18n 串里占位符按出现顺序取参）。 */
private fun String.withArgs(vararg args: Any): String {
    var result = this
    args.forEach { arg -> result = result.replaceFirst("%s", arg.toString()) }
    return result
}
