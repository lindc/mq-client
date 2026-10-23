@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.mqclient.rocketmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.rocketmq.RocketMqMessage
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.table.TableHeaderText
import com.mqclient.uicommon.table.TableRowActionIcon
import com.mqclient.uicommon.table.formatSize
import com.mqclient.uicommon.table.formatTimestamp
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 消息表格的固定列宽：Queue / Offset / MsgId / Tags / Keys / Timestamp / Size / 操作（Value 弹性）。 */
internal val RMSG_COL_QUEUE = 60.dp
internal val RMSG_COL_OFFSET = 90.dp
internal val RMSG_COL_MSGID = 150.dp
internal val RMSG_COL_TAGS = 100.dp
internal val RMSG_COL_KEYS = 120.dp
internal val RMSG_COL_TIMESTAMP = 160.dp
internal val RMSG_COL_SIZE = 70.dp
internal val RMSG_COL_ACTIONS = 40.dp
internal val RMSG_ROW_HPADDING = 16.dp

/** 表格时间列的展示格式（epoch millis → 本地时区，毫秒粒度）。 */
internal val RMSG_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/** 浏览页三态：初始加载 / 失败（带原因 + 重试）/ 就绪。 */
private sealed interface BrowseUiState {
    data object Loading : BrowseUiState
    data class Failed(val reason: String) : BrowseUiState
    data class Ready(
        val messages: List<RocketMqMessage>,
        /** 本队列的下一条 offset；null = 已到队列尾（无游标可续）。 */
        val nextBeginOffset: Long?,
        val loadingMore: Boolean = false,
    ) : BrowseUiState
}

/**
 * 「浏览消息」Tab：顶部工具栏（Queue 下拉 / 起点 Offset / 条数 / 读取 —— RocketMQ
 * 特有）+ 消息表格 + 底部（只读提示 + 加载更多）。数据经
 * [com.mqclient.rocketmq.RocketMqMessageService.readMessages]（临时 PullConsumer
 * 定点 pull，**不提交任何位点**，不影响任何消费组进度）；搜索只做已加载列表的本地
 * 过滤，不重新读。
 *
 * 打开即拉队列列表并按默认条件（Queue 0 + 起点 0 + 20 条）读首页；改条件不自动重读，
 * 点「读取」才生效。续读游标（nextBeginOffset）由本页持有。
 */
