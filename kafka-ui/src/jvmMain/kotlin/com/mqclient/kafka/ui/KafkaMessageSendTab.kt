@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mqclient.kafka.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import com.mqclient.kafka.KafkaMessageHeader
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.KafkaSendResult
import com.mqclient.mqui.file.MAX_MESSAGE_FILE_MB
import com.mqclient.mqui.file.MessageFileFailure
import com.mqclient.mqui.file.readMessageBytes
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.uicommon.CompactTextField
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.CompactTextField
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.HeaderDraft
import com.mqclient.uicommon.dialog.HeadersEditor
import com.mqclient.uicommon.file.pickFile
import com.mqclient.uicommon.file.pickFiles
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.table.TableHeaderText
import com.mqclient.uicommon.table.TableRowActionIcon
import com.mqclient.uicommon.table.formatSize
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mqclient.mqui.send.SendTaskRow as MqSendTaskRow
import com.mqclient.mqui.send.SendTaskStatus
import com.mqclient.mqui.send.SmallSendButton as SmallSecondaryButton

/** 单文件预览的截断阈值：> 100KB 只显示前 10000 字符（发送仍发完整内容，除非编辑过）。 */
private const val TRUNCATION_THRESHOLD_BYTES = 100_000
private const val PREVIEW_MAX_CHARS = 10_000

private val SuccessGreen = Color(0xFF4CAF50)
private val FailedRed = Color(0xFFF44336)
private val WaitingGray = Color(0xFF9E9E9E)

/** 发送任务状态机：等待 → 发送中 → 成功 / 失败（失败可重试回发送中）。 */
enum class SendStatus { WAITING, SENDING, SUCCESS, FAILED }

/** Header 编辑草稿（[HeaderDraft]）→ Kafka 消息头；key 为空或 hex 解析失败返回 null（发送时跳过该行）。 */
private fun HeaderDraft.toHeader(): KafkaMessageHeader? {
    if (key.isBlank()) return null
    val bytes = if (valueIsHex) {
        val compact = value.trim().replace(" ", "")
        if (compact.length % 2 != 0 || compact.any { it.isDigit().not() && it.lowercaseChar() !in 'a'..'f' }) {
            return null
        }
        compact.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } else {
        value.toByteArray(Charsets.UTF_8)
    }
    return KafkaMessageHeader(key.trim(), bytes)
}

/** 一个待发送文件的任务；[content] 在选择时已读入（读失败的文件不进列表）。 */
data class KafkaSendTask(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val content: ByteArray,
    val status: SendStatus = SendStatus.WAITING,
    val result: KafkaSendResult? = null,
    val errorMessage: String? = null,
)

/** 多文件「发送全部」一轮的汇总。 */
data class SendBatchSummary(val total: Int, val success: Int, val failed: Int)

/**
 * 「发送消息」Tab 的状态持有者（kafka-ui 内部，与 ibm-mq 的 SendMessagesState 同构）：
 *
 * **实例挂在 KafkaMessagesDialog 层**（而不是 Tab 里）—— 切到「浏览消息」Tab 时发送
 * 协程与任务列表都存活，回来还能看到结果；只有对话框真正关闭才销毁。发送按任务顺序
 * **串行**执行（[scope] 是 dialog 级 rememberCoroutineScope），文件读取在 IO 线程、
 * producer 阻塞等在 service 层的 Dispatchers.IO，状态写回走 Compose snapshot。
 *
 * 与 MQ 版的字段差异：高级属性换成 Kafka 的 targetTopic / targetPartition / keyText /
 * headers；批量任务的文件名可映射为 key（[mapFileNameToKey]）。
 */
