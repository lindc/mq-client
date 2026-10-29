@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mqclient.rocketmq.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.mqclient.mqui.file.MAX_MESSAGE_FILE_MB
import com.mqclient.mqui.file.MessageFileFailure
import com.mqclient.mqui.file.readMessageBytes
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.RocketMqSendResult
import com.mqclient.uicommon.CompactTextField
import com.mqclient.uicommon.PrimaryButton
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
import kotlinx.coroutines.CancellationException
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
internal enum class SendStatus { WAITING, SENDING, SUCCESS, FAILED }

/** 一个待发送文件的任务；[content] 在选择时已读入（读失败的文件不进列表）。 */
internal data class RocketMqSendTask(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val content: ByteArray,
    val status: SendStatus = SendStatus.WAITING,
    val result: RocketMqSendResult? = null,
    val errorMessage: String? = null,
)

/** 多文件「发送全部」一轮的汇总。 */
internal data class SendBatchSummary(val total: Int, val success: Int, val failed: Int)

/**
 * 「发送消息」Tab 的状态持有者（rocketmq-ui 内部，**与 KafkaSendMessagesState 同构**）：
 *
 * **实例挂在 RocketMqMessagesDialog 层**（而不是 Tab 里）—— 切到「浏览消息」Tab 时
 * 发送协程与任务列表都存活，回来还能看到结果；只有对话框真正关闭才销毁。发送按
 * 任务顺序**串行**执行（scope 是 dialog 级 rememberCoroutineScope），文件读取在
 * IO 线程、producer 阻塞等在 service 层的 Dispatchers.IO，状态写回走 Compose snapshot。
 *
 * 与 Kafka 的字段差异：没有目标 Topic / Partition（topic 固定为卡片主题）；换成
 * RocketMQ 特有的 tags / keys；Headers 直接是用户属性（纯字符串，无 hex 勾选）；
 * 批量任务的文件名可映射为 Keys（[mapFileNameToKeys]）。
 */
