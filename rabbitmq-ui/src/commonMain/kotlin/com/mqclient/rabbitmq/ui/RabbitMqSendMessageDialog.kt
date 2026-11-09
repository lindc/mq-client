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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.mqclient.uicommon.AppIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.file.MAX_MESSAGE_FILE_MB
import com.mqclient.mqui.file.MessageFileFailure
import com.mqclient.mqui.file.readMessageBytes
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.DialogSizeClass
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.file.pickFiles
import com.mqclient.uicommon.CompactTextField
import com.mqclient.uicommon.CompactControlSpec
import com.mqclient.uicommon.CompactPrimaryButton
import com.mqclient.uicommon.CompactSecondaryButton
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.failureText
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一个待发送文件的任务；[content] 选择时已读入（读失败的文件不进列表），
 * 发送时转 base64（管理端点 payload_encoding=base64，避免二进制损坏）。
 */
private data class SendTask(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val content: ByteArray,
    val status: SendFileStatus = SendFileStatus.WAITING,
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

    // ---- Headers 键值对（附加到每一条消息）----
    var headers by mutableStateOf(listOf<Pair<String, String>>())

    /* ------------------------------ 批量任务管理 ------------------------------ */

    /**
     * 选完文件后装配任务列表（**替换**语义），IO 线程读文件；读不出来的文件跳过，
     * 失败清单返回给调用方弹提示。空文件（0 字节）允许进列表。
     *
     * 单个文件的大小由 [readMessageBytes] 先行把住（超过 [com.mqclient.mqui.file.MAX_MESSAGE_FILE_BYTES]
     * 不读进内存）：消息体文件没有任何流控，误选一个几十 GB 的文件就是一次 OOM。
     */
    suspend fun loadFiles(paths: List<String>): List<MessageFileFailure> = withContext(Dispatchers.IO) {
        val loaded = mutableListOf<SendTask>()
        val failed = mutableListOf<MessageFileFailure>()
        for (path in paths) {
            val file = java.io.File(path)
            runCatching { file.readMessageBytes() }
                .onSuccess { bytes ->
                    loaded += SendTask(
                        id = UUID.randomUUID().toString(),
                        fileName = file.name,
                        fileSize = bytes.size.toLong(),
                        content = bytes,
                    )
                }
                .onFailure { failed += MessageFileFailure.of(file.name, it) }
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

    fun canRemove(task: SendTask): Boolean = !busy && task.status == SendFileStatus.WAITING

    /* ------------------------------ 发送 ------------------------------ */

    /** 「发送文件」：按列表顺序串行发 WAITING 项（base64 编码），完成后汇总。 */
    fun sendAll(routingKey: String) {
        if (busy) return
        val targets = tasks.filter { it.status == SendFileStatus.WAITING }
        if (targets.isEmpty()) return
        scope.launch {
            busy = true
            batchSummary = null
            batchTotal = targets.size
            processedCount = 0
            // 本轮锁定：全部文件用同一组 headers（key 空白的行忽略）。
            val headerMap = headers
                .filter { it.first.isNotBlank() }
                .associate { it.first.trim() to it.second }
            var ok = 0
            var fail = 0
            for (target in targets) {
                updateTask(target.id) { it.copy(status = SendFileStatus.SENDING, unrouted = false, errorMessage = null) }
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
        if (task.status != SendFileStatus.FAILED) return
        scope.launch {
            updateTask(id) { it.copy(status = SendFileStatus.SENDING, unrouted = false, errorMessage = null) }
            val headerMap = headers
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
        copy(status = SendFileStatus.SUCCESS, unrouted = false, errorMessage = null)
    } else {
        copy(status = SendFileStatus.FAILED, unrouted = outcome.unrouted, errorMessage = outcome.errorMessage)
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

/**
 * 「发送消息」对话框（交换机卡片 ✉️ 入口，开发辅助）：顶部固定区（目标交换机只读 +
 * Routing Key 必填）+ 纯文件驱动发送区（对齐队列对话框 SendPane）：Headers + 文件
 * 选择区（FileKit 多选，唯一消息来源，无正文输入框），全部用同一个 Routing Key 串行
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

    fun validatedRoutingKey(): String? {
        if (routingKey.isBlank()) {
            routingKeyError = stringResource("rabbitmq_ui_send_routing_key_required")
            return null
        }
        routingKeyError = null
        return routingKey
    }

    fun pickFiles() {
        scope.launch {
            val paths = pickFiles(stringResource("rabbitmq_ui_send_pick_title"))
            if (paths.isEmpty()) return@launch
            val failed = state.loadFiles(paths)
            failed.forEach { failure ->
                val text =
                    if (failure.tooLarge) {
                        stringResource("rabbitmq_ui_send_file_too_large")
                            .withArg(failure.fileName, MAX_MESSAGE_FILE_MB)
                    } else {
                        stringResource("rabbitmq_ui_send_file_read_failed").withArg(failure.fileName)
                    }
                snackbar.showSnackbar(text)
            }
        }
    }

    // 发送统一入口：按列表串行发 WAITING 项（无文件时按钮禁用）。Routing Key 先校验。
    fun submit() {
        validatedRoutingKey()?.let(state::sendAll)
    }

    ObjectDetailDialog(
        // 与队列发送对话框（RabbitMqQueueMessagesDialog 用默认的 CONTENT 档）完全一致：
        // 同一 0.85 屏幕比例、同一 900dp 内容区 / 最小窗口宽度钳制。此前这里是
        // FORM（0.60），初始窗口比队列对话框小一圈，文件表被压得很窄。
        sizeClass = DialogSizeClass.CONTENT,
        title = stringResource("rabbitmq_ui_send_title").withArg(displayName),
        onDismiss = onDismiss,
        footerActions = {
            PrimaryButton(
                onClick = onDismiss,
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
            CompactTextField(
                value = routingKey,
                onValueChange = {
                    routingKey = it
                    routingKeyError = null
                },
                label = stringResource("rabbitmq_ui_send_routing_key"),
                isError = routingKeyError != null,
                supportingText = routingKeyError,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                height = CompactControlSpec.Height,
            )
            Spacer(Modifier.height(8.dp))

            // ---- 发送页（单页融合，无 Tab）----
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SendPane(
                    state = state,
                    onPickFiles = ::pickFiles,
                    onRetryTask = { id -> validatedRoutingKey()?.let { state.retryTask(id, it) } },
                )
                ThemedSnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
            }

            HorizontalDivider()

            // ---- 底部：结果 / 进度 / 汇总 + 发送按钮 ----
            SendBottomBar(state = state, onSubmit = ::submit)
        }
    }
}

/* --------------------------- 发送页（单页融合） --------------------------- */

/**
 * 发送页**纯文件驱动**（对齐 IBM MessageSendTab / 队列对话框 SendPane）：Headers +
 * 文件选择区（FileKit 多选，唯一消息来源，无正文输入框）+ 文件表格；发送分流逻辑
 * 在对话框层 submit()：有 WAITING 项即可发送，按列表串行发。
 */
@Composable
private fun SendPane(
    state: RabbitMqSendState,
    onPickFiles: () -> Unit,
    onRetryTask: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            HeadersEditor(headers = state.headers, enabled = !state.busy, onChange = { state.headers = it })
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ---- 文件区工具栏：选择文件 / 数量 / 提示 / 清空 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactPrimaryButton(onClick = onPickFiles, enabled = !state.busy) {
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
                CompactSecondaryButton(
                    onClick = state::clearBatch,
                    enabled = !state.busy && state.tasks.isNotEmpty(),
                ) { Text(stringResource("rabbitmq_ui_send_clear")) }
            }
            Spacer(Modifier.height(4.dp))

            if (state.tasks.isEmpty()) {
                ObjectListEmptyState(message = stringResource("rabbitmq_ui_send_empty"))
            }

            // ---- 文件表格：名称 / 大小 / 状态 / 操作（选了文件才出现）----
            if (state.tasks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TableHeader(stringResource("rabbitmq_ui_send_col_file_name"), Modifier.weight(1f))
                    TableHeader(stringResource("rabbitmq_ui_send_col_size"), Modifier.width(SendFileColumns.SIZE))
                    TableHeader(stringResource("rabbitmq_ui_send_col_status"), Modifier.width(SendFileColumns.STATUS))
                    TableHeader(stringResource("rabbitmq_ui_send_col_actions"), Modifier.width(SendFileColumns.ACTIONS))
                }
                HorizontalDivider()
                state.tasks.forEach { task ->
                    SendFileRow(
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

/**
 * Headers 键值对编辑器（发送消息 / 创建队列等共用）：标签行 + 键值对行（可删）+
 * 「添加 Header」。状态由调用方持有，本组件是纯视图。[titleLabel] / [addLabel]
 * 可覆盖默认文案（创建队列的 Arguments 场景）。
 */
@Composable
internal fun HeadersEditor(
    headers: List<Pair<String, String>>,
    enabled: Boolean,
    onChange: (List<Pair<String, String>>) -> Unit,
    titleLabel: String? = null,
    addLabel: String? = null,
) {
    Text(
        text = titleLabel ?: stringResource("rabbitmq_ui_send_headers"),
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
            CompactTextField(
                value = pair.first,
                onValueChange = { onChange(headers.toMutableList().also { list -> list[index] = it to pair.second }) },
                enabled = enabled,
                placeholder = stringResource("rabbitmq_ui_send_header_name"),
                modifier = Modifier.weight(1f),
                height = CompactControlSpec.Height,
            )
            Spacer(Modifier.width(8.dp))
            CompactTextField(
                value = pair.second,
                onValueChange = { onChange(headers.toMutableList().also { list -> list[index] = pair.first to it }) },
                enabled = enabled,
                placeholder = stringResource("rabbitmq_ui_send_header_value"),
                modifier = Modifier.weight(1.4f),
                height = CompactControlSpec.Height,
            )
            AppIconButton(
                onClick = { onChange(headers.filterIndexed { i, _ -> i != index }) },
                enabled = enabled,
                size = 24.dp,
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
    PrimaryButton(
        onClick = { onChange(headers + ("" to "")) },
        enabled = enabled,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(addLabel ?: stringResource("rabbitmq_ui_send_add_header"), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SendFileRow(
    task: SendTask,
    busy: Boolean,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    FileListItem(
        fileName = task.fileName,
        fileSize = task.fileSize,
        status = task.status,
        // unrouted 的专用文案在这里解析：共享组件只认 errorMessage，不认业务状态。
        errorMessage = task.errorMessage
            ?: if (task.unrouted) stringResource("rabbitmq_ui_send_unrouted") else null,
        busy = busy,
        onRemove = onRemove,
        onRetry = onRetry,
        // 水平内边距与表头一致（8dp），列才对得上；行高与字号由 FileListItem 统一。
        modifier = Modifier
            .hoverable(interactionSource)
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .padding(horizontal = 8.dp),
    )
}

/* ------------------------------- 底部操作条 ------------------------------- */

@Composable
private fun SendBottomBar(
    state: RabbitMqSendState,
    onSubmit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // 发送中进度 + 一轮完成后的汇总。
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            // 只看待发送文件：有 WAITING 项即可点，无文件/全发完则禁用。
            PrimaryButton(
                onClick = onSubmit,
                enabled = !state.busy && state.tasks.any { it.status == SendFileStatus.WAITING },
            ) {
                Text(
                    text = when {
                        state.busy && state.batchTotal > 0 ->
                            stringResource("rabbitmq_ui_send_progress").withArg(state.processedCount, state.batchTotal)
                        state.busy -> stringResource("rabbitmq_ui_send_sending")
                        else -> stringResource("rabbitmq_ui_send_send_files")
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

