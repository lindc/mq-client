package com.mqclient.ibmmq.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.messaging.SendDefaults
import com.mqclient.ibmmq.messaging.SendResult
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单文件预览的截断阈值：> 100KB 只显示前 10000 字符（发送仍发完整内容，除非编辑过）。 */
private const val TRUNCATION_THRESHOLD_BYTES = 100_000
private const val PREVIEW_MAX_CHARS = 10_000

private val SuccessGreen = Color(0xFF4CAF50)
private val FailedRed = Color(0xFFF44336)
private val WaitingGray = Color(0xFF9E9E9E)

/** 发送任务状态机：等待 → 发送中 → 成功 / 失败（失败可重试回发送中）。 */
enum class SendStatus { WAITING, SENDING, SUCCESS, FAILED }

/** 一个待发送文件的任务；[content] 在选择时已读入（读失败的文件不进列表）。 */
data class SendTask(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val content: ByteArray,
    val status: SendStatus = SendStatus.WAITING,
    val messageId: String? = null,
    val sentAt: Long? = null,
    val errorMessage: String? = null,
)

/** 多文件「发送全部」一轮的汇总。 */
data class SendBatchSummary(val total: Int, val success: Int, val failed: Int)

/**
 * 「发送消息」Tab 的状态持有者（ibm-mq-ui 内部）：
 *
 * **实例挂在 QueueMessagesDialog 层**（而不是 Tab 里）——切到「浏览消息」Tab 时
 * 发送协程与任务列表都存活，回来还能看到结果；只有对话框真正关闭才销毁。
 * 发送按任务顺序**串行**执行（[scope] 是 dialog 级 rememberCoroutineScope），
 * 文件读取与 MQ put 都在 [Dispatchers.IO]（service 层内部包裹），状态写回走
 * Compose snapshot（主线程安全）。
 */
