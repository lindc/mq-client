package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.messaging.DmpmqmsgFormat
import com.mqclient.ibmmq.messaging.DmpmqmsgMessage
import com.mqclient.ibmmq.messaging.SendResult
import com.mqclient.ibmmq.messaging.sendExpiryTenths
import com.mqclient.ibmmq.messaging.sendPersistence
import com.mqclient.ibmmq.messaging.sendPriority
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.file.pickFile
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * dmpmqmsg **导入**是队列级动作，不是单条消息的属性 —— 详情对话框只看一条已存在的消息，
 * 在它的底栏放「导入」在业务上讲不通（导入的是**一批**待发出的消息，与当前这条无关）。
 * 所以整套导入从 `MessageDetailDialog` 搬到这里，宿主是队列消息处理对话框
 * （[QueueMessagesDialog]）。
 *
 * 状态挂在**对话框层**而不是 Tab 里：切到「发送消息」再切回来不会打断进行中的导入，
 * 预览框也不会跟着浏览 Tab 的组合一起消失。
 */

/** 一次批量导入的汇总。 */
internal data class DmpmqmsgImportOutcome(
    val sent: Int,
    val failed: Int,
    /** 第一条失败原因（全部成功时为 null）。 */
    val firstError: String?,
) {
    val total: Int get() = sent + failed
}

/**
 * 逐条发送并汇总结果。
 *
 * 抽成纯函数（sender 是参数而不是直接摸 service）是为了能在无 broker 的测试里钉住
 * 计数、进度回调与取消语义 —— 见 `DmpmqmsgImportBatchTest`。
 *
 * 取消原样抛出：中途关对话框 / 取消导入时，调用方的 `finally` 负责复位 `importing`。
 */
internal suspend fun importDmpmqmsgMessages(
    messages: List<DmpmqmsgMessage>,
    onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    send: suspend (DmpmqmsgMessage) -> SendResult,
): DmpmqmsgImportOutcome {
    var sent = 0
    var failed = 0
    var firstError: String? = null
    messages.forEachIndexed { index, message ->
        // 每条之间是最安全的取消点（MQI 的 put 本身不感知取消）。
        currentCoroutineContext().ensureActive()
        val result = send(message)
        if (result.success) {
            sent++
        } else {
            failed++
            if (firstError == null) {
                firstError = result.errorMessage ?: "MQRC ${result.reasonCode ?: "?"}"
            }
        }
        onProgress(index + 1, messages.size)
    }
    return DmpmqmsgImportOutcome(sent = sent, failed = failed, firstError = firstError)
}

/** 解析成功、等待确认的导入批次。 */
internal data class DmpmqmsgImportPreview(
    val fileName: String,
    val messages: List<DmpmqmsgMessage>,
)

/** 不是 dmpmqmsg 格式：可取消，或把文件内容当一条消息体发送。 */
internal data class DmpmqmsgImportFallback(
    val fileName: String,
    val body: ByteArray,
)

/**
 * 导入状态机：选文件 → 解析 → 预览确认（或 fallback）→ 批量发送 → 汇总反馈。
 *
 * [onImported] 在**有消息真的发出去之后**调用（含部分成功），宿主据此刷新浏览列表。
 */
