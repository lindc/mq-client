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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
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
import com.mqclient.ibmmq.messaging.MessageOperationResult
import com.mqclient.ibmmq.messaging.MessageSummary
import com.mqclient.ibmmq.messaging.browseErrorText
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.uicommon.DangerButton
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.AppTooltip
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.DateTimePickerDialog
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.file.saveFileBytes
import com.mqclient.uicommon.i18n.LanguageResources
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.table.TableHeaderText
import com.mqclient.uicommon.table.TableRowActionIcon
import com.mqclient.uicommon.table.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 「加载更多」每批条数（串行翻页）。 */
private const val PAGE_SIZE = 100

/** 表格固定列宽：时间 / 大小 / 持久化 / 优先级 / 操作（MessageId、CorrelationId 弹性）。 */
private val COL_TIME = 160.dp
private val COL_SIZE = 80.dp
private val COL_PERSISTENCE = 70.dp
private val COL_PRIORITY = 60.dp
private val COL_DETAIL = 80.dp
internal val ROW_HPADDING = 16.dp

/** 表格时间列的展示格式（MQMD PutDate+PutTime 截掉百分秒）。 */
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** 自定义时间的展示格式（选择器粒度到分钟）。 */
private val DISPLAY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 时间范围预设；CUSTOM 的起止由自定义输入框给出（留空 = 该侧不限制）。 */
private enum class BrowseTimeRange(val labelKey: String) {
    LAST_5_MIN("ibm_mq_msg_time_5min"),
    LAST_1_HOUR("ibm_mq_msg_time_1hour"),
    LAST_24_HOURS("ibm_mq_msg_time_24hour"),
    ALL("ibm_mq_msg_time_all"),
    CUSTOM("ibm_mq_msg_time_custom"),
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

    // 行操作（下载 / 删除）进行中的行：同一时刻只允许一个行操作，按钮转圈禁用。
    var rowActionId by remember { mutableStateOf<String?>(null) }
    // 待确认删除的消息（删除图标点击 → 确认框 → 实际执行）。
    var pendingDelete by remember { mutableStateOf<MessageSummary?>(null) }
    // 消息详情对话框（详细图标打开；内容待实装，先空着）。
    var detailMessage by remember { mutableStateOf<MessageSummary?>(null) }

    fun showCopied(text: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbarHostState.showSnackbar(stringResource("ibm_mq_msg_copied")) }
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

    /** 下载：按 MessageId 取完整消息体（独立 browse 句柄纯浏览，队列不动），弹「另存为」写盘。 */
    fun downloadMessage(message: MessageSummary) {
        if (rowActionId != null) return
        scope.launch {
            rowActionId = message.messageId
            val result = IbmMQModule.serviceOrNull()
                ?.downloadMessageBody(connectionId, queueName, message.messageId)
                ?: MessageOperationResult.failure(null, "服务未初始化")
            // showSnackbar 是挂起调用（挂到提示关闭才返回），另起协程弹提示，
            // 不阻塞行状态复位与后续 UI 更新。
            when {
                result.success && result.content != null -> {
                    try {
                        val saved = saveFileBytes("msg_${message.messageId.take(8)}.bin", "bin", result.content!!)
                        if (saved != null) {
                            scope.launch { snackbarHostState.showSnackbar(stringResource("ibm_mq_msg_browse_saved").withArg(saved)) }
                        }
                    } catch (e: Exception) {
                        scope.launch {
                            snackbarHostState.showSnackbar(stringResource("ibm_mq_msg_save_failed").withArg(e.message ?: ""))
                        }
                    }
                }
                else -> scope.launch {
                    snackbarHostState.showSnackbar(
                        stringResource("ibm_mq_msg_browse_op_failed").withArg(result.errorMessage ?: ""),
                    )
                }
            }
            rowActionId = null
        }
    }