@Stable
internal class SendMessagesState(
    private val connectionId: String,
    private val queueName: String,
    private val scope: CoroutineScope,
) {

    var tasks by mutableStateOf<List<SendTask>>(emptyList())
        private set

    /** 任一发送流程执行中（单文件 / 全部 / 单条重试共用，互斥）。 */
    var busy by mutableStateOf(false)
        private set

    /** 本轮「发送全部」已处理条数（进度 n/N）。 */
    var processedCount by mutableIntStateOf(0)
        private set

    var batchTotal by mutableIntStateOf(0)
        private set

    var batchSummary by mutableStateOf<SendBatchSummary?>(null)
        private set

    /** 单文件模式最近一次发送结果（null = 还没发过 / 刚清掉）。 */
    var singleOutcome by mutableStateOf<SendResult?>(null)
        private set

    /** 单文件编辑缓冲；null = 未编辑过，发送原始文件字节。 */
    var editingContent by mutableStateOf<String?>(null)

    // ---- 高级属性（默认收起）----
    var correlationId by mutableStateOf("")
    var replyToQueue by mutableStateOf("")

    /** MQPER_PERSISTENT（默认）/ MQPER_NOT_PERSISTENT。 */
    var persistence by mutableIntStateOf(SendDefaults.PERSISTENCE_PERSISTENT)
    var priority by mutableIntStateOf(4)

    /** 过期秒数；<= 0 = 不过期。 */
    var expirySeconds by mutableStateOf("0")

    val singleTask: SendTask? get() = tasks.singleOrNull()

    /**
     * 选完文件后装配任务列表（**替换**语义），IO 线程读文件；读失败的文件跳过，
     * 名字返回给调用方弹提示。空文件（0 字节）允许进列表——发送前给出「消息体为空」提示。
     */
    suspend fun loadFiles(paths: List<String>): List<String> = withContext(Dispatchers.IO) {
        val loaded = mutableListOf<SendTask>()
        val failed = mutableListOf<String>()
        for (path in paths) {
            val file = java.io.File(path)
            runCatching { file.readBytes() }
                .onSuccess { bytes ->
                    loaded += SendTask(
                        id = UUID.randomUUID().toString(),
                        fileName = file.name,
                        fileSize = bytes.size.toLong(),
                        content = bytes,
                    )
                }
                .onFailure { failed += file.name }
        }
        tasks = loaded
        editingContent = null
        singleOutcome = null
        batchSummary = null
        processedCount = 0
        batchTotal = 0
        failed
    }

    /** 清空已选文件与全部状态（工具栏「清空」）。 */
    fun clear() {
        tasks = emptyList()
        editingContent = null
        singleOutcome = null
        batchSummary = null
        processedCount = 0
        batchTotal = 0
    }

    /** 单文件发送（成功后再点即重发；空消息体经 [onEmptyBodyWarning] 提示后照发）。 */
    fun sendSingle(onEmptyBodyWarning: () -> Unit) {
        val task = singleTask ?: return
        if (busy) return
        if (contentOf(task).isEmpty()) onEmptyBodyWarning()
        scope.launch {
            busy = true
            singleOutcome = null
            tasks = listOf(task.copy(status = SendStatus.SENDING))
            val result = doSend(task)
            tasks = listOf(task.withResult(result))
            singleOutcome = result
            busy = false
        }
    }

    /** 「发送全部」：按列表顺序串行发 WAITING 项，逐条刷新状态，完成后汇总。 */
    fun sendAll() {
        if (busy) return
        val targets = tasks.filter { it.status == SendStatus.WAITING }
        if (targets.isEmpty()) return
        scope.launch {
            busy = true
            batchSummary = null
            batchTotal = targets.size
            processedCount = 0
            var ok = 0
            var fail = 0
            for (target in targets) {
                updateTask(target.id) { it.copy(status = SendStatus.SENDING, errorMessage = null) }
                val result = doSend(target)
                updateTask(target.id) { it.withResult(result) }
                if (result.success) ok++ else fail++
                processedCount++
            }
            batchSummary = SendBatchSummary(total = targets.size, success = ok, failed = fail)
            busy = false
        }
    }

    /** 失败项单独重试：只重发该条，不影响其他任务的状态。 */
    fun retryTask(id: String) {
        if (busy) return
        val task = tasks.firstOrNull { it.id == id } ?: return
        if (task.status != SendStatus.FAILED) return
        scope.launch {
            updateTask(id) { it.copy(status = SendStatus.SENDING, errorMessage = null) }
            val result = doSend(task)
            updateTask(id) { it.withResult(result) }
        }
    }

    fun removeTask(id: String) {
        if (busy) return
        tasks = tasks.filterNot { it.id == id }
    }

    fun canRemove(task: SendTask): Boolean = !busy && task.status == SendStatus.WAITING

    /* ------------------------------ internals ------------------------------ */

    private fun updateTask(id: String, transform: (SendTask) -> SendTask) {
        tasks = tasks.map { if (it.id == id) transform(it) else it }
    }

    /** 实际发送的字节：单文件模式编辑过 → 编辑文本（UTF-8）；否则文件原始内容。 */
    private fun contentOf(task: SendTask): ByteArray =
        if (tasks.size == 1 && editingContent != null) {
            editingContent!!.toByteArray(Charsets.UTF_8)
        } else {
            task.content
        }

    private suspend fun doSend(task: SendTask): SendResult {
        val seconds = expirySeconds.trim().toIntOrNull() ?: 0
        val expiryTenths = if (seconds <= 0) SendDefaults.EXPIRY_UNLIMITED_TENTHS else seconds * 10
        return IbmMQModule.serviceOrNull()?.sendMessage(
            connectionId = connectionId,
            queueName = queueName,
            content = contentOf(task),
            correlationId = correlationId.trim().takeIf { it.isNotEmpty() },
            replyToQueue = replyToQueue.trim().takeIf { it.isNotEmpty() },
            persistence = persistence,
            priority = priority,
            expiryTenths = expiryTenths,
        ) ?: SendResult.failure(null, "服务未初始化")
    }

    private fun SendTask.withResult(result: SendResult): SendTask = if (result.success) {
        copy(status = SendStatus.SUCCESS, messageId = result.messageId, sentAt = result.sentAt, errorMessage = null)
    } else {
        copy(status = SendStatus.FAILED, errorMessage = result.errorMessage ?: "Unknown")
    }
}

/**
 * 「发送消息」Tab：顶部工具栏（选择文件 / 数量 / 清空）+ 内容区（空态 /
 * 单文件可编辑预览 + 高级属性 / 多文件任务表格）+ 底部（结果 / 汇总 + 发送按钮）。
 * 状态在 [SendMessagesState]（dialog 层持有），本 Composable 基本是纯视图。
 */
