package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.file.pickFiles
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SuccessGreen = Color(0xFF4CAF50)
private val FailedRed = Color(0xFFF44336)
private val WaitingGray = Color(0xFF9E9E9E)

/** 批量任务状态机：等待 → 发送中 → 成功 / 失败（失败可重试，unrouted 也算失败）。 */
private enum class SendStatus { WAITING, SENDING, SUCCESS, FAILED }

/**
 * 一个待发送文件的任务；[content] 选择时已读入（读失败的文件不进列表），
 * 发送时转 base64（管理端点 payload_encoding=base64，避免二进制损坏）。
 */
private data class SendTask(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val content: ByteArray,
    val status: SendStatus = SendStatus.WAITING,
    val unrouted: Boolean = false,
    val errorMessage: String? = null,
)

/** 一轮「全部发送」的汇总。 */
private data class SendBatchSummary(val total: Int, val success: Int, val failed: Int)

/** 单条发送结果：unrouted=true = 已提交但没命中任何队列（按失败记，可重试）。 */
private data class SingleOutcome(val unrouted: Boolean = false, val errorMessage: String? = null) {
    val success: Boolean get() = !unrouted && errorMessage == null
}

/**
 * 「发送消息」对话框的状态持有者（rabbitmq-ui 内部，与 ibm-mq 的 SendMessagesState
 * 同一模式）：实例挂在对话框层 —— 切 Tab 状态都存活，对话框关闭才销毁。发送按任务
 * 顺序**串行**执行；HTTP 调用在 service 层的 Dispatchers.IO，状态写回走 Compose
 * snapshot（主线程安全）。Routing Key 在对话框层持有并校验，发送中禁用输入。
 */
