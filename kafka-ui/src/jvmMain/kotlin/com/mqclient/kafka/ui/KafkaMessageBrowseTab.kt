@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.mqclient.kafka.ui

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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
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
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.KafkaMessageSummary
import com.mqclient.kafka.KafkaReadStart
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.file.saveFileBytes
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 消息表格的固定列宽：Offset / Key / Timestamp / Partition / 操作（Value 弹性）。
 *  命名带 MSG_ 前缀：同包的 KafkaGroupDetailDialog 有自己的 COL_* 常量，避免撞名。 */
internal val MSG_COL_OFFSET = 90.dp
internal val MSG_COL_KEY = 160.dp
internal val MSG_COL_TIMESTAMP = 160.dp
internal val MSG_COL_PARTITION = 70.dp
internal val MSG_COL_ACTIONS = 80.dp
internal val ROW_HPADDING = 16.dp

/** 表格时间列的展示格式（epoch millis → 本地时区，毫秒粒度）。 */
internal val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/** 大小列：< 1KB 显示字节，否则 KB（1 位小数）；浏览 / 发送两页共用。 */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    else -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
}

/** 时间戳列展示：epoch millis → 本地时区文本；<= 0（无时间戳 / epoch 0）显示 "-"。 */
internal fun formatTimestamp(millis: Long): String =
    if (millis <= 0) {
        "-"
    } else {
        TIME_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }

/**
 * 轻量 hover 提示（同 ObjectCard 的 ActionIconButton 样式）；浏览 / 发送 / 详情共用。
 */
@Composable
internal fun HoverTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        delayMillis = 400,
        modifier = modifier,
        content = content,
    )
}

/** 表格表头单元格文本。 */
@Composable
internal fun TableHeaderText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

/** 表格行内的 24dp 图标操作钮（ObjectCard 的 ActionIconButton 同款尺寸与禁用降透明）。 */
@Composable
internal fun RowActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tooltip: String,
    enabled: Boolean = true,
    executing: Boolean = false,
    onClick: () -> Unit = {},
) {
    HoverTooltip(tooltip) {
        IconButton(
            onClick = onClick,
            enabled = enabled && !executing,
            modifier = Modifier.size(24.dp),
        ) {
            if (executing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = tooltip,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (enabled) 1f else 0.38f),
                )
            }
        }
    }
}

/**
 * 浏览起点四选一。OFFSET 仅单分区模式可用（Kafka 的 offset 是分区内的概念）。
 */
private enum class StartMode(val labelKey: String) {
    LATEST("kafka_ui_msg_browse_start_latest"),
    EARLIEST("kafka_ui_msg_browse_start_earliest"),
    OFFSET("kafka_ui_msg_browse_start_offset"),
    TIMESTAMP("kafka_ui_msg_browse_start_timestamp"),
}

/** 浏览页三态：初始加载 / 失败（带原因 + 重试）/ 就绪。 */
private sealed interface BrowseUiState {
    data object Loading : BrowseUiState
    data class Failed(val reason: String) : BrowseUiState
    data class Ready(
        val messages: List<KafkaMessageSummary>,
        /** 分区 → 下一条 offset；null = 已耗尽（无游标可续）。 */
        val nextCursor: Map<Int, Long>?,
        val loadingMore: Boolean = false,
        val refreshing: Boolean = false,
    ) : BrowseUiState
}

/**
 * 「浏览消息」Tab：顶部起点选择区（Partition / 起点 / 条数 —— Kafka 特有，MQ 没有）+
 * 消息表格 + 底部（已加载 + 加载更多 + 不提交提示）。数据经
 * [KafkaMessageService.readMessages]（assign + seek + poll，**不提交 offset**，不影响
 * 任何消费组进度）；搜索只做已加载列表的本地过滤，不重新读。
 *
 * 打开即按默认条件（全部分区 + 最新往前 50 条）读首页；改条件不自动重读，点「读取」
 * 才生效（时间戳 / offset 的半成品输入不触发半吊子请求）。
 */