@Stable
internal class RocketMqSendMessagesState(
    private val connectionId: String,
    val topicName: String,
    private val scope: CoroutineScope,
    /**
     * 发送动作的测试替身（null = 走 [doSend] 的生产路径）。
     *
     * 存在的理由：`doSend` 依赖模块注册表（测试里拿不到），而「发送抛异常时
     * busy 必须复位、对话框仍能关」正是本轮要钉住的不变量。
     */
    private val sendOverride: (suspend (RocketMqSendTask) -> RocketMqSendResult)? = null,
) {

    var tasks by mutableStateOf<List<RocketMqSendTask>>(emptyList())
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
    var singleOutcome by mutableStateOf<RocketMqSendResult?>(null)
        private set

    /** 单文件编辑缓冲；null = 未编辑过，发送原始文件字节。 */
    var editingContent by mutableStateOf<String?>(null)

    // ---- 发送目标（单条窗格可改，多文件窗格共用）----
    var tags by mutableStateOf("")
    var keysText by mutableStateOf("")
    var headers by mutableStateOf(listOf(HeaderDraft()))
    var mapFileNameToKeys by mutableStateOf(false)

    val singleTask: RocketMqSendTask? get() = tasks.singleOrNull()

    /** 解析后的用户属性（过滤 key 为空的行）。 */
    fun parsedProperties(): List<Pair<String, String>> =
        headers.mapNotNull { draft ->
            draft.key.trim().takeIf(String::isNotEmpty)?.let { it to draft.value }
        }

    /** 发送 keys：批量 + 文件名映射 → 文件名；否则 keysText 非空 → 原文；空 = null。 */
    fun keysOf(task: RocketMqSendTask?): String? = when {
        tasks.size > 1 && mapFileNameToKeys -> task?.fileName
        keysText.isNotBlank() -> keysText.trim()
        else -> null
    }

    /**
     * 选完文件后装配任务列表（**替换**语义），IO 线程读文件；读不出来的文件跳过，
     * 失败清单返回给调用方弹提示。空文件（0 字节）允许进列表——发送前给出「消息体为空」提示。
     *
     * 单个文件的大小由 [readMessageBytes] 先行把住（超过 [com.mqclient.mqui.file.MAX_MESSAGE_FILE_BYTES]
     * 不读进内存）：消息体文件没有任何流控，误选一个几十 GB 的文件就是一次 OOM。
     */
    suspend fun loadFiles(paths: List<String>): List<MessageFileFailure> = withContext(Dispatchers.IO) {
        val loaded = mutableListOf<RocketMqSendTask>()
        val failed = mutableListOf<MessageFileFailure>()
        for (path in paths) {
            val file = java.io.File(path)
            runCatching { file.readMessageBytes() }
                .onSuccess { bytes ->
                    loaded += RocketMqSendTask(
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
            try {
                tasks = listOf(task.copy(status = SendStatus.SENDING))
                val result = safeSend(task)
                tasks = listOf(task.withResult(result))
                singleOutcome = result
            } finally {
                // **必须 finally**：doSend 会抛（连接半路断掉时 connectedContext 直接
                // 抛 IllegalStateException）。旧写法把 busy = false 放在正常路径末尾，
                // 一次抛出就让它永远为 true —— 而对话框的 ✕ / ESC 正是按 busy 决定
                // 要不要先弹「发送中确认」，于是关闭按钮从此彻底失效。
                busy = false
            }
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
            // try/finally 理由同 sendSingle：busy 卡在 true 会让 ✕ / ESC 永远只弹
            // 「发送中确认」，用户看到的就是「关闭没反应」。
            try {
                for (target in targets) {
                    updateTask(target.id) { it.copy(status = SendStatus.SENDING, errorMessage = null) }
                    val result = safeSend(target)
                    updateTask(target.id) { it.withResult(result) }
                    if (result.success) ok++ else fail++
                    processedCount++
                }
                batchSummary = SendBatchSummary(total = targets.size, success = ok, failed = fail)
            } finally {
                // 取消（关对话框）后未处理完的 SENDING 项回落到 WAITING：还能继续发。
                tasks = tasks.map {
                    if (it.status == SendStatus.SENDING) it.copy(status = SendStatus.WAITING) else it
                }
                busy = false
            }
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

    fun canRemove(task: RocketMqSendTask): Boolean = !busy && task.status == SendStatus.WAITING

    /* ------------------------------ internals ------------------------------ */

    private fun updateTask(id: String, transform: (RocketMqSendTask) -> RocketMqSendTask) {
        // ByteArray 字段引用相等 —— 一律按 id 匹配，不做对象相等判断。
        tasks = tasks.map { if (it.id == id) transform(it) else it }
    }

    /** 实际发送的字节：单文件模式编辑过 → 编辑文本（UTF-8）；否则文件原始内容。 */
    private fun contentOf(task: RocketMqSendTask): ByteArray =
        if (tasks.size == 1 && editingContent != null) {
            editingContent!!.toByteArray(Charsets.UTF_8)
        } else {
            task.content
        }

    /**
     * [doSend] 的**异常安全外壳**：任何非取消异常都转成失败结果，绝不穿出协程。
     *
     * 为什么这条是硬要求：本状态挂在对话框的 `rememberCoroutineScope()` 上，协程
     * 同样是组合 Job 的子节点。未捕获异常会**取消父 Job** —— 整个对话框停止重组，
     * 之后 ✕ 收到点击也不再重绘，表现就是「整个对话框卡死，点关闭毫无反应」。
     * 与 ui-common 详情渲染里那条「异常绝不能穿出协程」是同一个坑。
     */
    private suspend fun safeSend(task: RocketMqSendTask): RocketMqSendResult =
        try {
            sendOverride?.invoke(task) ?: doSend(task)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            RocketMqSendResult.failure(error.message ?: error::class.simpleName ?: "unknown")
        }

    private suspend fun doSend(task: RocketMqSendTask): RocketMqSendResult =
        RocketMqModule.messageServiceOrNull()?.sendMessage(
            connectionId = connectionId,
            topic = topicName,
            tags = tags.trim().takeIf(String::isNotEmpty),
            keys = keysOf(task),
            body = contentOf(task),
            userProperties = parsedProperties(),
        ) ?: RocketMqSendResult.failure(stringResource("rocketmq_ui_send_service_not_ready"))

    private fun RocketMqSendTask.withResult(result: RocketMqSendResult): RocketMqSendTask = if (result.success) {
        copy(status = SendStatus.SUCCESS, result = result, errorMessage = null)
    } else {
        copy(status = SendStatus.FAILED, errorMessage = result.errorMessage ?: "Unknown")
    }
}

/**
 * 「发送消息」Tab：顶部工具栏（选择文件 / 数量 / 清空）+ 目标行（Topic 只读 /
 * Tags / Keys —— RocketMQ 特有）+ 内容区（空态 / 单文件可编辑预览 / 多文件任务表格）+
 * 底部（结果 / 汇总 + 发送按钮）。状态在 [RocketMqSendMessagesState]（dialog 层持有）。
 * 布局与 KafkaMessageSendTab 逐行同构。
 */
@Composable
internal fun RocketMqMessageSendTab(
    state: RocketMqSendMessagesState,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val pickScope = rememberCoroutineScope()

    fun pickAndLoad() {
        pickScope.launch {
            val paths = pickFiles(stringResource("rocketmq_ui_send_files_pick"))
            if (paths.isEmpty()) return@launch
            val failed = state.loadFiles(paths)
            failed.forEach { failure ->
                val text =
                    if (failure.tooLarge) {
                        stringResource("rocketmq_ui_send_file_too_large")
                            .withArg(failure.fileName, MAX_MESSAGE_FILE_MB)
                    } else {
                        stringResource("rocketmq_ui_send_read_failed").withArg(failure.fileName)
                    }
                snackbarHostState.showSnackbar(text)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---- 1. 工具栏 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RMSG_ROW_HPADDING, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton(onClick = ::pickAndLoad) { Text(stringResource("rocketmq_ui_send_files_pick")) }
            if (state.tasks.isNotEmpty()) {
                Text(
                    text = stringResource("rocketmq_ui_send_files_selected").withArg(state.tasks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton(
                onClick = state::clear,
                enabled = state.tasks.isNotEmpty(),
            ) { Text(stringResource("rocketmq_ui_send_clear")) }
        }
        HorizontalDivider()

        // ---- 2. 目标行（Topic 只读 / Tags / Keys）----
        TargetRow(state)

        // ---- 3. 内容区 ----
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.tasks.isEmpty() ->
                    ObjectListEmptyState(message = stringResource("rocketmq_ui_send_files_empty"))
                state.tasks.size == 1 -> SingleSendPane(
                    state = state,
                    onLoadFailed = { failure ->
                        pickScope.launch {
                            snackbarHostState.showSnackbar(
                                if (failure.tooLarge) {
                                    stringResource("rocketmq_ui_send_file_too_large")
                                        .withArg(failure.fileName, MAX_MESSAGE_FILE_MB)
                                } else {
                                    stringResource("rocketmq_ui_send_read_failed").withArg(failure.fileName)
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
                    snackbarHostState.showSnackbar(stringResource("rocketmq_ui_send_empty_body"))
                }
            },
        )
    }
}

/** 目标行：Topic（只读展示）/ Tags / Keys + 用户属性增删入口。 */
@Composable
private fun TargetRow(state: RocketMqSendMessagesState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = RMSG_ROW_HPADDING, vertical = 6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactTextField(
                value = state.topicName,
                onValueChange = {},
                label = stringResource("rocketmq_ui_send_topic"),
                readOnly = true,
                modifier = Modifier.weight(1.4f),
            )
            CompactTextField(
                value = state.tags,
                onValueChange = { state.tags = it },
                label = stringResource("rocketmq_ui_send_tags"),
                modifier = Modifier.weight(1f),
            )
            CompactTextField(
                value = state.keysText,
                onValueChange = { state.keysText = it },
                label = stringResource("rocketmq_ui_send_keys"),
                modifier = Modifier.weight(1f),
            )
        }
        // 用户属性编辑器（可增删键值行）—— ui-common 公共组件（Kafka Headers 同款；
        // RocketMQ 用户属性是纯字符串，hexText 传空隐藏 HEX 勾选）。
        if (state.tasks.size <= 1) {
            // HeadersEditor 行数随用户增删无上限：**有界化**（180dp 上限 + 内部滚动），
            // 否则顶部固定区无界膨胀，把 weight(1f) 内容区与底部发送栏顶出视口。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                HeadersEditor(
                    headers = state.headers,
                    onHeadersChange = { state.headers = it },
                    title = stringResource("rocketmq_ui_send_headers"),
                    addText = stringResource("rocketmq_ui_send_header_add"),
                    keyPlaceholder = stringResource("rocketmq_ui_send_header_key"),
                    valuePlaceholder = stringResource("rocketmq_ui_send_header_value"),
                    hexText = "",
                    removeText = stringResource("rocketmq_ui_send_remove"),
                )
            }
        }
    }
}

/* --------------------------- 单文件模式 --------------------------- */

@Composable
private fun SingleSendPane(
    state: RocketMqSendMessagesState,
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = RMSG_ROW_HPADDING, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource("rocketmq_ui_send_value"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            SecondaryButton(
                onClick = {
                    // 「从文件加载」：选文件读字节进编辑缓冲（大文件同样截断预览）。
                    scope.launch {
                        val path = pickFile(stringResource("rocketmq_ui_send_load_file"), emptyList())
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
            ) { Text(stringResource("rocketmq_ui_send_load_file")) }
        }
        OutlinedTextField(
            value = display,
            onValueChange = { state.editingContent = it },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = RMSG_ROW_HPADDING, vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        if (truncated && state.editingContent == null) {
            Text(
                text = stringResource("rocketmq_ui_send_preview_truncated"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = RMSG_ROW_HPADDING, vertical = 4.dp),
            )
        }
    }
}

/* --------------------------- 多文件模式 --------------------------- */

@Composable
private fun MultiSendPane(state: RocketMqSendMessagesState) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 文件名映射为 Keys 的开关（批量模式特有）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RMSG_ROW_HPADDING, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource("rocketmq_ui_send_map_filename_keys"),
                style = MaterialTheme.typography.bodySmall,
            )
            Switch(
                checked = state.mapFileNameToKeys,
                onCheckedChange = { state.mapFileNameToKeys = it },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = RMSG_ROW_HPADDING, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TableHeaderText(stringResource("rocketmq_ui_send_files_col_name"), Modifier.weight(1f))
            TableHeaderText(stringResource("rocketmq_ui_send_files_col_size"), Modifier.width(80.dp))
            TableHeaderText(stringResource("rocketmq_ui_send_files_col_status"), Modifier.width(110.dp))
            TableHeaderText(stringResource("rocketmq_ui_send_files_col_actions"), Modifier.width(80.dp))
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
private fun SendTaskRow(task: RocketMqSendTask, state: RocketMqSendMessagesState) {
    // 行渲染（悬停 / 文件名 / 大小 / 状态图标 / 动作按钮 / 失败原因）在 mq-ui 的
    // SendTaskRow 里三模块共用；这里只负责「本协议的状态怎么映射 + 文案怎么翻译」。
    val action: Pair<String, () -> Unit>? = when {
        task.status == SendStatus.WAITING && state.canRemove(task) ->
            stringResource("rocketmq_ui_send_remove") to { state.removeTask(task.id) }
        task.status == SendStatus.FAILED && !state.busy ->
            stringResource("rocketmq_ui_send_retry") to { state.retryTask(task.id) }
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
    SendStatus.WAITING -> "rocketmq_ui_send_status_waiting"
    SendStatus.SENDING -> "rocketmq_ui_send_status_sending"
    SendStatus.SUCCESS -> "rocketmq_ui_send_status_success"
    SendStatus.FAILED -> "rocketmq_ui_send_status_failed"
}

/* ------------------------------- 底部 ------------------------------- */

private val SENT_AT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
private fun SendBottomBar(
    state: RocketMqSendMessagesState,
    onEmptyBodyWarning: () -> Unit,
) {
    val single = state.tasks.size == 1
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = RMSG_ROW_HPADDING, vertical = 8.dp)) {
        // 单文件：最近一次发送结果（成功带 msgId/queue/offset + 时间，失败带原因 + 重试）。
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
                        text = stringResource("rocketmq_ui_send_result_ok")
                            .withArg(outcome.msgId ?: "", outcome.queueId ?: -1, outcome.offset ?: -1),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    outcome.brokerName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
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
                        stringResource("rocketmq_ui_send_btn_retry"),
                        onClick = { state.sendSingle(onEmptyBodyWarning) },
                    )
                }
            }
        }
        // 多文件：一轮完成后的汇总。
        state.batchSummary?.let { summary ->
            Text(
                text = stringResource("rocketmq_ui_send_summary")
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
                enabled = !state.busy && state.tasks.isNotEmpty() && !noWaiting,
            ) {
                Text(
                    text = when {
                        state.busy && !single ->
                            stringResource("rocketmq_ui_send_sending_progress")
                                .withArg(state.processedCount, state.batchTotal)
                        state.busy -> stringResource("rocketmq_ui_send_btn_sending")
                        single -> stringResource("rocketmq_ui_send_btn_send")
                        else -> stringResource("rocketmq_ui_send_btn_send_all")
                    },
                )
            }
        }
    }
}