@Stable
private class RabbitMqSendState(
    private val connectionId: String,
    private val exchangeName: String,
    private val scope: CoroutineScope,
) {
    var tasks by mutableStateOf<List<SendTask>>(emptyList())
        private set

    /** 任一发送流程执行中（单条 / 全部 / 单条重试共用，互斥）。 */
    var busy by mutableStateOf(false)
        private set

    /** 本轮「全部发送」已处理条数（进度 n/N）。 */
    var processedCount by mutableIntStateOf(0)
        private set

    var batchTotal by mutableIntStateOf(0)
        private set

    var batchSummary by mutableStateOf<SendBatchSummary?>(null)
        private set

    /** 单条模式最近一次发送结果（null = 还没发过）。 */
    var singleOutcome by mutableStateOf<SingleOutcome?>(null)
        private set

    // ---- 单条模式：Headers 键值对 + 消息正文 ----
    var headers by mutableStateOf(listOf<Pair<String, String>>())
    var payload by mutableStateOf("")

    /** 批量模式专属 Headers（与单条的 [headers] 各存各的），应用到本轮每一条消息。 */
    var batchHeaders by mutableStateOf(listOf<Pair<String, String>>())

    /* ------------------------------ 批量任务管理 ------------------------------ */


    /**
     * 选完文件后装配任务列表（**替换**语义），IO 线程读文件；读失败的文件跳过，
     * 名字返回给调用方弹提示。空文件（0 字节）允许进列表。
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
        batchSummary = null
        processedCount = 0
        batchTotal = 0
        failed
    }

    /** 清空已选文件与批量状态（工具栏「清空」）。 */
    fun clearBatch() {
        tasks = emptyList()
        batchSummary = null
        processedCount = 0
        batchTotal = 0
    }

    fun removeTask(id: String) {
        if (busy) return
        tasks = tasks.filterNot { it.id == id }
    }

    fun canRemove(task: SendTask): Boolean = !busy && task.status == SendStatus.WAITING

    /* ------------------------------ 发送 ------------------------------ */

    /** 单条发送（Headers + payload，string 编码）。routingKey 已由对话框层校验。 */
    fun sendSingle(routingKey: String) {
        if (busy) return
        scope.launch {
            busy = true
            singleOutcome = null
            singleOutcome = publish(
                routingKey = routingKey,
                payload = payload,
                headers = headers.filter { it.first.isNotBlank() }.associate { it.first.trim() to it.second },
                encoding = "string",
            )
            busy = false
        }
    }

    /** 「全部发送」：按列表顺序串行发 WAITING 项（base64 编码），完成后汇总。 */
    fun sendAll(routingKey: String) {
        if (busy) return
        val targets = tasks.filter { it.status == SendStatus.WAITING }
        if (targets.isEmpty()) return
        scope.launch {
            busy = true
            batchSummary = null
            batchTotal = targets.size
            processedCount = 0
            // 本轮锁定：全部文件用同一组 batchHeaders（key 空白的行忽略）。
            val headerMap = batchHeaders
                .filter { it.first.isNotBlank() }
                .associate { it.first.trim() to it.second }
            var ok = 0
            var fail = 0
            for (target in targets) {
                updateTask(target.id) { it.copy(status = SendStatus.SENDING, unrouted = false, errorMessage = null) }
                val outcome = publish(routingKey, b64(target.content), headerMap, "base64")
                updateTask(target.id) { it.withOutcome(outcome) }
                if (outcome.success) ok++ else fail++
                processedCount++
            }
            batchSummary = SendBatchSummary(total = targets.size, success = ok, failed = fail)
            busy = false
        }
    }

    /** 失败项单独重试：只重发该条，不影响其他任务的状态。 */
    fun retryTask(id: String, routingKey: String) {
        if (busy) return
        val task = tasks.firstOrNull { it.id == id } ?: return
        if (task.status != SendStatus.FAILED) return
        scope.launch {
            updateTask(id) { it.copy(status = SendStatus.SENDING, unrouted = false, errorMessage = null) }
            val headerMap = batchHeaders
                .filter { it.first.isNotBlank() }
                .associate { it.first.trim() to it.second }
            val outcome = publish(routingKey, b64(task.content), headerMap, "base64")
            updateTask(id) { it.withOutcome(outcome) }
        }
    }

    /* ------------------------------ internals ------------------------------ */

    private fun updateTask(id: String, transform: (SendTask) -> SendTask) {
        tasks = tasks.map { if (it.id == id) transform(it) else it }
    }

    /** 管理端点 publish：routed=false 记为失败（未路由到任何队列，可重试）。 */
    private suspend fun publish(
        routingKey: String,
        payload: String,
        headers: Map<String, String>,
        encoding: String,
    ): SingleOutcome {
        val service = RabbitMqModule.serviceOrNull()
            ?: return SingleOutcome(errorMessage = "服务未初始化")
        return try {
            val routed = service.publishTestMessage(
                connectionId = connectionId,
                exchange = exchangeName,
                routingKey = routingKey,
                payload = payload,
                headers = headers,
                payloadEncoding = encoding,
            )
            if (routed) SingleOutcome() else SingleOutcome(unrouted = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SingleOutcome(errorMessage = failureText(e))
        }
    }

    private fun SendTask.withOutcome(outcome: SingleOutcome): SendTask = if (outcome.success) {
        copy(status = SendStatus.SUCCESS, unrouted = false, errorMessage = null)
    } else {
        copy(status = SendStatus.FAILED, unrouted = outcome.unrouted, errorMessage = outcome.errorMessage)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

/**
 * 「发送消息」对话框（交换机卡片 ✉️ 入口，开发辅助）：顶部固定区（目标交换机只读 +
 * Routing Key 必填）+ 单条 / 批量两个 Tab。单条 = Headers 键值对 + 正文 TextArea
 * （可从文件加载，string 编码）；批量 = 多文件表格，全部用同一个 Routing Key 串行
 * 发送（base64 编码），失败可重试。底层走管理 API publish（见
 * [com.mqclient.rabbitmq.RabbitMqScanService.publishTestMessage]）。
 */
@Composable
fun RabbitMqSendMessageDialog(
    connectionId: String,
    exchangeName: String,
    displayName: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state = remember { RabbitMqSendState(connectionId, exchangeName, scope) }
    var routingKey by remember { mutableStateOf("") }
    var routingKeyError by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    fun validatedRoutingKey(): String? {
        if (routingKey.isBlank()) {
            routingKeyError = stringResource("rabbitmq_ui_send_routing_key_required")
            return null
        }
        routingKeyError = null
        return routingKey
    }

    fun pickForSingle() {
        scope.launch {
            val paths = pickFiles(stringResource("rabbitmq_ui_send_pick_title"))
            val path = paths.firstOrNull() ?: return@launch
            val text = withContext(Dispatchers.IO) {
                runCatching { java.io.File(path).readText(Charsets.UTF_8) }.getOrNull()
            }
            if (text == null) {
                snackbar.showSnackbar(
                    stringResource("rabbitmq_ui_send_file_read_failed")
                        .withArg(java.io.File(path).name),
                )
            } else {
                state.payload = text
            }
        }
    }

    fun pickForBatch() {
        scope.launch {
            val paths = pickFiles(stringResource("rabbitmq_ui_send_pick_title"))
            if (paths.isEmpty()) return@launch
            val failed = state.loadFiles(paths)
            failed.forEach { name ->
                snackbar.showSnackbar(stringResource("rabbitmq_ui_send_file_read_failed").withArg(name))
            }
        }
    }

    ObjectDetailDialog(
        title = stringResource("rabbitmq_ui_send_title").withArg(displayName),
        onDismiss = onDismiss,
        minWidth = 660.dp,
        minHeight = 700.dp,
        maxHeight = 840.dp,
        footerActions = {
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_close")) }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            // ---- 顶部固定区：目标交换机（只读）+ Routing Key（必填，两 Tab 共用）----
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource("rabbitmq_ui_send_target"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            OutlinedTextField(
                value = routingKey,
                onValueChange = {
                    routingKey = it
                    routingKeyError = null
                },
                label = { Text(stringResource("rabbitmq_ui_send_routing_key")) },
                isError = routingKeyError != null,
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (routingKeyError != null) {
                Text(
                    text = routingKeyError!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))

            // ---- Tab：单条 / 批量 ----
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource("rabbitmq_ui_send_tab_single")) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource("rabbitmq_ui_send_tab_batch")) },
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    0 -> SingleSendPane(
                        state = state,
                        busy = state.busy,
                        onPickFile = ::pickForSingle,
                    )
                    else -> BatchSendPane(
                        state = state,
                        onPickFiles = ::pickForBatch,
                        onRetryTask = { id -> validatedRoutingKey()?.let { state.retryTask(id, it) } },
                    )
                }
                ThemedSnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
            }

            HorizontalDivider()

            // ---- 底部：结果 / 进度 / 汇总 + 发送按钮 ----
            SendBottomBar(
                state = state,
                isSingle = selectedTab == 0,
                onSubmitSingle = { validatedRoutingKey()?.let(state::sendSingle) },
                onSubmitAll = { validatedRoutingKey()?.let(state::sendAll) },
            )
        }
    }
}

