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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.mqui.state.LoadState
import com.mqclient.rabbitmq.RabbitMqFetchedMessage
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.RabbitMqQueueSession
import com.mqclient.rabbitmq.RabbitMqSessionOpener
import com.mqclient.uicommon.CompactTextField
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.file.pickFiles
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.table.formatSize
import com.mqclient.uicommon.failureText
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 拉取条数上限：basicGet 逐条循环，防大队列一次拉爆内存。 */
private const val FETCH_HARD_LIMIT = 100

/**
 * 单次 AMQP 调用的硬超时（建会话 / basicGet / requeue / ack / heldCount）。
 *
 * 为什么需要它：AMQP 的调用是**阻塞式**的，默认 RPC 超时以分钟计。broker 不应答时
 * 会话会一直挂在 `basicGet` 里，UI 侧 `fetching` / `sessionState` 永远不会落地。
 * 5s 到了就当成失败把状态置下去。
 *
 * **局限（必须知道）**：`withTimeout` 只能让**等待方**放弃，不能杀死已经在阻塞
 * socket 上的那个调用——它会在后台线程里继续挂着，直到连接层自身超时。要真正
 * 回收线程，得靠 AMQP 客户端自己的超时配置；这里保证的是「界面绝不无限期转圈」。
 */
private const val MESSAGE_TIMEOUT_MS = 5_000L

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
 * UI 免填 Exchange / Routing Key；纯文件驱动（Headers + 多文件串行直发原始字节，
 * 单文件即单发）。发送成功自动触发一次「重新拉取」刷新列表。
 *
 * 会话生命周期：打开时 [RabbitMqModule.openMessageSession]（三态），onDispose
 * 走 [RabbitMqModule.closeMessageSessionAsync]（同步回调里挂起 close 另起协程）。
 */