@Composable
internal fun MessageSendTab(
    state: SendMessagesState,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val pickScope = rememberCoroutineScope()

    fun pickAndLoad() {
        pickScope.launch {
            val paths = pickFiles(stringResource("msg_send_pick_title"))
            if (paths.isEmpty()) return@launch
            val failed = state.loadFiles(paths)
            failed.forEach { name ->
                snackbarHostState.showSnackbar(stringResource("msg_send_read_failed").withArg(name))
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---- 1. 工具栏 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HPADDING, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PressableButton(onClick = ::pickAndLoad) { Text(stringResource("msg_send_pick_file")) }
            if (state.tasks.isNotEmpty()) {
                Text(
                    text = stringResource("msg_send_files_selected").withArg(state.tasks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            PressableButton(
                onClick = state::clear,
                enabled = state.tasks.isNotEmpty(),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("msg_send_clear")) }
        }
        HorizontalDivider()

        // ---- 2. 内容区 ----
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.tasks.isEmpty() ->
                    ObjectListEmptyState(message = stringResource("msg_send_empty"))
                state.tasks.size == 1 -> SingleSendPane(state)
                else -> MultiSendPane(state)
            }
            SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }

        HorizontalDivider()

        // ---- 3. 底部：结果 / 汇总 + 发送按钮 ----
        SendBottomBar(
            state = state,
            onEmptyBodyWarning = {
                pickScope.launch { snackbarHostState.showSnackbar(stringResource("msg_send_empty_body")) }
            },
        )
    }
}

/* --------------------------- 单文件模式 --------------------------- */

@Composable
private fun SingleSendPane(state: SendMessagesState) {
    val task = state.singleTask ?: return
    val rawText = remember(task.id) { task.content.toString(Charsets.UTF_8) }
    val truncated = task.fileSize > TRUNCATION_THRESHOLD_BYTES
    val display = state.editingContent
        ?: rawText.takeIf { !truncated || it.length <= PREVIEW_MAX_CHARS }
        ?: rawText.take(PREVIEW_MAX_CHARS)

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = display,
            onValueChange = { state.editingContent = it },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = ROW_HPADDING, vertical = 8.dp),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        if (truncated && state.editingContent == null) {
            Text(
                text = stringResource("msg_send_preview_truncated"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ROW_HPADDING, vertical = 4.dp),
            )
        }
        AdvancedPropsSection(state)
    }
}

/** 高级属性（默认收起）：CorrelationId / ReplyToQueue / 持久化 / 优先级 / 过期时间。 */
@Composable
private fun AdvancedPropsSection(state: SendMessagesState) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HPADDING, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource("msg_send_advanced"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.correlationId,
                    onValueChange = { state.correlationId = it },
                    label = { Text(stringResource("msg_send_correlation_id")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.replyToQueue,
                    onValueChange = { state.replyToQueue = it },
                    label = { Text(stringResource("msg_send_reply_to_queue")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PropLabel(stringResource("msg_send_persistence"))
                    DropdownSelector(
                        selected = stringResource(
                            if (state.persistence == SendDefaults.PERSISTENCE_PERSISTENT) {
                                "msg_persistence_persistent"
                            } else {
                                "msg_persistence_non_persistent"
                            },
                        ),
                        options = listOf(
                            stringResource("msg_persistence_persistent") to SendDefaults.PERSISTENCE_PERSISTENT,
                            stringResource("msg_persistence_non_persistent") to SendDefaults.PERSISTENCE_NOT_PERSISTENT,
                        ),
                        onSelect = { state.persistence = it },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PropLabel(stringResource("msg_send_priority"))
                    DropdownSelector(
                        selected = state.priority.toString(),
                        options = (0..9).map { it.toString() to it },
                        onSelect = { state.priority = it },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PropLabel(stringResource("msg_send_expiry"))
                    OutlinedTextField(
                        value = state.expirySeconds,
                        onValueChange = { state.expirySeconds = it.filter(Char::isDigit).take(9) },
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                    )
                    Text(
                        text = stringResource("msg_send_expiry_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PropLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(110.dp),
    )
}

@Composable
private fun DropdownSelector(
    selected: String,
    options: List<Pair<String, Int>>,
    onSelect: (Int) -> Unit,
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

/* --------------------------- 多文件模式 --------------------------- */

@Composable
private fun MultiSendPane(state: SendMessagesState) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = ROW_HPADDING, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TableHeaderText(stringResource("msg_send_col_file_name"), Modifier.weight(1f))
            TableHeaderText(stringResource("msg_col_size"), Modifier.width(80.dp))
            TableHeaderText(stringResource("msg_send_col_status"), Modifier.width(110.dp))
            TableHeaderText(stringResource("msg_send_col_actions"), Modifier.width(80.dp))
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.tasks, key = { it.id }) { task ->
                SendTaskRow(task, state)
            }
        }
    }
}

@Composable
private fun SendTaskRow(task: SendTask, state: SendMessagesState) {
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
            .padding(horizontal = ROW_HPADDING, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HoverTooltip(task.fileName, Modifier.weight(1f)) {
            Text(
                text = task.fileName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatSize(task.fileSize),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(80.dp),
        )
        Box(modifier = Modifier.width(110.dp)) {
            StatusCell(task)
        }
        Box(modifier = Modifier.width(80.dp)) {
            when {
                task.status == SendStatus.WAITING && state.canRemove(task) ->
                    SmallSecondaryButton(stringResource("msg_send_remove"), onClick = { state.removeTask(task.id) })
                task.status == SendStatus.FAILED && !state.busy ->
                    SmallSecondaryButton(stringResource("msg_send_retry"), onClick = { state.retryTask(task.id) })
            }
        }
    }
}

@Composable
private fun StatusCell(task: SendTask) {
    val cell: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (task.status) {
                SendStatus.WAITING -> {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(WaitingGray))
                    StatusText(stringResource("msg_send_status_waiting"))
                }
                SendStatus.SENDING -> {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    StatusText(stringResource("msg_send_status_sending"))
                }
                SendStatus.SUCCESS -> {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = SuccessGreen)
                    StatusText(stringResource("msg_send_status_success"))
                }
                SendStatus.FAILED -> {
                    Icon(imageVector = Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp), tint = FailedRed)
                    StatusText(stringResource("msg_send_status_failed"))
                }
            }
        }
    }
    // 失败项 hover 显示错误原因。
    if (task.status == SendStatus.FAILED && task.errorMessage != null) {
        HoverTooltip(task.errorMessage!!, Modifier) { cell() }
    } else {
        cell()
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, maxLines = 1)
}