@Stable
internal class KafkaSendMessagesState(
    private val connectionId: String,
    private val topicName: String,
    private val scope: CoroutineScope,
) {

    var tasks by mutableStateOf<List<KafkaSendTask>>(emptyList())
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
    var singleOutcome by mutableStateOf<KafkaSendResult?>(null)
        private set

    /** 单文件编辑缓冲；null = 未编辑过，发送原始文件字节。 */
    var editingContent by mutableStateOf<String?>(null)

    // ---- 发送目标（单条窗格可改，多文件窗格共用）----
    var targetTopic by mutableStateOf(topicName)
    var targetPartition by mutableStateOf("") // 空 = 不指定（按 key 哈希或轮询）
    var keyText by mutableStateOf("")
    var headers by mutableStateOf(listOf(HeaderDraft()))
    var mapFileNameToKey by mutableStateOf(false)

    val singleTask: KafkaSendTask? get() = tasks.singleOrNull()

    /** 解析后的消息头（过滤 key 为空 / hex 非法的行）。 */
    fun parsedHeaders(): List<KafkaMessageHeader> = headers.mapNotNull { it.toHeader() }

    /** 发送 key：批量 + 文件名映射 → 文件名字节；否则 keyText 非空 → UTF-8 字节；空 = null。 */
    fun keyBytesOf(task: KafkaSendTask?): ByteArray? = when {
        tasks.size > 1 && mapFileNameToKey -> task?.fileName?.toByteArray(Charsets.UTF_8)
        keyText.isNotBlank() -> keyText.toByteArray(Charsets.UTF_8)
        else -> null
    }

    /** 目标分区：空串 = null（不指定）；非法数字视同不指定。 */
    fun partitionOf(): Int? = targetPartition.trim().toIntOrNull()

    /**
     * 选完文件后装配任务列表（**替换**语义），IO 线程读文件；读不出来的文件跳过，
     * 失败清单返回给调用方弹提示。空文件（0 字节）允许进列表——发送前给出「消息体为空」提示。
     *
     * 单个文件的大小由 [readMessageBytes] 先行把住（超过 [com.mqclient.mqui.file.MAX_MESSAGE_FILE_BYTES]
     * 不读进内存）：消息体文件没有任何流控，误选一个几十 GB 的文件就是一次 OOM。
     */
    suspend fun loadFiles(paths: List<String>): List<MessageFileFailure> = withContext(Dispatchers.IO) {
        val loaded = mutableListOf<KafkaSendTask>()
        val failed = mutableListOf<MessageFileFailure>()
        for (path in paths) {
            val file = java.io.File(path)
            runCatching { file.readMessageBytes() }
                .onSuccess { bytes ->
                    loaded += KafkaSendTask(
                        id = UUID.randomUUID().toString(),
                        fileName = file.name,
                        fileSize = bytes.size.toLong(),
                        content = bytes,
                    )
                }
                .onFailure { failed += MessageFileFailure.of(file.name, it) }
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

    fun canRemove(task: KafkaSendTask): Boolean = !busy && task.status == SendStatus.WAITING

    /* ------------------------------ internals ------------------------------ */

    private fun updateTask(id: String, transform: (KafkaSendTask) -> KafkaSendTask) {
        // ByteArray 字段引用相等 —— 一律按 id 匹配，不做对象相等判断。
        tasks = tasks.map { if (it.id == id) transform(it) else it }
    }

    /** 实际发送的字节：单文件模式编辑过 → 编辑文本（UTF-8）；否则文件原始内容。 */
    private fun contentOf(task: KafkaSendTask): ByteArray =
        if (tasks.size == 1 && editingContent != null) {
            editingContent!!.toByteArray(Charsets.UTF_8)
        } else {
            task.content
        }

    private suspend fun doSend(task: KafkaSendTask): KafkaSendResult =
        KafkaModule.messageServiceOrNull()?.sendMessage(
            connectionId = connectionId,
            topic = targetTopic.trim(),
            value = contentOf(task),
            key = keyBytesOf(task),
            partition = partitionOf(),
            headers = parsedHeaders(),
        ) ?: KafkaSendResult.failure(stringResource("kafka_ui_send_service_not_ready"))

    private fun KafkaSendTask.withResult(result: KafkaSendResult): KafkaSendTask = if (result.success) {
        copy(status = SendStatus.SUCCESS, result = result, errorMessage = null)
    } else {
        copy(status = SendStatus.FAILED, errorMessage = result.errorMessage ?: "Unknown")
    }
}

/**
 * 「发送消息」Tab：顶部工具栏（选择文件 / 数量 / 清空）+ 目标行（Topic / Partition /
 * Key / Headers —— Kafka 特有）+ 内容区（空态 / 单文件可编辑预览 / 多文件任务表格）+
 * 底部（结果 / 汇总 + 发送按钮）。状态在 [KafkaSendMessagesState]（dialog 层持有）。
 */
@Composable
internal fun KafkaMessageSendTab(
    state: KafkaSendMessagesState,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val pickScope = rememberCoroutineScope()

    fun pickAndLoad() {
        pickScope.launch {
            val paths = pickFiles(stringResource("kafka_ui_send_files_pick"))
            if (paths.isEmpty()) return@launch
            val failed = state.loadFiles(paths)
            failed.forEach { failure ->
                val text =
                    if (failure.tooLarge) {
                        stringResource("kafka_ui_send_file_too_large")
                            .withArg(failure.fileName, MAX_MESSAGE_FILE_MB)
                    } else {
                        stringResource("kafka_ui_send_read_failed").withArg(failure.fileName)
                    }
                snackbarHostState.showSnackbar(text)
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
            PrimaryButton(onClick = ::pickAndLoad) { Text(stringResource("kafka_ui_send_files_pick")) }
            if (state.tasks.isNotEmpty()) {
                Text(
                    text = stringResource("kafka_ui_send_files_selected").withArg(state.tasks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton(
                onClick = state::clear,
                enabled = state.tasks.isNotEmpty(),
            ) { Text(stringResource("kafka_ui_send_clear")) }
        }
        HorizontalDivider()

        // ---- 2. 目标行（Topic / Partition / Key）----
        TargetRow(state)

        // ---- 3. 内容区 ----
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.tasks.isEmpty() ->
                    ObjectListEmptyState(message = stringResource("kafka_ui_send_files_empty"))
                state.tasks.size == 1 -> SingleSendPane(
                    state = state,
                    onLoadFailed = { failure ->
                        pickScope.launch {
                            snackbarHostState.showSnackbar(
                                if (failure.tooLarge) {
                                    stringResource("kafka_ui_send_file_too_large")
                                        .withArg(failure.fileName, MAX_MESSAGE_FILE_MB)
                                } else {
                                    stringResource("kafka_ui_send_read_failed").withArg(failure.fileName)
                                },
                            )
                        }
                    },
                )
                else -> MultiSendPane(state)
            }
            ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }

        HorizontalDivider()

        // ---- 4. 底部：结果 / 汇总 + 发送按钮 ----
        SendBottomBar(
            state = state,
            onEmptyBodyWarning = {
                pickScope.launch {
                    snackbarHostState.showSnackbar(stringResource("kafka_ui_send_empty_body"))
                }
            },
        )
    }
}

/** 目标行：Topic（预填可改）/ Partition（可选）/ Key（可选）+ Headers 增删入口。 */
@Composable
private fun TargetRow(state: KafkaSendMessagesState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HPADDING, vertical = 6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactTextField(
                value = state.targetTopic,
                onValueChange = { state.targetTopic = it },
                label = stringResource("kafka_ui_send_topic"),
                modifier = Modifier.weight(1.4f),
            )
            CompactTextField(
                value = state.targetPartition,
                onValueChange = { state.targetPartition = it.filter(Char::isDigit).take(3) },
                label = stringResource("kafka_ui_send_partition"),
                placeholder = stringResource("kafka_ui_send_partition_hint"),
                modifier = Modifier.weight(0.6f),
            )
            CompactTextField(
                value = state.keyText,
                onValueChange = { state.keyText = it },
                label = stringResource("kafka_ui_send_key"),
                modifier = Modifier.weight(1f),
            )
        }
        // Headers 编辑器（可增删键值行，value 可勾 hex）—— ui-common 公共组件，
        // IBM MQ / RocketMQ 发送页同款（文案各模块自传）。
        if (state.tasks.size <= 1) {
            HeadersEditor(
                headers = state.headers,
                onHeadersChange = { state.headers = it },
                title = stringResource("kafka_ui_send_headers"),
                addText = stringResource("kafka_ui_send_header_add"),
                keyPlaceholder = stringResource("kafka_ui_send_header_key"),
                valuePlaceholder = stringResource("kafka_ui_send_header_value"),
                hexText = stringResource("kafka_ui_send_header_hex"),
                removeText = stringResource("kafka_ui_send_remove"),
            )
        }
    }
}

/* --------------------------- 单文件模式 --------------------------- */

@Composable
private fun SingleSendPane(
    state: KafkaSendMessagesState,
    /** 「从文件加载」读不进来时的上报口（父层统一弹 snackbar；本窗格没有自己的 host）。 */
    onLoadFailed: (MessageFileFailure) -> Unit,
) {
    val task = state.singleTask ?: return
    val scope = rememberCoroutineScope()
    val rawText = remember(task.id) { task.content.toString(Charsets.UTF_8) }
    val truncated = task.fileSize > TRUNCATION_THRESHOLD_BYTES
    val display = state.editingContent
        ?: rawText.takeIf { !truncated || it.length <= PREVIEW_MAX_CHARS }
        ?: rawText.take(PREVIEW_MAX_CHARS)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HPADDING, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource("kafka_ui_send_value"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            SecondaryButton(
                onClick = {
                    // 「从文件加载」：选文件读字节进编辑缓冲（大文件同样截断预览）。
                    scope.launch {
                        val path = pickFile(stringResource("kafka_ui_send_load_file"), emptyList())
                            ?: return@launch
                        val file = java.io.File(path)
                        val loaded = withContext(Dispatchers.IO) {
                            // 先看尺寸再读：单条窗格的「从文件加载」同样不能把超大文件进堆。
                            runCatching { file.readMessageBytes() }
                        }
                        loaded
                            .onSuccess { state.editingContent = String(it, Charsets.UTF_8) }
                            .onFailure { onLoadFailed(MessageFileFailure.of(file.name, it)) }
                    }
                },
                standardMinWidth = false,
            ) { Text(stringResource("kafka_ui_send_load_file")) }
        }
        OutlinedTextField(
            value = display,
            onValueChange = { state.editingContent = it },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = ROW_HPADDING, vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        if (truncated && state.editingContent == null) {
            Text(
                text = stringResource("kafka_ui_send_preview_truncated"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ROW_HPADDING, vertical = 4.dp),
            )
        }
    }
}

/* --------------------------- 多文件模式 --------------------------- */

@Composable
private fun MultiSendPane(state: KafkaSendMessagesState) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 文件名映射为 Key 的开关（批量模式特有）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HPADDING, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource("kafka_ui_send_map_filename_key"),
                style = MaterialTheme.typography.bodySmall,
            )
            Switch(
                checked = state.mapFileNameToKey,
                onCheckedChange = { state.mapFileNameToKey = it },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = ROW_HPADDING, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TableHeaderText(stringResource("kafka_ui_send_files_col_name"), Modifier.weight(1f))
            TableHeaderText(stringResource("kafka_ui_send_files_col_size"), Modifier.width(80.dp))
            TableHeaderText(stringResource("kafka_ui_send_files_col_status"), Modifier.width(110.dp))
            TableHeaderText(stringResource("kafka_ui_send_files_col_actions"), Modifier.width(80.dp))
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
private fun SendTaskRow(task: KafkaSendTask, state: KafkaSendMessagesState) {
    // 行渲染（悬停 / 文件名 / 大小 / 状态图标 / 动作按钮 / 失败原因）在 mq-ui 的
    // SendTaskRow 里三模块共用；这里只负责「本协议的状态怎么映射 + 文案怎么翻译」。
    val action: Pair<String, () -> Unit>? = when {
        task.status == SendStatus.WAITING && state.canRemove(task) ->
            stringResource("kafka_ui_send_remove") to { state.removeTask(task.id) }
        task.status == SendStatus.FAILED && !state.busy ->
            stringResource("kafka_ui_send_retry") to { state.retryTask(task.id) }
        else -> null
    }
    MqSendTaskRow(
        fileName = task.fileName,
        sizeBytes = task.fileSize,
        status = task.status.toSharedStatus(),
        statusText = stringResource(task.status.i18nKey()),
        errorMessage = task.errorMessage,
        actionLabel = action?.first,
        onAction = action?.second,
    )
}

/** 本模块的四态 → mq-ui 的共用四态（两边语义一致，改名只是为了避免枚举撞名）。 */
private fun SendStatus.toSharedStatus(): SendTaskStatus = when (this) {
    SendStatus.WAITING -> SendTaskStatus.WAITING
    SendStatus.SENDING -> SendTaskStatus.SENDING
    SendStatus.SUCCESS -> SendTaskStatus.SUCCESS
    SendStatus.FAILED -> SendTaskStatus.FAILED
}

/** 状态 → 本模块 i18n 键（`when` 穷举：新增状态时编译器会在这里报错）。 */
private fun SendStatus.i18nKey(): String = when (this) {
    SendStatus.WAITING -> "kafka_ui_send_status_waiting"
    SendStatus.SENDING -> "kafka_ui_send_status_sending"
    SendStatus.SUCCESS -> "kafka_ui_send_status_success"
    SendStatus.FAILED -> "kafka_ui_send_status_failed"
}

/* ------------------------------- 底部 ------------------------------- */

private val SENT_AT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun SendBottomBar(
    state: KafkaSendMessagesState,
    onEmptyBodyWarning: () -> Unit,
) {
    val single = state.tasks.size == 1
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HPADDING, vertical = 8.dp)) {
        // 单文件：最近一次发送结果（成功带 partition/offset + 时间，失败带原因 + 重试）。
        state.singleOutcome?.let { outcome ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (outcome.success) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = SuccessGreen,
                    )
                    Text(
                        text = stringResource("kafka_ui_send_result_ok")
                            .withArg(outcome.partition ?: -1, outcome.offset ?: -1),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    outcome.timestamp?.let {
                        Text(
                            text = SENT_AT_FORMATTER.format(
                                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = FailedRed,
                    )
                    Text(
                        text = outcome.errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    SmallSecondaryButton(
                        stringResource("kafka_ui_send_btn_retry"),
                        onClick = { state.sendSingle(onEmptyBodyWarning) },
                    )
                }
            }
        }
        // 多文件：一轮完成后的汇总。
        state.batchSummary?.let { summary ->
            Text(
                text = stringResource("kafka_ui_send_summary")
                    .withArg(summary.total, summary.success, summary.failed),
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
            PrimaryButton(
                onClick = {
                    if (single) state.sendSingle(onEmptyBodyWarning) else state.sendAll()
                },
                enabled = !state.busy && state.tasks.isNotEmpty() && !noWaiting &&
                    state.targetTopic.isNotBlank(),
            ) {
                Text(
                    text = when {
                        state.busy && !single ->
                            stringResource("kafka_ui_send_sending_progress")
                                .withArg(state.processedCount, state.batchTotal)
                        state.busy -> stringResource("kafka_ui_send_btn_sending")
                        single -> stringResource("kafka_ui_send_btn_send")
                        else -> stringResource("kafka_ui_send_btn_send_all")
                    },
                )
            }
        }
    }
}
