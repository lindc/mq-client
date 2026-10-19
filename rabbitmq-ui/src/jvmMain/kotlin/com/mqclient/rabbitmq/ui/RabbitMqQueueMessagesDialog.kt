package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.state.LoadState
import com.mqclient.rabbitmq.RabbitMqFetchedMessage
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.RabbitMqMessageSession
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.file.pickFile
import com.mqclient.uicommon.file.pickFiles
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 拉取条数上限：basicGet 逐条循环，防大队列一次拉爆内存。 */
private const val FETCH_HARD_LIMIT = 100

private val SuccessGreen = Color(0xFF2E7D32)
private val FailedRed = Color(0xFFD32F2F)

/** 批量发送任务状态机：等待 → 发送中 → 成功 / 失败（失败可重试）。 */
private enum class BatchStatus { WAITING, SENDING, SUCCESS, FAILED }

/** 一个待发送文件的任务；[content] 选择时已读入，直发原始字节（AMQP 无需编码层）。 */
private data class BatchTask(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val content: ByteArray,
    val status: BatchStatus = BatchStatus.WAITING,
    val errorMessage: String? = null,
)

/** 手动 limit 输入 → 有效条数（1 - 100，默认 20）。 */
private fun effectiveLimit(raw: String): Int =
    raw.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, FETCH_HARD_LIMIT) ?: 20

/**
 * 队列消息对话框（队列卡片 ✉️ 入口）：ObjectDetailDialog + PrimaryTabRow 双 Tab
 * （浏览 / 发送），参照 IBM MQ 的 QueueMessagesDialog。
 *
 * **浏览（只读拉取）**：底层 AMQP basicGet(autoAck = false) —— 消息置于 unacked，
 * 凭据（deliveryTag）在会话手上：点删除 = basicAck 彻底消费；「重新拉取」=
 * requeueAll（basicNack requeue）放回后重拉；关闭对话框 = 会话 close（未 ack 的
 * 由 broker 自动重入队，close 前另显式 requeue 一遍）。「拉取消息」在同一 channel
 * 上继续往后取（不 requeue），队列取尽时给出提示。
 *
 * **发送（直发队列）**：经默认交换机 basicPublish("", queue, props, body) ——
 * UI 免填 Exchange / Routing Key；单条（Headers + 正文）/ 批量（多文件串行直发
 * 原始字节）。发送成功自动触发一次「重新拉取」刷新列表。
 *
 * 会话生命周期：打开时 [RabbitMqModule.openMessageSession]（三态），onDispose
 * 走 [RabbitMqModule.closeMessageSessionAsync]（同步回调里挂起 close 另起协程）。
 */