@Composable
internal fun KafkaMessageBrowseTab(
    connectionId: String,
    topicName: String,
    partitionCount: Int?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    // ---- 起点选择区状态 ----
    var selectedPartition by remember(topicName) { mutableStateOf(-1) } // -1 = 全部分区
    var startMode by remember(topicName) { mutableStateOf(StartMode.LATEST) }
    var offsetText by remember(topicName) { mutableStateOf("") }
    var pickedTime by remember(topicName) { mutableStateOf<LocalDateTime?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }
    var countText by remember(topicName) { mutableStateOf("50") }
    var retryKey by remember(topicName) { mutableIntStateOf(0) }

    var uiState by remember(topicName) { mutableStateOf<BrowseUiState>(BrowseUiState.Loading) }
    var detailMessage by remember(topicName) { mutableStateOf<KafkaMessageSummary?>(null) }
    // 行操作（下载）进行中的行 key（"p:o"）：同一时刻只允许一个行操作。
    var rowActionKey by remember { mutableStateOf<String?>(null) }

    /** 当前选择区 → service 的读取请求。OFFSET 仅单分区模式可用（Kafka 的 offset 是分区内概念）。 */
    fun startFrom(): KafkaReadStart = when (startMode) {
        StartMode.LATEST -> KafkaReadStart.Latest(count = countText.toIntOrNull() ?: 50)
        StartMode.EARLIEST -> KafkaReadStart.Earliest(count = countText.toIntOrNull() ?: 50)
        StartMode.OFFSET ->
            if (selectedPartition >= 0) {
                KafkaReadStart.AtOffset(
                    partition = selectedPartition,
                    offset = offsetText.toLongOrNull() ?: 0L,
                )
            } else {
                KafkaReadStart.Latest(count = countText.toIntOrNull() ?: 50)
            }
        StartMode.TIMESTAMP -> KafkaReadStart.AtTimestamp(
            epochMillis = (pickedTime ?: LocalDateTime.now())
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
    }

    fun count(): Int = (countText.toIntOrNull() ?: 50).coerceIn(1, 500)

    fun showCopied(text: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbarHostState.showSnackbar(stringResource("kafka_ui_msg_copied")) }
    }

    /** 读首页（初始加载与「读取」按钮共用，retryKey++ 触发重跑）。 */
    LaunchedEffect(topicName, retryKey) {
        uiState = BrowseUiState.Loading
        try {
            val service = KafkaModule.messageServiceOrNull()
                ?: throw IllegalStateException(stringResource("kafka_ui_send_service_not_ready"))
            val page = service.readMessages(
                connectionId = connectionId,
                topic = topicName,
                partition = selectedPartition.takeIf { it >= 0 },
                startFrom = startFrom(),
                count = count(),
            )
            uiState = BrowseUiState.Ready(page.messages, page.nextCursor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = BrowseUiState.Failed(e.message ?: e::class.simpleName ?: "unknown")
        }
    }

    fun loadMore() {
        val current = uiState as? BrowseUiState.Ready ?: return
        val cursor = current.nextCursor ?: return
        if (current.loadingMore || current.refreshing) return
        scope.launch {
            uiState = current.copy(loadingMore = true)
            try {
                val service = KafkaModule.messageServiceOrNull()
                    ?: throw IllegalStateException("服务未初始化")
                val page = service.readMoreMessages(connectionId, topicName, cursor, count())
                val ready = uiState as? BrowseUiState.Ready ?: return@launch
                uiState = ready.copy(
                    messages = ready.messages + page.messages,
                    nextCursor = page.nextCursor,
                    loadingMore = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (uiState as? BrowseUiState.Ready)?.let { uiState = it.copy(loadingMore = false) }
                scope.launch {
                    snackbarHostState.showSnackbar(e.message ?: e::class.simpleName ?: "unknown")
                }
            }
        }
    }

    /** 下载：现查完整消息体再弹「另存为」（列表行只有预览字节）。 */
    fun downloadMessage(message: KafkaMessageSummary) {
        if (rowActionKey != null) return
        scope.launch {
            rowActionKey = "${message.partition}:${message.offset}"
            try {
                val service = KafkaModule.messageServiceOrNull()
                    ?: throw IllegalStateException("服务未初始化")
                val detail = service.readMessageDetail(connectionId, topicName, message.partition, message.offset)
                val saved = saveFileBytes(
                    "msg_${topicName}_p${message.partition}_o${message.offset}.bin",
                    "bin",
                    detail.valueBytes,
                )
                if (saved != null) {
                    snackbarHostState.showSnackbar(stringResource("kafka_ui_msg_saved").withArg(saved))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    stringResource("kafka_ui_msg_save_failed").withArg(e.message ?: ""),
                )
            }
            rowActionKey = null
        }
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
        // ---- 1. 起点选择区（Kafka 特有）----
        StartSelectorRow(
            partitionCount = partitionCount,
            selectedPartition = selectedPartition,
            onPartitionChange = { selectedPartition = it },
            startMode = startMode,
            onStartModeChange = { startMode = it },
            offsetText = offsetText,
            onOffsetChange = { offsetText = it.filter(Char::isDigit).take(19) },
            pickedTime = pickedTime,
            onPickTime = { showTimePicker = true },
            onClearTime = { pickedTime = null },
            countText = countText,
            onCountChange = { countText = it.filter(Char::isDigit).take(4) },
            onRead = { retryKey++ },
            reading = uiState == BrowseUiState.Loading,
        )
        HorizontalDivider()
        // 搜索行（独立一行：选择区已占满宽度）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HPADDING, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactSearchField(
                value = rawSearch,
                onValueChange = { rawSearch = it },
                placeholder = stringResource("kafka_ui_msg_browse_search"),
                modifier = Modifier.weight(1f),
            )
            if (ready != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource("kafka_ui_msg_browse_loaded").withArg(ready.messages.size),
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
                        ObjectListEmptyState(message = stringResource("kafka_ui_msg_browse_empty"))
                    filtered.isEmpty() ->
                        ObjectListEmptyState(message = stringResource("kafka_ui_msg_browse_filtered_empty"))
                    else -> MessageTable(
                        messages = filtered,
                        onCopy = ::showCopied,
                        actionInProgressKey = rowActionKey,
                        onDownload = ::downloadMessage,
                        onDetail = { detailMessage = it },
                    )
                }
            }
            ThemedSnackbarHost(
                snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // ---- 3. 底部：加载更多 + 不提交提示 ----
        if (ready != null && ready.messages.isNotEmpty()) {
            HorizontalDivider()
            BrowseBottomBar(
                nextCursor = ready.nextCursor,
                loadingMore = ready.loadingMore,
                onLoadMore = ::loadMore,
            )
        }
    }

    if (showTimePicker) {
        KafkaDateTimePickerDialog(
            initial = pickedTime,
            onConfirm = {
                pickedTime = it
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }

    detailMessage?.let { message ->
        KafkaMessageDetailDialog(
            connectionId = connectionId,
            topicName = topicName,
            summary = message,
            onDismiss = { detailMessage = null },
        )
    }
}

/* ------------------------------ 起点选择区 ------------------------------ */

@Composable
private fun StartSelectorRow(
    partitionCount: Int?,
    selectedPartition: Int,
    onPartitionChange: (Int) -> Unit,
    startMode: StartMode,
    onStartModeChange: (StartMode) -> Unit,
    offsetText: String,
    onOffsetChange: (String) -> Unit,
    pickedTime: LocalDateTime?,
    onPickTime: () -> Unit,
    onClearTime: () -> Unit,
    countText: String,
    onCountChange: (String) -> Unit,
    onRead: () -> Unit,
    reading: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HPADDING, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Partition 下拉：-1 = 全部分区。
        DropdownButton(
            selected = if (selectedPartition < 0) {
                stringResource("kafka_ui_msg_browse_partition_all")
            } else {
                selectedPartition.toString()
            },
            options = buildList {
                add(stringResource("kafka_ui_msg_browse_partition_all") to -1)
                if (partitionCount != null) {
                    (0 until partitionCount).forEach { p -> add(p.toString() to p) }
                }
            },
            onSelect = onPartitionChange,
        )
        // 起点四选一。
        DropdownButton(
            selected = stringResource(startMode.labelKey),
            options = StartMode.entries.map { stringResource(it.labelKey) to it },
            onSelect = onStartModeChange,
        )
        when (startMode) {
            StartMode.OFFSET -> OutlinedSmallField(
                value = offsetText,
                onValueChange = onOffsetChange,
                // offset 是分区内概念：全部分区模式下禁用并提示。
                enabled = selectedPartition >= 0,
            )
            StartMode.TIMESTAMP -> {
                PressableButton(
                    onClick = onPickTime,
                    standardMinWidth = false,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(pickedTime?.format(DISPLAY_TIME_FORMATTER) ?: stringResource("kafka_ui_msg_browse_start_time_hint"))
                }
                if (pickedTime != null) {
                    IconButton(onClick = onClearTime, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
            else -> Unit
        }
        OutlinedSmallField(
            value = countText,
            onValueChange = onCountChange,
            enabled = true,
            modifier = Modifier.width(84.dp),
        )
        PressableButton(onClick = onRead, enabled = !reading) {
            Text(
                stringResource(
                    if (reading) "kafka_ui_msg_browse_loading" else "kafka_ui_msg_browse_read",
                ),
            )
        }
    }
}

/** 选择区通用下拉（PressableButton + DropdownMenu，与 MQ 的 DropdownSelector 同款）。 */
@Composable
private fun <T> DropdownButton(
    selected: String,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PressableButton(
            onClick = { expanded = true },
            standardMinWidth = false,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(selected)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

/** 选择区的小号数字输入框。 */
@Composable
private fun OutlinedSmallField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = modifier.width(140.dp),
    )
}

/** 自定义时间的展示格式（选择器粒度到分钟）。 */
private val DISPLAY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 日期 + 时间两步选择弹窗（复刻 MQ 的 DateTimePickerDialog：M3 DatePicker 桌面宽度坑）。 */
@Composable
private fun KafkaDateTimePickerDialog(
    initial: LocalDateTime?,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit,
) {
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
        title = { Text(stringResource("kafka_ui_msg_browse_start_timestamp")) },
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
                ) { Text(stringResource("common_ok")) }
                PickerPhase.TIME -> PressableButton(onClick = {
                    val millis = dateState.selectedDateMillis
                    if (millis == null) {
                        onDismiss()
                    } else {
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onConfirm(LocalDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute)))
                    }
                }) { Text(stringResource("common_ok")) }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phase == PickerPhase.TIME) {
                    PressableButton(
                        onClick = { phase = PickerPhase.DATE },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { Text(stringResource("common_cancel")) }
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

/* ------------------------------- 表格 ------------------------------- */

@Composable
private fun MessageTable(
    messages: List<KafkaMessageSummary>,
    onCopy: (String) -> Unit,
    actionInProgressKey: String?,
    onDownload: (KafkaMessageSummary) -> Unit,
    onDetail: (KafkaMessageSummary) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = ROW_HPADDING, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TableHeaderText(stringResource("kafka_ui_msg_browse_col_offset"), Modifier.width(MSG_COL_OFFSET))
            TableHeaderText(stringResource("kafka_ui_msg_browse_col_key"), Modifier.width(MSG_COL_KEY))
            TableHeaderText(stringResource("kafka_ui_msg_browse_col_value"), Modifier.weight(1f))
            TableHeaderText(stringResource("kafka_ui_msg_browse_col_timestamp"), Modifier.width(MSG_COL_TIMESTAMP))
            TableHeaderText(stringResource("kafka_ui_msg_browse_col_partition"), Modifier.width(MSG_COL_PARTITION))
            TableHeaderText(stringResource("kafka_ui_msg_browse_col_actions"), Modifier.width(MSG_COL_ACTIONS))
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            // ByteArray 字段是引用相等 —— key 用 "分区:offset" 字符串，绝不用 data class。
            items(messages, key = { "${it.partition}:${it.offset}" }) { message ->
                MessageRow(
                    message = message,
                    onCopy = onCopy,
                    actionInProgress = actionInProgressKey == "${message.partition}:${message.offset}",
                    anyActionInProgress = actionInProgressKey != null,
                    onDownload = onDownload,
                    onDetail = onDetail,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: KafkaMessageSummary,
    onCopy: (String) -> Unit,
    actionInProgress: Boolean,
    anyActionInProgress: Boolean,
    onDownload: (KafkaMessageSummary) -> Unit,
    onDetail: (KafkaMessageSummary) -> Unit,
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
        // Offset：等宽 + hover 全串 + 点击复制（IdCell 同款交互）。
        HoverTooltip(message.offset.toString(), Modifier.width(MSG_COL_OFFSET)) {
            Text(
                text = message.offset.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onCopy(message.offset.toString()) }
                    .padding(vertical = 2.dp),
            )
        }
        CopyablePreviewCell(
            text = message.keyPreview,
            hex = message.keyHex,
            modifier = Modifier.width(MSG_COL_KEY),
            onCopy = onCopy,
        )
        ValueCell(message, Modifier.weight(1f), onCopy)
        Text(
            text = formatTimestamp(message.timestamp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(MSG_COL_TIMESTAMP),
        )
        Text(
            text = message.partition.toString(),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(MSG_COL_PARTITION),
        )
        Row(modifier = Modifier.width(MSG_COL_ACTIONS)) {
            RowActionIcon(
                icon = Icons.Default.Info,
                tooltip = stringResource("kafka_ui_msg_browse_col_detail"),
                enabled = !anyActionInProgress,
                onClick = { onDetail(message) },
            )
            RowActionIcon(
                icon = Icons.Default.Download,
                tooltip = stringResource("kafka_ui_msg_browse_download"),
                enabled = !anyActionInProgress,
                executing = actionInProgress,
                onClick = { onDownload(message) },
            )
        }
    }
}

/** Key 单元格：utf8 预览，疑似二进制时切 hex；hover 全文 + 点击复制。 */
@Composable
private fun CopyablePreviewCell(
    text: String?,
    hex: String?,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit,
) {
    val display = text ?: hex ?: ""
    val copied = text ?: hex ?: ""
    HoverTooltip(display, modifier) {
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

/** Value 单元格：utf8 预览；疑似二进制显示 hex 前段 + 「HEX」标记。 */
@Composable
private fun ValueCell(
    message: KafkaMessageSummary,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit,
) {
    if (message.valueProbablyBinary) {
        CopyablePreviewCell(text = null, hex = message.valueHex, modifier = modifier, onCopy = onCopy)
    } else {
        CopyablePreviewCell(text = message.valuePreview, hex = null, modifier = modifier, onCopy = onCopy)
    }
}

/* ------------------------------- 底部 ------------------------------- */

@Composable
private fun BrowseBottomBar(
    nextCursor: Map<Int, Long>?,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HPADDING, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource("kafka_ui_msg_browse_no_commit_hint"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (nextCursor != null) {
            PressableButton(onClick = onLoadMore, enabled = !loadingMore) {
                Text(
                    stringResource(
                        if (loadingMore) "kafka_ui_msg_browse_loading" else "kafka_ui_msg_browse_load_more",
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
            text = stringResource("kafka_ui_msg_browse_loading"),
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
            text = stringResource("kafka_ui_msg_browse_failed").withArg(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PressableButton(onClick = onRetry) { Text(stringResource("common_retry")) }
    }
}

/* ------------------------------- 纯函数 ------------------------------- */

/** 本地过滤：key 预览 / value 预览 / hex / 时间文本的大小写不敏感 contains。 */
private fun filterMessages(
    messages: List<KafkaMessageSummary>,
    search: String,
): List<KafkaMessageSummary> {
    val query = search.trim()
    if (query.isEmpty()) return messages
    return messages.filter { message ->
        message.keyPreview?.contains(query, ignoreCase = true) == true ||
            message.valuePreview.contains(query, ignoreCase = true) ||
            message.keyHex?.contains(query, ignoreCase = true) == true ||
            message.valueHex.contains(query, ignoreCase = true) ||
            formatTimestamp(message.timestamp).contains(query, ignoreCase = true) ||
            message.offset.toString().contains(query)
    }
}