@Composable
fun RabbitMqQueueMessagesDialog(
    connectionId: String,
    queueName: String,
    onDismiss: () -> Unit,
    /**
     * 打开会话的工厂（可注入）：默认真连 AMQP；测试传替身以观察「重开前先关旧会话」。
     */
    openSession: RabbitMqSessionOpener = RabbitMqSessionOpener.Default,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // 会话的**所有者**：开会话只能经它（内部先关旧的），UI 侧的 `session` 只是镜像。
    val sessionSlot = remember(openSession) { QueueSessionSlot(openSession) }
    var session by remember { mutableStateOf<RabbitMqQueueSession?>(null) }
    // Idle = **还没开过会话**（对话框刚开、用户还没点任何动作）。
    // 与「正在开」区分开是 P2-1 的前提：只有用户显式动作才会把它推进到 Loading。
    var sessionState by remember(queueName) { mutableStateOf<LoadState<Unit>>(LoadState.Idle) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // ---- 浏览状态 ----
    var messages by remember(queueName) { mutableStateOf(listOf<RabbitMqFetchedMessage>()) }
    var fetching by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf("20") }
    var exhausted by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<RabbitMqFetchedMessage?>(null) }
    var forwarding by remember { mutableStateOf<List<RabbitMqFetchedMessage>>(emptyList()) }

    // ---- 发送状态 ----
    var sendHeaders by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var batchTasks by remember { mutableStateOf(listOf<BatchTask>()) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }

    // 对话框销毁 → 永久作废本槽，并把当前会话交给模块异步关。
    // 用 dispose() 而不是 detach()：若此刻恰有 open() 在建连中，它回来时会被拒绝
    // 并把手里的连接当场关掉 —— 否则那条连接无人认领，就是一条泄漏。
    DisposableEffect(Unit) {
        onDispose { RabbitMqModule.closeMessageSessionAsync(sessionSlot.dispose()) }
    }

    // 会话状态机：**建连推迟到用户的第一个显式动作**（拉取 / 发送）。
    //
    // 为什么不打开就建：打开对话框原本会另开一条 AMQP 连接（TCP + 握手 + passive
    // 声明），对远程 broker + TLS 是几百毫秒到数秒。用户只是想看一眼，却要等一次
    // 完整建连——这就是「弹出很慢」的来源。现在打开只渲染外壳，点「拉取消息」才连。
    //
    // 重开（换队列 / 重试）一律经 [QueueSessionSlot.open]：它保证**先关旧会话再开新的**。
    // `session` 只是给 UI 读的镜像，不是句柄的所有者 —— 开新会话只有槽这一条路。
    //
    // 关掉之后消息列表上的 deliveryTag 全部作废：tag 是 channel 级、新 channel 从 1 重新
    // 计数，旧 tag 拿去 ack 会命中新会话里的**另一条**消息。所以一并清空列表，让用户
    // 在新会话上重新拉取。
    // 超时文案先取好：LaunchedEffect / launch / 异常分支都不是 @Composable 上下文。
    val timeoutText = stringResource("rabbitmq_ui_msg_timeout").withArg(MESSAGE_TIMEOUT_MS / 1_000)

    // 只重置状态、**不建连**。
    LaunchedEffect(connectionId, queueName) {
        // 换队列时把上一队列的会话交出去关掉，不必等下一个 open 才回收。
        RabbitMqModule.closeMessageSessionAsync(sessionSlot.detach())
        session = null
        messages = emptyList()
        held = 0
        exhausted = false
        // 详情 / 转发选择都持有旧会话的消息对象，同样作废。
        detail = null
        forwarding = emptyList()
        sessionState = LoadState.Idle
    }

    /**
     * 确保会话已开（幂等）；失败返回 null 并把原因写进 [sessionState]。
     *
     * 超时分支必须排在取消分支**之前**：`TimeoutCancellationException` 是
     * `CancellationException` 的子类，顺序反了会把超时当成外部取消原样上抛，
     * 协程直接死掉、状态永远停在 Loading。
     */
    suspend fun ensureSession(): RabbitMqQueueSession? {
        session?.let { return it }
        sessionState = LoadState.Loading
        return try {
            val opened = withTimeout(MESSAGE_TIMEOUT_MS) { sessionSlot.open(connectionId, queueName) }
            session = opened
            sessionState = LoadState.Success(Unit)
            opened
        } catch (e: TimeoutCancellationException) {
            sessionState = LoadState.Error(timeoutText)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sessionState = LoadState.Error(failureText(e))
            null
        }
    }

    fun fetchMore() {
        scope.launch {
            fetching = true
            try {
                val s = ensureSession() ?: return@launch
                val batch = withTimeout(MESSAGE_TIMEOUT_MS) { s.fetchNext(effectiveLimit(limit)) }
                messages = messages + batch
                exhausted = batch.isEmpty()
                held = withTimeout(MESSAGE_TIMEOUT_MS) { s.heldCount() }
            } catch (e: TimeoutCancellationException) {
                snackbar.showSnackbar(timeoutText)
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
        scope.launch {
            fetching = true
            try {
                val s = ensureSession() ?: return@launch
                withTimeout(MESSAGE_TIMEOUT_MS) { s.requeueAll() }
                messages = emptyList()
                exhausted = false
                val batch = withTimeout(MESSAGE_TIMEOUT_MS) { s.fetchNext(effectiveLimit(limit)) }
                messages = batch
                exhausted = batch.isEmpty()
                held = withTimeout(MESSAGE_TIMEOUT_MS) { s.heldCount() }
            } catch (e: TimeoutCancellationException) {
                snackbar.showSnackbar(timeoutText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(failureText(e))
            } finally {
                fetching = false
            }
        }
    }

    fun onForwarded(forwarded: List<RabbitMqFetchedMessage>, deleteSource: Boolean) {
        if (forwarded.isEmpty()) return
        if (deleteSource) {
            val tags = forwarded.map { it.deliveryTag }.toSet()
            messages = messages.filterNot { it.deliveryTag in tags }
            // heldCount 也是 AMQP 往返，同样封顶：拿不到计数就不刷新，不拖死对话框。
            scope.launch {
                held =
                    try {
                        withTimeout(MESSAGE_TIMEOUT_MS) { session?.heldCount() } ?: held
                    } catch (e: TimeoutCancellationException) {
                        held
                    } catch (e: CancellationException) {
                        // runCatching 在这里是错的：它会把取消一并吞掉。手写分支保住语义。
                        throw e
                    } catch (e: Exception) {
                        held
                    }
            }
        }
        scope.launch {
            snackbar.showSnackbar(stringResource("rabbitmq_ui_msg_forward_done_count").withArg(forwarded.size))
        }
    }

    fun deleteOne(msg: RabbitMqFetchedMessage) {
        val s = session ?: return
        scope.launch {
            try {
                withTimeout(MESSAGE_TIMEOUT_MS) { s.ack(msg.deliveryTag) }
                messages = messages.filterNot { it.deliveryTag == msg.deliveryTag }
                held = withTimeout(MESSAGE_TIMEOUT_MS) { s.heldCount() }
                snackbar.showSnackbar(stringResource("rabbitmq_ui_msg_deleted"))
            } catch (e: TimeoutCancellationException) {
                snackbar.showSnackbar(timeoutText)
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

    suspend fun sendOneDirect(routingBytes: ByteArray, headers: Map<String, String>, s: RabbitMqQueueSession): Pair<Boolean, String?> {
        return try {
            withTimeout(MESSAGE_TIMEOUT_MS) { s.publishDirect(routingBytes, headers) }
            true to null
        } catch (e: TimeoutCancellationException) {
            // 超时不能当取消抛出：publishDirect 的 TimeoutCancellationException 也是
            // CancellationException，若漏给下面那个分支会把整批发送协程连带杀掉。
            false to timeoutText
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false to failureText(e)
        }
    }

    fun sendBatch() {
        scope.launch {
            sending = true
            sendError = null
            try {
                // 发送同样需要会话：延迟建连后不能再假定它已经在。
                val s = ensureSession() ?: return@launch
                val headers = sendHeaders.filter { it.first.isNotBlank() }.associate { it.first.trim() to it.second }
                var sent = 0
                for (task in batchTasks.filter { it.status == BatchStatus.WAITING }) {
                    updateBatchTask(task.id) { it.copy(status = BatchStatus.SENDING, errorMessage = null) }
                    val (ok, err) = sendOneDirect(task.content, headers, s)
                    updateBatchTask(task.id) {
                        if (ok) it.copy(status = BatchStatus.SUCCESS) else it.copy(status = BatchStatus.FAILED, errorMessage = err)
                    }
                    if (ok) sent++
                }
                if (sent > 0) {
                    // 有成功投递 → 自动重新拉取刷新列表（新消息在队列头部）。
                    withTimeout(MESSAGE_TIMEOUT_MS) { s.requeueAll() }
                    messages = emptyList()
                    exhausted = false
                    messages = withTimeout(MESSAGE_TIMEOUT_MS) { s.fetchNext(effectiveLimit(limit)) }
                    exhausted = messages.isEmpty()
                    held = withTimeout(MESSAGE_TIMEOUT_MS) { s.heldCount() }
                }
                val failed = batchTasks.count { it.status == BatchStatus.FAILED }
                if (failed == 0) snackbar.showSnackbar(stringResource("rabbitmq_ui_msg_batch_done"))
            } catch (e: TimeoutCancellationException) {
                sendError = timeoutText
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError = failureText(e)
            } finally {
                sending = false
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

    fun retryBatchTask(task: BatchTask) {
        scope.launch {
            val s = ensureSession() ?: return@launch
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
            SecondaryButton(
                onClick = onDismiss,
            ) { Text(stringResource("common_close")) }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (val ss = sessionState) {
                // Error：会话没开成。错误整页呈现 + 重试（重试 = 再走一次 ensureSession，
                // 由 fetchMore 触发）。
                is LoadState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource("rabbitmq_ui_msg_session_failed").withArg(ss.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButtonCompat { fetchMore() }
                    }
                }
                // Idle / Loading / Success 一律渲染外壳：
                //  - Idle = 还没开过会话（打开对话框的常态），用户要能看到 Tab 与
                //    「拉取消息」按钮，才有办法触发第一次建连；
                //  - Loading = 正在开，外壳不动，只把 busy 传给浏览区（按钮置灰 + 转圈）；
                //  - Success = 已开。
                else -> {
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
                                    // 建会话与拉取都算 busy：按钮都该置灰，转圈都该转。
                                    fetching = fetching || sessionState is LoadState.Loading,
                                    limit = limit,
                                    onLimitChange = { limit = it.filter(Char::isDigit).take(3) },
                                    exhausted = exhausted,
                                    held = held,
                                    onFetch = ::fetchMore,
                                    onRefresh = ::refreshQueue,
                                    onDetail = { detail = it },
                                    onDelete = ::deleteOne,
                                    onForward = { forwarding = listOf(it) },
                                    onForwardAll = { forwarding = messages.toList() },
                                )
                            } else {
                                SendPane(
                                    sendHeaders = sendHeaders,
                                    onSendHeaders = { sendHeaders = it },
                                    busy = sending,
                                    batchTasks = batchTasks,
                                    onPickFiles = ::loadSendFiles,
                                    onRemoveTask = { batchTasks = batchTasks.filterNot { t -> t.id == it } },
                                    onSendBatch = ::sendBatch,
                                    onRetryTask = ::retryBatchTask,
                                    sendError = sendError,
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

        if (forwarding.isNotEmpty()) {
            val s = session
            if (s != null) {
                RabbitMqForwardMessageDialog(
                    connectionId = connectionId,
                    sourceQueue = queueName,
                    targets = forwarding,
                    session = s,
                    onDismiss = { forwarding = emptyList() },
                    onForwarded = { forwarded, deleteSource ->
                        onForwarded(forwarded, deleteSource)
                    },
                )
            }
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
    onForward: (RabbitMqFetchedMessage) -> Unit,
    onForwardAll: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton(onClick = onFetch, enabled = !fetching) {
                Text(stringResource("rabbitmq_ui_msg_fetch"))
            }
            SecondaryButton(
                onClick = onRefresh,
                enabled = !fetching,
            ) { Text(stringResource("rabbitmq_ui_msg_refetch")) }
            SecondaryButton(
                onClick = onForwardAll,
                enabled = !fetching && messages.isNotEmpty(),
            ) { Text(stringResource("rabbitmq_ui_msg_forward_all")) }

            // 条数标签刻意做成 Row 的**兄弟节点**，而不是 CompactTextField 的 label：
            // 后者的 label 画在框体上方（labelSmall + 4dp 间距），整块会变成约 60dp，
            // 比 40dp 的按钮高一截 —— Row 虽然 CenterVertically，框体本身反而会低下去。
            // 放成兄弟节点，它只有一行高，不参与框体的高度。
            Text(
                text = stringResource("rabbitmq_ui_msg_fetch_limit"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 80dp：两侧各 12dp 内边距后还剩 56dp，够放三位数字（上限就是 3 位）。
            // 不传 modifier.height —— 组件自己是 heightIn(min = 40.dp)，与 M3 Button 同高，
            // 这也正是原来 height(52.dp) 该去掉的原因（52 既不是 56 也不是 40）。
            // 数字限制仍是双保险：KeyboardOptions 在桌面端没有软键盘可作用，真正的
            // 关卡在调用方的 filter(Char::isDigit).take(3) 与 effectiveLimit 的 coerceIn。
            CompactTextField(
                value = limit,
                onValueChange = onLimitChange,
                enabled = !fetching,
                modifier = Modifier.width(80.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
            Text(stringResource("rabbitmq_ui_msg_col_actions"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(116.dp))
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
                    Row(modifier = Modifier.width(116.dp)) {
                        IconButton(onClick = { onDetail(msg) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Info, contentDescription = stringResource("rabbitmq_ui_msg_action_detail"), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onForward(msg) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = stringResource("rabbitmq_ui_msg_action_forward"),
                                modifier = Modifier.size(18.dp),
                            )
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

/**
 * 发送页**纯文件驱动**（对齐 IBM MessageSendTab）：Headers + 文件选择区（FileKit
 * 多选，唯一消息来源，无正文输入框）+ 文件表格；底部单一"发送文件"按钮 —— 有
 * WAITING 项即可点，按列表逐个直发（单文件即单发）；有成功投递自动刷新列表。
 */
@Composable
private fun SendPane(
    sendHeaders: List<Pair<String, String>>,
    onSendHeaders: (List<Pair<String, String>>) -> Unit,
    busy: Boolean,
    batchTasks: List<BatchTask>,
    onPickFiles: () -> Unit,
    onRemoveTask: (String) -> Unit,
    onSendBatch: () -> Unit,
    onRetryTask: (BatchTask) -> Unit,
    sendError: String?,
) {
    val hasBatch = batchTasks.isNotEmpty()
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            HeadersEditor(headers = sendHeaders, enabled = !busy, onChange = onSendHeaders)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            // ---- 文件区：选择文件 / 数量 / 提示 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton(onClick = onPickFiles, enabled = !busy) { Text(stringResource("rabbitmq_ui_send_pick_file")) }
                if (hasBatch) {
                    Text(
                        text = stringResource("rabbitmq_ui_send_files_selected").withArg(batchTasks.size),
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
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            if (!hasBatch) {
                ObjectListEmptyState(message = stringResource("rabbitmq_ui_send_empty"))
            }
            batchTasks.forEach { task ->
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
                                SecondaryButton(
                                    onClick = { onRemoveTask(task.id) },
                                    standardMinWidth = false,
                                ) { Text(stringResource("rabbitmq_ui_send_remove")) }
                            task.status == BatchStatus.FAILED && !busy ->
                                SecondaryButton(
                                    onClick = { onRetryTask(task) },
                                    standardMinWidth = false,
                                ) { Text(stringResource("rabbitmq_ui_send_retry")) }
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
            if (sendError != null) {
                Spacer(Modifier.height(4.dp))
                Text(sendError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        HorizontalDivider()
        // ---- 底部条：单一"发送文件"按钮，只看待发送文件 ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                onClick = onSendBatch,
                enabled = !busy && batchTasks.any { it.status == BatchStatus.WAITING },
            ) {
                Text(
                    text = if (busy) stringResource("rabbitmq_ui_send_sending")
                    else stringResource("rabbitmq_ui_send_send_files"),
                )
            }
        }
    }
}
/** 「重试」文本按钮（会话失败态）。 */
@Composable
private fun TextButtonCompat(onClick: () -> Unit) {
    SecondaryButton(onClick = onClick) {
        Text(stringResource("rabbitmq_ui_refresh"))
    }
}

/**
 * 会话槽：**唯一**持有当前队列消息会话的容器，开新的必然先关旧的。
 *
 * 把「先关旧、再开新」做成容器语义，而不是调用方的约定：对话框里任何一处想开会话
 * 都得经 [open]，没有第二条路 —— 漏关旧会话从「靠代码审阅发现」变成「结构上做不到」。
 *
 * 之前这里只是 `mutableStateOf<Session?>`：`session` 不按 queueName 取 key，同一对话框
 * 实例换队列时它仍指向旧连接，直接覆盖就会漏掉那条 AMQP `Connection` + `Channel`
 * （旧 channel 上的未 ack 消息也一直不还给队列，要等对话框销毁）。
 */
internal class QueueSessionSlot(private val opener: RabbitMqSessionOpener) {

    private val lock = Any()
    private var current: RabbitMqQueueSession? = null

    /**
     * 槽是否已跟对话框一起作废。
     *
     * 堵的是这个竞态：`open()` 在 `opener.open(...)` **返回之后**才把新会话放进槽，
     * 若这期间对话框已销毁、`detach()` 早已返回过 null，那个新会话就**没有任何人
     * 认领** —— 直到应用退出才被 `shutdown()` 收走。每「建连中→关闭」一次漏一条
     * Connection。有了这个标记，迟到的 open 会把刚建好的会话当场关掉。
     */
    private var disposed = false

    /** 关掉旧会话（如果有）再开新的。返回新会话。 */
    suspend fun open(connectionId: String, queueName: String): RabbitMqQueueSession {
        // 先把旧会话从槽里摘走再关：关闭是挂起调用，不能占着监视器。
        val previous = synchronized(lock) { current.also { current = null } }
        // 关闭失败不拦重开：旧连接已不可用时，能开新的比卡在旧的上更有用。
        //（close 自身已对未 ack 做了 requeue；失败时 broker 也会在连接断开后重入队。）
        previous?.let { runCatching { it.close() } }

        val opened = opener.open(connectionId, queueName)
        val adopted =
            synchronized(lock) {
                if (disposed) {
                    false
                } else {
                    current = opened
                    true
                }
            }
        if (!adopted) {
            // 槽已销毁：刚建好的连接绝不能留在外面，否则就是一条泄漏。
            runCatching { opened.close() }
            throw IllegalStateException("消息会话已随对话框销毁，新建的连接已关闭")
        }
        return opened
    }

    /**
     * 取走当前会话（**换队列**时用：槽仍然可用，只是交出了旧句柄）。
     *
     * 取走而不是读取：槽不再持有它，避免关闭与重开同时对同一个句柄动手。
     */
    fun detach(): RabbitMqQueueSession? = synchronized(lock) { current.also { current = null } }

    /**
     * 对话框销毁：永久关闭此槽并交出当前会话。
     *
     * 与 [detach] 的区别就是那个永久性：之后再 [open] 会把新会话立刻关掉并抛错，
     * 而不是默默留下一份无人认领的连接。
     */
    fun dispose(): RabbitMqQueueSession? =
        synchronized(lock) {
            disposed = true
            current.also { current = null }
        }
}