@Composable
private fun SmallSecondaryButton(text: String, onClick: () -> Unit) {
    PressableButton(
        onClick = onClick,
        standardMinWidth = false,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) { Text(text) }
}

/* ------------------------------- 底部 ------------------------------- */

private val SENT_AT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun SendBottomBar(
    state: SendMessagesState,
    onEmptyBodyWarning: () -> Unit,
) {
    val single = state.tasks.size == 1
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HPADDING, vertical = 8.dp)) {
        // 单文件：最近一次发送结果（成功带 MessageId + 时间，失败带原因 + 重试）。
        state.singleOutcome?.let { outcome ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (outcome.success) {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = SuccessGreen)
                    Text(
                        text = stringResource("msg_send_sent_ok").withArg(outcome.messageId ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    outcome.sentAt?.let {
                        Text(
                            text = stringResource("msg_send_sent_at")
                                .withArg(SENT_AT_FORMATTER.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Icon(imageVector = Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp), tint = FailedRed)
                    Text(
                        text = stringResource("msg_send_failed_reason").withArg(outcome.errorMessage ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    SmallSecondaryButton(stringResource("msg_send_retry"), onClick = { state.sendSingle(onEmptyBodyWarning) })
                }
            }
        }
        // 多文件：一轮完成后的汇总。
        state.batchSummary?.let { summary ->
            Text(
                text = stringResource("msg_send_summary")
                    .withArgs(summary.total, summary.success, summary.failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            val noWaiting = !single && state.tasks.none { it.status == SendStatus.WAITING }
            PressableButton(
                onClick = {
                    if (single) state.sendSingle(onEmptyBodyWarning) else state.sendAll()
                },
                enabled = !state.busy && state.tasks.isNotEmpty() && !noWaiting,
            ) {
                Text(
                    text = when {
                        state.busy && !single ->
                            stringResource("msg_send_sending_progress").withArgs(state.processedCount, state.batchTotal)
                        state.busy -> stringResource("msg_send_sending")
                        single -> stringResource("msg_send_send")
                        else -> stringResource("msg_send_send_all")
                    },
                )
            }
        }
    }
}

/* --------------------------- 发送中关闭确认 --------------------------- */

/** 发送进行中关对话框的确认（ObjectDetailDialog 的 onCloseRequest 拦截后弹出）。 */
@Composable
internal fun ConfirmCloseWhileSendingDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource("msg_send_close_confirm")) },
        confirmButton = {
            PressableButton(onClick = onConfirm) { Text(stringResource("common_ok")) }
        },
        dismissButton = {
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_cancel")) }
        },
    )
}