/* --------------------------- 单条发送 Tab --------------------------- */

@Composable
private fun SingleSendPane(
    state: RabbitMqSendState,
    busy: Boolean,
    onPickFile: () -> Unit,
) {
    // 整体可滚动：Headers 行多时不挤压正文；正文固定 200dp 保证输入空间。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        HeadersEditor(headers = state.headers, enabled = !busy, onChange = { state.headers = it })
        Spacer(Modifier.height(8.dp))

        // ---- 消息正文（TextArea，可从文件加载）----
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource("rabbitmq_ui_send_payload"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            PressableButton(onClick = onPickFile, enabled = !busy) {
                Text(stringResource("rabbitmq_ui_send_load_file"), style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = state.payload,
            onValueChange = { state.payload = it },
            enabled = !busy,
            singleLine = false,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.height(280.dp).fillMaxWidth(),
        )
    }
}

/**
 * Headers 键值对编辑器（单条 / 批量两 Tab 共用同一实现）：标签行 + 键值对行（可删）+
 * 「添加 Header」。状态由调用方持有（单条 [RabbitMqSendState.headers] /
 * 批量 [RabbitMqSendState.batchHeaders] 各存各的），本组件是纯视图。
 */
@Composable
private fun HeadersEditor(
    headers: List<Pair<String, String>>,
    enabled: Boolean,
    onChange: (List<Pair<String, String>>) -> Unit,
) {
    Text(
        text = stringResource("rabbitmq_ui_send_headers"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    // 行区上限与正文（280dp）一致：行少自然收缩，行多内部滚动，不再把正文 / 文件表挤下去。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        headers.forEachIndexed { index, pair ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            OutlinedTextField(
                value = pair.first,
                onValueChange = { onChange(headers.toMutableList().also { list -> list[index] = it to pair.second }) },
                singleLine = true,
                enabled = enabled,
                placeholder = { Text(stringResource("rabbitmq_ui_send_header_name")) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = pair.second,
                onValueChange = { onChange(headers.toMutableList().also { list -> list[index] = pair.first to it }) },
                singleLine = true,
                enabled = enabled,
                placeholder = { Text(stringResource("rabbitmq_ui_send_header_value")) },
                modifier = Modifier.weight(1.4f),
            )
            IconButton(
                onClick = { onChange(headers.filterIndexed { i, _ -> i != index }) },
                enabled = enabled,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = stringResource("common_delete"),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
    }
    PressableButton(
        onClick = { onChange(headers + ("" to "")) },
        enabled = enabled,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource("rabbitmq_ui_send_add_header"), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/* --------------------------- 批量发送 Tab --------------------------- */

@Composable
private fun BatchSendPane(
    state: RabbitMqSendState,
    onPickFiles: () -> Unit,
    onRetryTask: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        // ---- 工具栏：选择文件 / 数量 / 清空 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PressableButton(onClick = onPickFiles, enabled = !state.busy) {
                Text(stringResource("rabbitmq_ui_send_pick_file"))
            }
            if (state.tasks.isNotEmpty()) {
                Text(
                    text = stringResource("rabbitmq_ui_send_files_selected").withArg(state.tasks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource("rabbitmq_ui_send_batch_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            PressableButton(
                onClick = state::clearBatch,
                enabled = !state.busy && state.tasks.isNotEmpty(),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("rabbitmq_ui_send_clear")) }
        }
        Spacer(Modifier.height(8.dp))
        // ---- 批量 Headers：应用到本轮每一条消息（与单条 headers 各存各的）----
        HeadersEditor(
            headers = state.batchHeaders,
            enabled = !state.busy,
            onChange = { state.batchHeaders = it },
        )
        Spacer(Modifier.height(4.dp))

        if (state.tasks.isEmpty()) {
            ObjectListEmptyState(message = stringResource("rabbitmq_ui_send_empty"))
        } else {
            // ---- 文件表格：名称 / 大小 / 状态 / 操作 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TableHeader(stringResource("rabbitmq_ui_send_col_file_name"), Modifier.weight(1f))
                TableHeader(stringResource("rabbitmq_ui_send_col_size"), Modifier.width(80.dp))
                TableHeader(stringResource("rabbitmq_ui_send_col_status"), Modifier.width(160.dp))
                TableHeader(stringResource("rabbitmq_ui_send_col_actions"), Modifier.width(72.dp))
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.tasks, key = { it.id }) { task ->
                    SendTaskRow(
                        task = task,
                        busy = state.busy,
                        onRemove = { state.removeTask(task.id) },
                        onRetry = { onRetryTask(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SendTaskRow(
    task: SendTask,
    busy: Boolean,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(background)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = task.fileName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatSize(task.fileSize),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(80.dp),
        )
        Box(modifier = Modifier.width(160.dp)) {
            StatusCell(task)
        }
        Box(modifier = Modifier.width(72.dp)) {
            when {
                task.status == SendStatus.WAITING && !busy ->
                    SmallSecondaryButton(stringResource("rabbitmq_ui_send_remove"), onClick = onRemove)
                task.status == SendStatus.FAILED && !busy ->
                    SmallSecondaryButton(stringResource("rabbitmq_ui_send_retry"), onClick = onRetry)
            }
        }
    }
}

@Composable
private fun StatusCell(task: SendTask) {
    Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (task.status) {
            SendStatus.WAITING -> {
                Box(Modifier.size(8.dp).clip(CircleShape).background(WaitingGray))
                StatusText(stringResource("rabbitmq_ui_send_status_waiting"))
            }
            SendStatus.SENDING -> {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                StatusText(stringResource("rabbitmq_ui_send_status_sending"))
            }
            SendStatus.SUCCESS -> {
                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = SuccessGreen)
                StatusText(stringResource("rabbitmq_ui_send_status_success"))
            }
            SendStatus.FAILED -> {
                Icon(imageVector = Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp), tint = FailedRed)
                // 失败列直出原因（unrouted 专用文案 / 其他异常），单行省略。
                StatusText(
                    text = task.errorMessage
                        ?: stringResource(if (task.unrouted) "rabbitmq_ui_send_unrouted" else "rabbitmq_ui_send_status_failed"),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color = Color.Unspecified) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/* ------------------------------- 底部操作条 ------------------------------- */

@Composable
private fun SendBottomBar(
    state: RabbitMqSendState,
    isSingle: Boolean,
    onSubmitSingle: () -> Unit,
    onSubmitAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // 单条：最近一次发送结果（成功 / 未路由 / 失败原因 + 重试）。
        if (isSingle) {
            state.singleOutcome?.let { outcome ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        outcome.success -> {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = SuccessGreen)
                            Text(
                                text = stringResource("rabbitmq_ui_send_success"),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        outcome.unrouted -> {
                            Icon(imageVector = Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp), tint = FailedRed)
                            Text(
                                text = stringResource("rabbitmq_ui_send_unrouted"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            SmallSecondaryButton(stringResource("rabbitmq_ui_send_retry"), onClick = onSubmitSingle)
                        }
                        else -> {
                            Icon(imageVector = Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp), tint = FailedRed)
                            Text(
                                text = stringResource("rabbitmq_ui_send_failed").withArg(outcome.errorMessage ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            SmallSecondaryButton(stringResource("rabbitmq_ui_send_retry"), onClick = onSubmitSingle)
                        }
                    }
                }
            }
        }
        // 批量：发送中进度 + 一轮完成后的汇总。
        if (!isSingle) {
            if (state.busy && state.batchTotal > 0) {
                Text(
                    text = stringResource("rabbitmq_ui_send_progress").withArg(state.processedCount, state.batchTotal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            state.batchSummary?.let { summary ->
                Text(
                    text = stringResource("rabbitmq_ui_send_summary")
                        .withArg(summary.total, summary.success, summary.failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            val noWaiting = !isSingle && state.tasks.none { it.status == SendStatus.WAITING }
            PressableButton(
                onClick = { if (isSingle) onSubmitSingle() else onSubmitAll() },
                enabled = !state.busy && (isSingle || (state.tasks.isNotEmpty() && !noWaiting)),
            ) {
                Text(
                    text = when {
                        isSingle && state.busy -> stringResource("rabbitmq_ui_send_sending")
                        !isSingle && state.busy ->
                            stringResource("rabbitmq_ui_send_progress").withArg(state.processedCount, state.batchTotal)
                        !isSingle -> stringResource("rabbitmq_ui_send_send_all")
                        else -> stringResource("rabbitmq_ui_send_button")
                    },
                )
            }
        }
    }
}

/* ------------------------------- 小件 ------------------------------- */

@Composable
private fun TableHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
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

/** 失败原因：解 ExecutionException 包装（与其他对话框调用处同一简化版）。 */
private fun failureText(error: Throwable): String =
    (error as? java.util.concurrent.ExecutionException)?.cause?.message
        ?: error.message
        ?: error::class.simpleName
        ?: "unknown"

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