    /** 删除：按 MessageId 消费掉该条（MQGMO_MSG_UNDER_CURSOR 只动这一条）。成功后
     * **本地直接移除该行并把 CURDEPTH 减一**——不走网络刷新，即时生效；要看最新
     * 全量（含其他应用收发的变化）用工具栏的刷新按钮。 */
    fun deleteMessage(message: MessageSummary) {
        if (rowActionId != null) return
        scope.launch {
            rowActionId = message.messageId
            val result = IbmMQModule.serviceOrNull()
                ?.deleteMessage(connectionId, queueName, message.messageId)
                ?: MessageOperationResult.failure(null, "服务未初始化")
            if (result.success) {
                // 先更新列表（showSnackbar 挂起会阻塞本协程，提示与刷新互不等待）。
                (uiState as? BrowseUiState.Ready)?.let { ready ->
                    uiState = ready.copy(
                        messages = ready.messages.filterNot { it.messageId == message.messageId },
                        depth = (ready.depth - 1).coerceAtLeast(0),
                    )
                }
            }
            scope.launch {
                snackbarHostState.showSnackbar(
                    if (result.success) {
                        stringResource("ibm_mq_msg_browse_delete_ok")
                    } else {
                        stringResource("ibm_mq_msg_browse_op_failed").withArg(result.errorMessage ?: "")
                    },
                )
            }
            rowActionId = null
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
                ready.messages.size >= ready.depth -> stringResource("ibm_mq_msg_browse_total").withArg(ready.depth)
                else -> stringResource("ibm_mq_msg_browse_total_loaded")
                    .withArg(ready.depth, ready.messages.size)
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
                        ObjectListEmptyState(message = stringResource("ibm_mq_msg_browse_empty"))
                    filtered.isEmpty() ->
                        ObjectListEmptyState(message = stringResource("ibm_mq_msg_browse_filter_empty"))
                    else -> MessageTable(
                        messages = filtered,
                        onCopy = ::showCopied,
                        actionInProgressId = rowActionId,
                        onDownload = ::downloadMessage,
                        onDelete = { pendingDelete = it },
                        onDetail = { detailMessage = it },
                    )
                }
            }
            ThemedSnackbarHost(
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

    pendingDelete?.let { message ->
        ConfirmDeleteMessageDialog(
            onConfirm = {
                pendingDelete = null
                deleteMessage(message)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    detailMessage?.let { message ->
        MessageDetailDialog(
            connectionId = connectionId,
            queueName = queueName,
            summary = message,
            onDismiss = { detailMessage = null },
            // dmpmqmsg 导入成功后刷新列表（新 MessageId，原列表里没有）
            onImported = { refresh() },
        )
    }
}

/** 删除消息确认框：破坏性操作（消费掉该条，不可恢复），先确认再执行。 */
@Composable
private fun ConfirmDeleteMessageDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("ibm_mq_msg_browse_delete")) },
        text = { Text(stringResource("ibm_mq_msg_browse_delete_confirm")) },
        confirmButton = {
            DangerButton(onClick = onConfirm) { Text(stringResource("ibm_mq_msg_browse_delete")) }
        },
        dismissButton = {
            SecondaryButton(
                onClick = onDismiss,
            ) { Text(stringResource("common_cancel")) }
        },
    )
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
            placeholder = stringResource("ibm_mq_msg_browse_search_hint"),
            modifier = Modifier.weight(1f),
        )
        // 时间范围下拉（对已加载消息做本地过滤）。
        Box {
            SecondaryButton(
                onClick = { menuExpanded = true },
                standardMinWidth = false,
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
                Icon(Icons.Default.Refresh, contentDescription = stringResource("ibm_mq_msg_browse_refresh"))
            }
        }
    }
}

/* ------------------------------- 表格 ------------------------------- */