@Composable
internal fun RocketMqMessageBrowseTab(
    connectionId: String,
    topicName: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    // ---- 工具栏状态 ----
    var queueIds by remember(topicName) { mutableStateOf(listOf(0)) }
    var selectedQueue by remember(topicName) { mutableIntStateOf(0) }
    var offsetText by remember(topicName) { mutableStateOf("0") }
    var countText by remember(topicName) { mutableStateOf("20") }
    var retryKey by remember(topicName) { mutableIntStateOf(0) }

    var uiState by remember(topicName) { mutableStateOf<BrowseUiState>(BrowseUiState.Loading) }
    var detailMessage by remember(topicName) { mutableStateOf<RocketMqMessage?>(null) }

    fun count(): Int = (countText.toIntOrNull() ?: 20).coerceIn(1, 500)

    fun showError(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    /** 队列列表（Queue 下拉数据源）：路由 readQueueNums，失败回落 [0] 并提示。 */
    LaunchedEffect(topicName, retryKey) {
        try {
            val service = RocketMqModule.messageServiceOrNull()
                ?: throw IllegalStateException(stringResource("rocketmq_ui_send_service_not_ready"))
            val ids = service.fetchQueueIds(connectionId, topicName)
            if (ids.isNotEmpty()) {
                queueIds = ids
                if (selectedQueue !in ids) selectedQueue = ids.first()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(stringResource("rocketmq_ui_msg_browse_queue_failed").withArg(e.message ?: ""))
        }
    }

    /** 读首页（初始加载与「读取」按钮共用，retryKey++ 触发重跑）。 */
    LaunchedEffect(topicName, retryKey) {
        uiState = BrowseUiState.Loading
        try {
            val service = RocketMqModule.messageServiceOrNull()
                ?: throw IllegalStateException(stringResource("rocketmq_ui_send_service_not_ready"))
            val page = service.readMessages(
                connectionId = connectionId,
                topic = topicName,
                queueId = selectedQueue,
                offset = offsetText.toLongOrNull() ?: 0L,
                count = count(),
            )
            uiState = BrowseUiState.Ready(page.messages, page.nextBeginOffset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = BrowseUiState.Failed(e.message ?: e::class.simpleName ?: "unknown")
        }
    }

    fun loadMore() {
        val current = uiState as? BrowseUiState.Ready ?: return
        val cursor = current.nextBeginOffset ?: return
        if (current.loadingMore) return
        scope.launch {
            uiState = current.copy(loadingMore = true)
            try {
                val service = RocketMqModule.messageServiceOrNull()
                    ?: throw IllegalStateException(stringResource("rocketmq_ui_send_service_not_ready"))
                val page = service.readMessages(
                    connectionId = connectionId,
                    topic = topicName,
                    queueId = selectedQueue,
                    offset = cursor,
                    count = count(),
                )
                val ready = uiState as? BrowseUiState.Ready ?: return@launch
                uiState = ready.copy(
                    messages = ready.messages + page.messages,
                    nextBeginOffset = page.nextBeginOffset,
                    loadingMore = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (uiState as? BrowseUiState.Ready)?.let { uiState = it.copy(loadingMore = false) }
                showError(e.message ?: e::class.simpleName ?: "unknown")
            }
        }
    }

    fun showCopied(text: String) {
        clipboard.setText(AnnotatedString(text))
        showError(stringResource("rocketmq_ui_msg_copied"))
    }

    // 搜索：输入即时回显，防抖 300ms 后进过滤；只过滤已加载消息，不触发读取。
    var rawSearch by remember { mutableStateOf("") }
    var appliedSearch by remember { mutableStateOf("") }
    LaunchedEffect(rawSearch) {
        delay(300)
        appliedSearch = rawSearch
    }

    val ready = uiState as? BrowseUiState.Ready
    val filtered = ready?.messages?.let { filterMessages(it, appliedSearch) } ?: emptyList()

    Column(modifier = modifier.fillMaxSize()) {
        // ---- 1. 工具栏（Queue / Offset / 条数 / 读取）----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RMSG_ROW_HPADDING, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueueDropdown(
                selected = selectedQueue,
                options = queueIds,
                onSelect = { selectedQueue = it },
            )
            CompactNumberField(
                value = offsetText,
                onValueChange = { offsetText = it.filter(Char::isDigit).take(19) },
                width = 140.dp,
                hint = stringResource("rocketmq_ui_msg_browse_offset_hint"),
            )
            CompactNumberField(
                value = countText,
                onValueChange = { countText = it.filter(Char::isDigit).take(4) },
                width = 84.dp,
            )
            PrimaryButton(
                onClick = { retryKey++ },
                enabled = uiState != BrowseUiState.Loading,
            ) {
                Text(
                    stringResource(
                        if (uiState == BrowseUiState.Loading) {
                            "rocketmq_ui_msg_browse_loading"
                        } else {
                            "rocketmq_ui_msg_browse_read"
                        },
                    ),
                )
            }
        }
        HorizontalDivider()
        // 搜索行（独立一行：工具栏已占满宽度）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RMSG_ROW_HPADDING, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactSearchField(
                value = rawSearch,
                onValueChange = { rawSearch = it },
                placeholder = stringResource("rocketmq_ui_msg_browse_search"),
                modifier = Modifier.weight(1f),
            )
            if (ready != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource("rocketmq_ui_msg_browse_loaded").withArg(ready.messages.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()

        // ---- 2. 表格 / 状态区 ----
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
                        ObjectListEmptyState(message = stringResource("rocketmq_ui_msg_browse_empty"))
                    filtered.isEmpty() ->
                        ObjectListEmptyState(message = stringResource("rocketmq_ui_msg_browse_filtered_empty"))
                    else -> MessageTable(
                        messages = filtered,
                        onCopy = ::showCopied,
                        onDetail = { detailMessage = it },
                    )
                }
            }
            ThemedSnackbarHost(
                snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // ---- 3. 底部：只读提示 + 加载更多 ----
        if (ready != null && ready.messages.isNotEmpty()) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = RMSG_ROW_HPADDING, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("rocketmq_ui_msg_browse_no_commit_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (ready.nextBeginOffset != null) {
                    PrimaryButton(onClick = ::loadMore, enabled = !ready.loadingMore) {
                        Text(
                            stringResource(
                                if (ready.loadingMore) {
                                    "rocketmq_ui_msg_browse_loading"
                                } else {
                                    "rocketmq_ui_msg_browse_load_more"
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    detailMessage?.let { message ->
        RocketMqMessageDetailDialog(
            message = message,
            onDismiss = { detailMessage = null },
        )
    }
}

/**
 * 紧凑数字输入框（36dp 高，BasicTextField 自绘边框）：m3 OutlinedTextField 默认
 * 56dp，与同行的 Queue 下拉按钮（约 36dp）比例失衡 —— 这里压到同高并垂直居中。
 */
@Composable
private fun CompactNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    width: androidx.compose.ui.unit.Dp,
    hint: String? = null,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .width(width)
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 8.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
                inner()
            }
        },
    )
}

/** Queue 下拉（PrimaryButton + DropdownMenu，与 KafkaMessageBrowseTab 的 DropdownButton 同款）。 */
@Composable
private fun QueueDropdown(
    selected: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SecondaryButton(
            onClick = { expanded = true },
            standardMinWidth = false,
        ) {
            Text(stringResource("rocketmq_ui_msg_browse_queue").withArg(selected))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { id ->
                DropdownMenuItem(
                    text = { Text(stringResource("rocketmq_ui_msg_browse_queue").withArg(id)) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

/* ------------------------------- 表格 ------------------------------- */

@Composable
private fun MessageTable(
    messages: List<RocketMqMessage>,
    onCopy: (String) -> Unit,
    onDetail: (RocketMqMessage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = RMSG_ROW_HPADDING, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TableHeaderText(stringResource("rocketmq_ui_msg_browse_col_queue"), Modifier.width(RMSG_COL_QUEUE))
            TableHeaderText(stringResource("rocketmq_ui_msg_browse_col_offset"), Modifier.width(RMSG_COL_OFFSET))
            TableHeaderText(stringResource("rocketmq_ui_msg_browse_col_msgid"), Modifier.width(RMSG_COL_MSGID))
            TableHeaderText(stringResource("rocketmq_ui_msg_browse_col_tags"), Modifier.width(RMSG_COL_TAGS))
            TableHeaderText(stringResource("rocketmq_ui_msg_browse_col_keys"), Modifier.width(RMSG_COL_KEYS))
            TableHeaderText(stringResource("rocketmq_ui_msg_browse_col_value"), Modifier.weight(1f))
            TableHeaderText(stringResource("rocketmq_ui_msg_browse_col_timestamp"), Modifier.width(RMSG_COL_TIMESTAMP))
            TableHeaderText(stringResource("rocketmq_ui_msg_browse_col_size"), Modifier.width(RMSG_COL_SIZE))
            TableHeaderText(stringResource("rocketmq_ui_msg_browse_col_actions"), Modifier.width(RMSG_COL_ACTIONS))
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            // ByteArray 字段是引用相等 —— key 用 "queue:offset" 字符串，绝不用 data class。
            items(messages, key = { "${it.queueId}:${it.offset}" }) { message ->
                MessageRow(
                    message = message,
                    onCopy = onCopy,
                    onDetail = onDetail,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: RocketMqMessage,
    onCopy: (String) -> Unit,
    onDetail: (RocketMqMessage) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = if (hovered) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(background)
            .padding(horizontal = RMSG_ROW_HPADDING, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Offset：等宽 + hover 全串 + 点击复制。
        CopyableTextCell(
            text = message.offset.toString(),
            modifier = Modifier.width(RMSG_COL_OFFSET),
            onCopy = onCopy,
        )
        // MsgId：等宽 + hover 全串 + 点击复制。
        CopyableTextCell(
            text = message.msgId,
            modifier = Modifier.width(RMSG_COL_MSGID),
            onCopy = onCopy,
        )
        CopyablePreviewCell(
            text = message.tags,
            hex = null,
            modifier = Modifier.width(RMSG_COL_TAGS),
            onCopy = onCopy,
        )
        CopyablePreviewCell(
            text = message.keys,
            hex = null,
            modifier = Modifier.width(RMSG_COL_KEYS),
            onCopy = onCopy,
        )
        // Value 单元格：utf8 预览；疑似二进制显示 hex 前段。
        if (message.valueProbablyBinary) {
            CopyablePreviewCell(
                text = null,
                hex = message.valueHex,
                modifier = Modifier.weight(1f),
                onCopy = onCopy,
            )
        } else {
            CopyablePreviewCell(
                text = message.valuePreview,
                hex = null,
                modifier = Modifier.weight(1f),
                onCopy = onCopy,
            )
        }
        Text(
            text = formatTimestamp(message.storeTimestamp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(RMSG_COL_TIMESTAMP),
        )
        Text(
            text = formatSize(message.valueSize.toLong()),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(RMSG_COL_SIZE),
        )
        Row(modifier = Modifier.width(RMSG_COL_ACTIONS)) {
            TableRowActionIcon(
                icon = Icons.Default.Info,
                tooltip = stringResource("rocketmq_ui_msg_browse_col_detail"),
                onClick = { onDetail(message) },
            )
        }
    }
}

/** 等宽文本单元格（Offset / MsgId）：hover 全文 + 点击复制。 */
@Composable
private fun CopyableTextCell(
    text: String,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit,
) {
    com.mqclient.uicommon.AppTooltip(text = text, modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onCopy(text) }
                .padding(vertical = 2.dp),
        )
    }
}

/** 预览单元格：utf8 预览，null 时切 hex；hover 全文 + 点击复制。 */
@Composable
private fun CopyablePreviewCell(
    text: String?,
    hex: String?,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit,
) {
    val display = text ?: hex ?: ""
    val copied = text ?: hex ?: ""
    com.mqclient.uicommon.AppTooltip(text = display, modifier = modifier) {
        Text(
            text = display,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onCopy(copied) }
                .padding(vertical = 2.dp),
        )
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
            text = stringResource("rocketmq_ui_msg_browse_loading"),
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
            text = stringResource("rocketmq_ui_msg_browse_failed").withArg(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton(onClick = onRetry) { Text(stringResource("common_retry")) }
    }
}

/* ------------------------------- 纯函数 ------------------------------- */

/** 本地过滤：msgId / tags / keys / value 预览 / hex / 时间文本的大小写不敏感 contains。 */
private fun filterMessages(
    messages: List<RocketMqMessage>,
    search: String,
): List<RocketMqMessage> {
    val query = search.trim()
    if (query.isEmpty()) return messages
    return messages.filter { message ->
        message.msgId.contains(query, ignoreCase = true) ||
            message.tags?.contains(query, ignoreCase = true) == true ||
            message.keys?.contains(query, ignoreCase = true) == true ||
            message.valuePreview.contains(query, ignoreCase = true) ||
            message.valueHex.contains(query, ignoreCase = true) ||
            formatTimestamp(message.storeTimestamp).contains(query, ignoreCase = true) ||
            message.offset.toString().contains(query)
    }
}