@Composable
fun RabbitMqQueueMessagesDialog(
    connectionId: String,
    queueName: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var session by remember { mutableStateOf<RabbitMqMessageSession?>(null) }
    var sessionState by remember(queueName) { mutableStateOf<LoadState<Unit>>(LoadState.Idle) }
    var sessionRetry by remember(queueName) { mutableIntStateOf(0) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // ---- 浏览状态 ----
    var messages by remember(queueName) { mutableStateOf(listOf<RabbitMqFetchedMessage>()) }
    var fetching by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf("20") }
    var exhausted by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<RabbitMqFetchedMessage?>(null) }

    // ---- 发送状态 ----
    var sendHeaders by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var payload by remember { mutableStateOf("") }
    var batchTasks by remember { mutableStateOf(listOf<BatchTask>()) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var sendTab by remember { mutableIntStateOf(0) }

    // 对话框销毁 → 收会话（onDispose 同步，挂起 close 另起协程；未 ack 自动重入队）。
    DisposableEffect(Unit) {
        onDispose { RabbitMqModule.closeMessageSessionAsync(session) }
    }

    // 会话三态：打开即建 AMQP 连接（passive 校验队列存在）。
    LaunchedEffect(connectionId, queueName, sessionRetry) {
        sessionState = LoadState.Loading
        sessionState = runCatching {
            val s = RabbitMqModule.openMessageSession(connectionId, queueName)
            session = s
            LoadState.Success(Unit)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            LoadState.Error(failureText(e))
        }
    }

    fun fetchMore() {
        val s = session ?: return
        scope.launch {
            fetching = true
            try {
                val batch = s.fetchNext(effectiveLimit(limit))
                messages = messages + batch
                exhausted = batch.isEmpty()
                held = s.heldCount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(failureText(e))
            } finally {
                fetching = false
            }
        }
    }

    fun refreshQueue() {
        val s = session ?: return
        scope.launch {
            fetching = true
            try {
                s.requeueAll()
                messages = emptyList()
                exhausted = false
                val batch = s.fetchNext(effectiveLimit(limit))
                messages = batch
                exhausted = batch.isEmpty()
                held = s.heldCount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(failureText(e))
            } finally {
                fetching = false
            }
        }
    }

    fun deleteOne(msg: RabbitMqFetchedMessage) {
        val s = session ?: return
        scope.launch {
            try {
                s.ack(msg.deliveryTag)
                messages = messages.filterNot { it.deliveryTag == msg.deliveryTag }
                held = s.heldCount()
                snackbar.showSnackbar(stringResource("rabbitmq_ui_msg_deleted"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(failureText(e))
            }
        }
    }

    fun updateBatchTask(id: String, transform: (BatchTask) -> BatchTask) {
        batchTasks = batchTasks.map { if (it.id == id) transform(it) else it }
    }

    suspend fun sendOneDirect(routingBytes: ByteArray, headers: Map<String, String>, s: RabbitMqMessageSession): Pair<Boolean, String?> {
        return try {
            s.publishDirect(routingBytes, headers)
            true to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false to failureText(e)
        }
    }

    fun sendSingle() {
        val s = session ?: return
        scope.launch {
            sending = true
            sendError = null
            try {
                val headers = sendHeaders.filter { it.first.isNotBlank() }.associate { it.first.trim() to it.second }
                s.publishDirect(payload.toByteArray(Charsets.UTF_8), headers)
                snackbar.showSnackbar(stringResource("rabbitmq_ui_msg_sent"))
                payload = ""
                sendHeaders = emptyList()
                // 发送成功自动刷新列表（重新拉取：新消息在队列头部）。
                s.requeueAll()
                messages = emptyList()
                exhausted = false
                messages = s.fetchNext(effectiveLimit(limit))
                exhausted = messages.isEmpty()
                held = s.heldCount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError = failureText(e)
            } finally {
                sending = false
            }
        }
    }

    fun loadPayloadFile() {
        scope.launch {
            val path = pickFile(stringResource("rabbitmq_ui_msg_pick_title"), extensions = emptyList()) ?: return@launch
            val text = withContext(Dispatchers.IO) {
                runCatching { java.io.File(path).readText(Charsets.UTF_8) }.getOrNull()
            }
            if (text == null) {
                snackbar.showSnackbar(stringResource("rabbitmq_ui_msg_file_read_failed"))
            } else {
                payload = text
            }
        }
    }

    fun loadSendFiles() {
        scope.launch {
            val paths = pickFiles(stringResource("rabbitmq_ui_msg_pick_title"))
            if (paths.isEmpty()) return@launch
            batchTasks = withContext(Dispatchers.IO) {
                paths.mapNotNull { path ->
                    val file = java.io.File(path)
                    runCatching { file.readBytes() }.getOrNull()?.let {
                        BatchTask(id = UUID.randomUUID().toString(), fileName = file.name, fileSize = it.size.toLong(), content = it)
                    }
                }
            }
        }
    }

    fun sendBatch() {
        val s = session ?: return
        scope.launch {
            sending = true
            sendError = null
            try {
                val headers = sendHeaders.filter { it.first.isNotBlank() }.associate { it.first.trim() to it.second }
                for (task in batchTasks.filter { it.status == BatchStatus.WAITING }) {
                    updateBatchTask(task.id) { it.copy(status = BatchStatus.SENDING, errorMessage = null) }
                    val (ok, err) = sendOneDirect(task.content, headers, s)
                    updateBatchTask(task.id) {
                        if (ok) it.copy(status = BatchStatus.SUCCESS) else it.copy(status = BatchStatus.FAILED, errorMessage = err)
                    }
                }
                val failed = batchTasks.count { it.status == BatchStatus.FAILED }
                if (failed == 0) snackbar.showSnackbar(stringResource("rabbitmq_ui_msg_batch_done"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError = failureText(e)
            } finally {
                sending = false
            }
        }
    }

    fun retryBatchTask(task: BatchTask) {
        val s = session ?: return
        scope.launch {
            updateBatchTask(task.id) { it.copy(status = BatchStatus.SENDING, errorMessage = null) }
            val headers = sendHeaders.filter { it.first.isNotBlank() }.associate { it.first.trim() to it.second }
            val (ok, err) = sendOneDirect(task.content, headers, s)
            updateBatchTask(task.id) {
                if (ok) it.copy(status = BatchStatus.SUCCESS) else it.copy(status = BatchStatus.FAILED, errorMessage = err)
            }
        }
    }

    ObjectDetailDialog(
        title = stringResource("rabbitmq_ui_msg_title").withArg(queueName),
        onDismiss = onDismiss,
        minWidth = 780.dp,
        minHeight = 580.dp,
        footerActions = {
            Spacer(Modifier.weight(1f))
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_close")) }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (val ss = sessionState) {
                is LoadState.Idle, is LoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                is LoadState.Error -> {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                        Text(
                            text = stringResource("rabbitmq_ui_msg_session_failed").withArg(ss.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButtonCompat { sessionRetry++ }
                    }
                }
                is LoadState.Success -> {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        PrimaryTabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text(stringResource("rabbitmq_ui_msg_tab_browse")) },
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text(stringResource("rabbitmq_ui_msg_tab_send")) },
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (selectedTab == 0) {
                                BrowsePane(
                                    messages = messages,
                                    fetching = fetching,
                                    limit = limit,
                                    onLimitChange = { limit = it.filter(Char::isDigit).take(3) },
                                    exhausted = exhausted,
                                    onFetch = ::fetchMore,
                                    onRefresh = ::refreshQueue,
                                    onDetail = { detail = it },
                                    onDelete = ::deleteOne,
                                    held = held,
                                )
                            } else {
                                SendPane(
                                    sendTab = sendTab,
                                    onSendTab = { sendTab = it },
                                    sendHeaders = sendHeaders,
                                    onSendHeaders = { sendHeaders = it },
                                    payload = payload,
                                    onPayload = { payload = it },
                                    busy = sending,
                                    onLoadFile = ::loadPayloadFile,
                                    batchTasks = batchTasks,
                                    onPickFiles = ::loadSendFiles,
                                    onRemoveTask = { batchTasks = batchTasks.filterNot { t -> t.id == it } },
                                    onSendSingle = ::sendSingle,
                                    onSendBatch = ::sendBatch,
                                    onRetryTask = ::retryBatchTask,
                                    sendError = sendError,
                                    queueEmpty = exhausted && messages.isEmpty(),
                                )
                            }
                        }
                    }
                }
            }
            ThemedSnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }

        detail?.let { msg ->
            RabbitMqMessageDetailDialog(message = msg, onDismiss = { detail = null })
        }
    }
}