@Composable
private fun MessageTable(
    messages: List<MessageSummary>,
    onCopy: (String) -> Unit,
    actionInProgressId: String?,
    onDownload: (MessageSummary) -> Unit,
    onDelete: (MessageSummary) -> Unit,
    onDetail: (MessageSummary) -> Unit,
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
            TableHeaderText(stringResource("ibm_mq_msg_col_message_id"), Modifier.weight(1f))
            TableHeaderText(stringResource("ibm_mq_msg_col_correlation_id"), Modifier.weight(1f))
            TableHeaderText(stringResource("ibm_mq_msg_col_time"), Modifier.width(COL_TIME))
            TableHeaderText(stringResource("ibm_mq_msg_col_size"), Modifier.width(COL_SIZE))
            TableHeaderText(stringResource("ibm_mq_msg_col_persistence"), Modifier.width(COL_PERSISTENCE))
            TableHeaderText(stringResource("ibm_mq_msg_col_priority"), Modifier.width(COL_PRIORITY))
            TableHeaderText(stringResource("ibm_mq_msg_send_col_actions"), Modifier.width(COL_DETAIL))
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages, key = { it.messageId }) { message ->
                MessageRow(
                    message = message,
                    onCopy = onCopy,
                    actionInProgress = actionInProgressId == message.messageId,
                    anyActionInProgress = actionInProgressId != null,
                    onDownload = onDownload,
                    onDelete = onDelete,
                    onDetail = onDetail,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: MessageSummary,
    onCopy: (String) -> Unit,
    actionInProgress: Boolean,
    anyActionInProgress: Boolean,
    onDownload: (MessageSummary) -> Unit,
    onDelete: (MessageSummary) -> Unit,
    onDetail: (MessageSummary) -> Unit,
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
            text = formatSize(message.dataLength.toLong()),
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
        Row(modifier = Modifier.width(COL_DETAIL)) {
            // 详细：弹出详情对话框（内容待实装，先空壳）。
            TableRowActionIcon(
                icon = Icons.Default.Info,
                tooltip = stringResource("ibm_mq_msg_col_detail"),
                enabled = !anyActionInProgress,
                onClick = { onDetail(message) },
            )
            // 下载：取完整消息体弹「另存为」，队列消息不动。
            TableRowActionIcon(
                icon = Icons.Default.Download,
                tooltip = stringResource("ibm_mq_msg_browse_download"),
                enabled = !anyActionInProgress,
                executing = actionInProgress,
                onClick = { onDownload(message) },
            )
            // 删除：按 MessageId 消费掉该条（只动这一条），成功后刷新列表。
            TableRowActionIcon(
                icon = Icons.Default.Delete,
                tooltip = stringResource("ibm_mq_msg_browse_delete"),
                enabled = !anyActionInProgress,
                executing = actionInProgress,
                onClick = { onDelete(message) },
            )
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
    AppTooltip(text, modifier) {
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
        if (persistent) "ibm_mq_msg_persistence_persistent" else "ibm_mq_msg_persistence_non_persistent",
    )
    AppTooltip(label) {
        Icon(
            imageVector = if (persistent) Icons.Default.Lock else Icons.Default.LockOpen,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
            stringResource("ibm_mq_msg_browse_loaded_all").withArg(loadedCount)
        } else {
            stringResource("ibm_mq_msg_browse_loaded_of").withArg(loadedCount, depth)
        }
        Text(
            text = progressText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (!exhausted && loadedCount < depth) {
            PrimaryButton(onClick = onLoadMore, enabled = !loadingMore) {
                Text(
                    stringResource(
                        if (loadingMore) "ibm_mq_msg_browse_loading_more" else "ibm_mq_msg_browse_load_more",
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
            text = stringResource("ibm_mq_msg_browse_loading"),
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
            text = stringResource("ibm_mq_msg_browse_failed").withArg(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton(onClick = onRetry) { Text(stringResource("common_retry")) }
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
        title = { Text(stringResource("ibm_mq_msg_time_custom")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeRangeRow(
                    label = stringResource("ibm_mq_msg_time_start"),
                    value = start,
                    onPick = { picking = TimeEditTarget.START },
                    onClear = { start = null },
                )
                TimeRangeRow(
                    label = stringResource("ibm_mq_msg_time_end"),
                    value = end,
                    onPick = { picking = TimeEditTarget.END },
                    onClear = { end = null },
                )
                Text(
                    text = stringResource("ibm_mq_msg_time_blank_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            PrimaryButton(onClick = {
                // 结束时间取整到该分钟的最后一秒，含端点；两侧都空 = 不限制。
                onConfirm(start?.withSecond(0), end?.withSecond(59))
            }) { Text(stringResource("common_ok")) }
        },
        dismissButton = {
            SecondaryButton(
                onClick = onDismiss,
            ) { Text(stringResource("common_cancel")) }
        },
    )

    picking?.let { target ->
        val isStart = target == TimeEditTarget.START
        DateTimePickerDialog(
            title = stringResource(if (isStart) "ibm_mq_msg_time_start" else "ibm_mq_msg_time_end"),
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
        SecondaryButton(
            onClick = onPick,
            standardMinWidth = false,
        ) {
            Text(value?.format(DISPLAY_TIME_FORMATTER) ?: stringResource("ibm_mq_msg_time_not_set"))
        }
        if (value != null) {
            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

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