@Stable
internal class DmpmqmsgImportState(
    private val connectionId: String,
    val queueName: String,
    private val scope: CoroutineScope,
    private val onImported: () -> Unit,
) {
    var preview by mutableStateOf<DmpmqmsgImportPreview?>(null)
        private set

    var fallback by mutableStateOf<DmpmqmsgImportFallback?>(null)
        private set

    /** 预览里当前选中的消息下标（只影响预览，导入的是整批）。 */
    var previewIndex by mutableIntStateOf(0)
        private set

    var importing by mutableStateOf(false)
        private set

    /** 批量导入进度（已处理 / 总数）。 */
    var processed by mutableIntStateOf(0)
        private set

    var total by mutableIntStateOf(0)
        private set

    var errorText by mutableStateOf<String?>(null)
        private set

    /** 导入结果的提示（成功条数 / 部分失败）。挂在对话框窗口内，见 [DmpmqmsgImportHost]。 */
    val snackbarHostState = SnackbarHostState()

    /** 导入进行中（宿主用它拦关闭、禁用重复点击）。 */
    val busy: Boolean get() = importing

    fun selectPreview(index: Int) {
        previewIndex = index
    }

    fun dismissPreview() {
        if (!importing) preview = null
    }

    fun dismissFallback() {
        if (!importing) fallback = null
    }

    /** 选文件并解析：解析出消息走预览，解析不出走 fallback（与迁移前一致）。 */
    fun pickFile() {
        if (importing) return
        scope.launch {
            val path = pickFile(
                stringResource("ibm_mq_msg_import_pick_title"),
                listOf("txt", "json"),
            ) ?: return@launch
            val bytes = try {
                withContext(Dispatchers.IO) { java.io.File(path).readBytes() }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    stringResource("ibm_mq_msg_import_read_failed").withArg(e.message ?: ""),
                )
                return@launch
            }
            val parsed = withContext(Dispatchers.Default) {
                DmpmqmsgFormat.parse(String(bytes, Charsets.UTF_8))
            }
            errorText = null
            processed = 0
            val fileName = java.io.File(path).name
            if (parsed.messages.isNotEmpty()) {
                previewIndex = 0
                total = parsed.messages.size
                preview = DmpmqmsgImportPreview(fileName, parsed.messages)
            } else {
                fallback = DmpmqmsgImportFallback(fileName, bytes)
            }
        }
    }

    /** 确认导入：把解析出的**整批**消息逐条发到当前队列。 */
    fun importAll() {
        val batch = preview ?: return
        if (importing) return
        runImport(batch.messages) { message ->
            send(message)
        }
    }

    /** fallback 路径：整份文件当一个消息体发（全部走 MQ 默认属性）。 */
    fun importFallbackFile() {
        val file = fallback ?: return
        if (importing) return
        runImport(listOf(DmpmqmsgMessage(attrs = emptyMap(), body = file.body))) { message ->
            send(message)
        }
    }

    private fun runImport(
        messages: List<DmpmqmsgMessage>,
        send: suspend (DmpmqmsgMessage) -> SendResult,
    ) {
        scope.launch {
            importing = true
            errorText = null
            processed = 0
            total = messages.size
            try {
                val outcome = importDmpmqmsgMessages(
                    messages = messages,
                    onProgress = { done, all ->
                        processed = done
                        total = all
                    },
                    send = send,
                )
                if (outcome.failed == 0) {
                    preview = null
                    fallback = null
                    snackbarHostState.showSnackbar(
                        stringResource("ibm_mq_msg_import_result").withArg(outcome.sent),
                    )
                } else {
                    // 部分成功也刷新：已经进去的消息在列表里看不到（新 MessageId）。
                    errorText =
                        if (outcome.sent == 0) {
                            stringResource("ibm_mq_msg_import_failed").withArg(outcome.firstError ?: "")
                        } else {
                            stringResource("ibm_mq_msg_import_result_partial")
                                .withArg(outcome.sent, outcome.failed)
                        }
                }
                if (outcome.sent > 0) onImported()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorText = e.message ?: e::class.simpleName ?: "unknown"
            } finally {
                importing = false
            }
        }
    }

    /** 单条发送（属性按 dmpmqmsg 记录还原；MessageId 由 MQ 重新生成，不还原）。 */
    private suspend fun send(message: DmpmqmsgMessage): SendResult {
        val service = IbmMQModule.serviceOrNull()
            ?: return SendResult.failure(null, stringResource("ibm_mq_msg_service_not_ready"))
        return service.sendMessage(
            connectionId = connectionId,
            queueName = queueName,
            content = message.body,
            replyToQueue = message.replyToQueue.takeIf { it.isNotBlank() },
            persistence = message.sendPersistence(),
            priority = message.sendPriority(),
            expiryTenths = message.sendExpiryTenths(),
            correlationIdBytes = message.correlIdBytes,
            characterSet = message.ccsid,
            format = message.format.takeIf { it.isNotBlank() },
            replyToQMgrName = message.replyToQMgr.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * 建一份绑定到当前宿主的导入状态。
 *
 * 必须由**持有对话框窗口的那一层**调用（[QueueMessagesDialog]），不要在 Tab 内部建：
 * 切 Tab 会销毁 Tab 的组合，进行中的导入会被一起取消。
 */
@Composable
internal fun rememberDmpmqmsgImportState(
    connectionId: String,
    queueName: String,
    onImported: () -> Unit,
): DmpmqmsgImportState {
    val scope = rememberCoroutineScope()
    // 用 rememberUpdatedState 而不是直接把 lambda 塞进构造函数：宿主每次重组都会给出新的
    // lambda 实例，直接捕获会让导入一直回调**第一次组合**的那个（典型 stale capture）。
    val latestOnImported by rememberUpdatedState(onImported)
    return remember(connectionId, queueName) {
        DmpmqmsgImportState(connectionId, queueName, scope) { latestOnImported() }
    }
}

/**
 * 导入的浮层：结果提示 + 预览 / fallback 对话框。
 *
 * 必须声明在宿主对话框的 content 内 —— compose Dialog 的窗口归属随声明组合走，挂到外层
 * 会被宿主窗口压住（见 `ObjectDetailDialog` 的 KDoc）。
 */
@Composable
internal fun DmpmqmsgImportHost(
    state: DmpmqmsgImportState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        ThemedSnackbarHost(state.snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
    state.preview?.let { batch ->
        DmpmqmsgImportDialog(
            fileName = batch.fileName,
            queueName = state.queueName,
            messages = batch.messages,
            selectedIndex = state.previewIndex,
            onSelectMessage = state::selectPreview,
            executing = state.importing,
            processed = state.processed,
            total = state.total,
            errorText = state.errorText,
            onImport = state::importAll,
            onDismiss = state::dismissPreview,
        )
    }
    state.fallback?.let { file ->
        DmpmqmsgFallbackDialog(
            fileName = file.fileName,
            queueName = state.queueName,
            executing = state.importing,
            errorText = state.errorText,
            onSendPlain = state::importFallbackFile,
            onDismiss = state::dismissFallback,
        )
    }
}