/* ------------------------------ Tab 1：浏览 ------------------------------ */

@Composable
private fun BrowsePane(
    messages: List<RabbitMqFetchedMessage>,
    fetching: Boolean,
    limit: String,
    onLimitChange: (String) -> Unit,
    exhausted: Boolean,
    held: Int,
    onFetch: () -> Unit,
    onRefresh: () -> Unit,
    onDetail: (RabbitMqFetchedMessage) -> Unit,
    onDelete: (RabbitMqFetchedMessage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PressableButton(onClick = onFetch, enabled = !fetching) { Text(stringResource("rabbitmq_ui_msg_fetch")) }
            PressableButton(
                onClick = onRefresh,
                enabled = !fetching,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("rabbitmq_ui_msg_refetch")) }
            OutlinedTextField(
                value = limit,
                onValueChange = onLimitChange,
                singleLine = true,
                enabled = !fetching,
                modifier = Modifier.width(72.dp),
            )
            Text(
                text = stringResource("rabbitmq_ui_msg_fetch_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(stringResource("rabbitmq_ui_msg_col_index"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp))
            Text(stringResource("rabbitmq_ui_msg_col_message_id"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(stringResource("rabbitmq_ui_msg_col_body"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
            Text(stringResource("rabbitmq_ui_msg_col_size"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
            Text(stringResource("rabbitmq_ui_msg_col_time"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(90.dp))
            Text(stringResource("rabbitmq_ui_msg_col_actions"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages, key = { it.deliveryTag }) { msg ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg.deliveryTag.toString(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.width(40.dp))
                    Text(
                        text = msg.messageId ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(modifier = Modifier.width(64.dp)) {
                        Text(
                            text = stringResource(if (msg.probablyBinary) "rabbitmq_ui_msg_body_binary" else "rabbitmq_ui_msg_body_text"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(formatSize(msg.size), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.width(72.dp))
                    Text(
                        text = msg.timestamp?.let { java.text.SimpleDateFormat("HH:mm:ss").format(it) } ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(90.dp),
                    )
                    Row(modifier = Modifier.width(88.dp)) {
                        IconButton(onClick = { onDetail(msg) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Info, contentDescription = stringResource("rabbitmq_ui_msg_action_detail"), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDelete(msg) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource("rabbitmq_ui_msg_action_delete"),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
        Text(
            text = buildString {
                append(stringResource("rabbitmq_ui_msg_fetched_count").withArg(messages.size))
                append(stringResource("rabbitmq_ui_msg_pending_count").withArg(held))
                if (exhausted) append(stringResource("rabbitmq_ui_msg_exhausted"))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/* ------------------------------ Tab 2：发送 ------------------------------ */

@Composable
private fun SendPane(
    sendTab: Int,
    onSendTab: (Int) -> Unit,
    sendHeaders: List<Pair<String, String>>,
    onSendHeaders: (List<Pair<String, String>>) -> Unit,
    payload: String,
    onPayload: (String) -> Unit,
    busy: Boolean,
    onLoadFile: () -> Unit,
    batchTasks: List<BatchTask>,
    onPickFiles: () -> Unit,
    onRemoveTask: (String) -> Unit,
    onSendSingle: () -> Unit,
    onSendBatch: () -> Unit,
    onRetryTask: (BatchTask) -> Unit,
    sendError: String?,
    queueEmpty: Boolean,
) {
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        PrimaryTabRow(selectedTabIndex = sendTab) {
            Tab(selected = sendTab == 0, onClick = { onSendTab(0) }, text = { Text(stringResource("rabbitmq_ui_send_tab_single")) })
            Tab(selected = sendTab == 1, onClick = { onSendTab(1) }, text = { Text(stringResource("rabbitmq_ui_send_tab_batch")) })
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (sendTab == 0) {
                SingleSendPane(sendHeaders, onSendHeaders, payload, onPayload, busy, sendError, onLoadFile = onLoadFile)
            } else {
                BatchSendPane(sendHeaders, onSendHeaders, busy, batchTasks, onPickFiles, onRemoveTask, onSendBatch, onRetryTask, sendError)
            }
        }
    }
}

@Composable
private fun SingleSendPane(
    headers: List<Pair<String, String>>,
    onHeaders: (List<Pair<String, String>>) -> Unit,
    payload: String,
    onPayload: (String) -> Unit,
    busy: Boolean,
    sendError: String?,
    onLoadFile: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        HeadersEditor(headers = headers, enabled = !busy, onChange = onHeaders)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource("rabbitmq_ui_send_payload"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            PressableButton(onClick = onLoadFile, enabled = !busy) {
                Text(stringResource("rabbitmq_ui_send_load_file"), style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = payload,
            onValueChange = onPayload,
            enabled = !busy,
            singleLine = false,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.height(200.dp).fillMaxWidth(),
        )
        if (sendError != null) {
            Spacer(Modifier.height(8.dp))
            Text(sendError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BatchSendPane(
    headers: List<Pair<String, String>>,
    onHeaders: (List<Pair<String, String>>) -> Unit,
    busy: Boolean,
    tasks: List<BatchTask>,
    onPickFiles: () -> Unit,
    onRemoveTask: (String) -> Unit,
    onSendBatch: () -> Unit,
    onRetryTask: (BatchTask) -> Unit,
    sendError: String?,
) {
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PressableButton(onClick = onPickFiles, enabled = !busy) { Text(stringResource("rabbitmq_ui_send_pick_file")) }
            Text(
                text = stringResource("rabbitmq_ui_send_files_selected").withArg(tasks.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            PressableButton(
                onClick = onSendBatch,
                enabled = !busy && tasks.any { it.status == BatchStatus.WAITING },
            ) { Text(stringResource("rabbitmq_ui_send_send_all")) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource("rabbitmq_ui_send_batch_hint"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        HeadersEditor(headers = headers, enabled = !busy, onChange = onHeaders)
        Spacer(Modifier.height(8.dp))
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource("rabbitmq_ui_send_empty"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(tasks, key = { it.id }) { task ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = task.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(formatSize(task.fileSize), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
                        Box(modifier = Modifier.width(160.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                when (task.status) {
                                    BatchStatus.WAITING -> {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
                                        Text(stringResource("rabbitmq_ui_send_status_waiting"), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    }
                                    BatchStatus.SENDING -> {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Text(stringResource("rabbitmq_ui_send_status_sending"), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    }
                                    BatchStatus.SUCCESS -> {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                                        Text(stringResource("rabbitmq_ui_send_status_success"), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    }
                                    BatchStatus.FAILED -> {
                                        Icon(Icons.Default.Cancel, contentDescription = null, tint = FailedRed, modifier = Modifier.size(16.dp))
                                        Text(
                                            text = task.errorMessage ?: stringResource("rabbitmq_ui_send_status_failed"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        Box(modifier = Modifier.width(72.dp)) {
                            when {
                                task.status == BatchStatus.WAITING && !busy ->
                                    PressableButton(
                                        onClick = { onRemoveTask(task.id) },
                                        standardMinWidth = false,
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ) { Text(stringResource("rabbitmq_ui_send_remove")) }
                                task.status == BatchStatus.FAILED && !busy ->
                                    PressableButton(
                                        onClick = { onRetryTask(task) },
                                        standardMinWidth = false,
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ) { Text(stringResource("rabbitmq_ui_send_retry")) }
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
        if (sendError != null) {
            Spacer(Modifier.height(4.dp))
            Text(sendError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 「重试」文本按钮（会话失败态）。 */
@Composable
private fun TextButtonCompat(onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(stringResource("rabbitmq_ui_refresh"))
    }
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
