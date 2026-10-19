package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.messaging.DmpmqmsgFormat
import com.mqclient.ibmmq.messaging.DmpmqmsgMessage
import com.mqclient.ibmmq.messaging.MessageDetail
import com.mqclient.ibmmq.messaging.MessageOperationResult
import com.mqclient.ibmmq.messaging.MessageSummary
import com.mqclient.ibmmq.messaging.sendExpiryTenths
import com.mqclient.ibmmq.messaging.sendPersistence
import com.mqclient.ibmmq.messaging.sendPriority
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.MessageDetailDialog
import com.mqclient.uicommon.dialog.MessageDetailErrorView
import com.mqclient.uicommon.dialog.MessageDetailLoadingView
import com.mqclient.uicommon.dialog.MessageHeaderRow
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.file.pickFile
import com.mqclient.uicommon.file.saveFileBytes
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 详情对话框的三态：加载中 / 失败（带原因，可重试）/ 就绪。 */
private sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Failed(val reason: String) : DetailUiState
    data class Ready(val detail: MessageDetail) : DetailUiState
}

/**
 * 消息详情对话框（浏览消息表格「详细」按钮打开）：三态外壳 —— 就绪态交给公共
 * [MessageDetailDialog]（正文/Header 双 Tab + 解释器区，ui-common 统一渲染），
 * 加载 / 失败态用普通 [ObjectDetailDialog] + 公共 Loading / Error 视图。IBM 侧只
 * 保留数据获取与 dmpmqmsg 导入/导出逻辑；Header 插槽渲染 MQMD 字段列表，底部
 * 额外操作为「下载 / 导出 dmpmqmsg / 导入 dmpmqmsg」。删除消息不走详情对话框，
 * 入口在消息列表的行操作。
 *
 * 数据经 service 的 getMessageDetail 按 MessageId 现查（独立 browse 句柄，纯浏览
 * 不消费）。
 */
@Composable
internal fun MessageDetailDialog(
    connectionId: String,
    queueName: String,
    summary: MessageSummary,
    onDismiss: () -> Unit,
    onImported: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var uiState by remember(summary.messageId) { mutableStateOf<DetailUiState>(DetailUiState.Loading) }
    var retryKey by remember(summary.messageId) { mutableStateOf(0) }

    LaunchedEffect(summary.messageId, retryKey) {
        uiState = DetailUiState.Loading
        val result = IbmMQModule.serviceOrNull()
            ?.getMessageDetail(connectionId, queueName, summary.messageId)
            ?: MessageOperationResult.failure(null, "服务未初始化")
        val detail = result.detail
        uiState = when {
            result.success && detail != null -> DetailUiState.Ready(detail)
            else -> DetailUiState.Failed(result.errorMessage ?: "unknown")
        }
    }

    /** 下载：消息体已在详情里（Ready 时才可见），直接写盘，不再回查队列。 */
    fun downloadDetail(detail: MessageDetail) {
        scope.launch {
            try {
                val saved = saveFileBytes("msg_${detail.messageId.take(8)}.bin", "bin", detail.content)
                if (saved != null) {
                    snackbarHostState.showSnackbar(stringResource("ibm_mq_msg_browse_saved").withArg(saved))
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(stringResource("ibm_mq_msg_save_failed").withArg(e.message ?: ""))
            }
        }
    }

    /** 导出 dmpmqmsg：IO/Default 线程生成文本，FileKit 另存为。 */
    fun exportDmpmqmsg(detail: MessageDetail) {
        scope.launch {
            try {
                val text = withContext(Dispatchers.Default) {
                    val qmgr = IbmMQModule.serviceOrNull()?.cachedQueueManager(connectionId)
                    DmpmqmsgFormat.export(
                        detail = detail,
                        queueName = queueName,
                        qmgrName = qmgr?.name,
                        qmgrVersion = qmgr?.attributes?.get("MQCA_VERSION")?.toString(),
                    )
                }
                val saved = saveFileBytes(
                    "msg_${detail.messageId.take(8)}_dmpmqmsg.txt",
                    "txt",
                    text.toByteArray(Charsets.UTF_8),
                )
                if (saved != null) {
                    snackbarHostState.showSnackbar(stringResource("ibm_mq_msg_export_success").withArg(saved))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(stringResource("ibm_mq_msg_save_failed").withArg(e.message ?: ""))
            }
        }
    }

    // ---- 导入 dmpmqmsg：选文件 → 解析 → 预览确认 / fallback → 发送 ----
    var importPreview by remember { mutableStateOf<Pair<String, List<DmpmqmsgMessage>>?>(null) }
    var importFallback by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var selectedImportIndex by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    fun startImport() {
        scope.launch {
            val path = pickFile(stringResource("ibm_mq_msg_import_pick_title"), emptyList()) ?: return@launch
            val bytes = try {
                withContext(Dispatchers.IO) { java.io.File(path).readBytes() }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(stringResource("ibm_mq_msg_import_read_failed").withArg(e.message ?: ""))
                return@launch
            }
            val parsed = withContext(Dispatchers.Default) { DmpmqmsgFormat.parse(String(bytes, Charsets.UTF_8)) }
            importError = null
            if (parsed.messages.isNotEmpty()) {
                selectedImportIndex = 0
                importPreview = java.io.File(path).name to parsed.messages
            } else {
                // fallback：不是 dmpmqmsg 格式，可取消或按普通文件发送
                importFallback = java.io.File(path).name to bytes
            }
        }
    }

    /** 执行导入：[message] 为 null（fallback 路径）时全部走 MQ 默认属性。 */
    fun performImport(message: DmpmqmsgMessage) {
        scope.launch {
            importing = true
            importError = null
            try {
                val service = IbmMQModule.serviceOrNull() ?: error(stringResource("ibm_mq_msg_service_not_ready"))
                val result = service.sendMessage(
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
                if (result.success) {
                    importPreview = null
                    importFallback = null
                    snackbarHostState.showSnackbar(
                        stringResource("ibm_mq_msg_import_success").withArg(result.messageId ?: ""),
                    )
                    onImported()
                } else {
                    importError = result.errorMessage ?: "MQRC ${result.reasonCode ?: "?"}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                importError = e.message ?: e::class.simpleName ?: "unknown"
            } finally {
                importing = false
            }
        }
    }

    // 本宿主自己的 snackbar 反馈（下载/导入等）+ dmpmqmsg 导入预览 / fallback 对话框。
    // 声明在当前 ObjectDetailDialog 的 content 内（各分支的 overlay lambda）——compose
    // Dialog 的窗口归属随声明组合走，挂到外层会被本对话框压住（见 ObjectDetailDialog KDoc）。
    val hostOverlay: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize()) {
            ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
        importPreview?.let { (fileName, messages) ->
            DmpmqmsgImportDialog(
                fileName = fileName,
                messages = messages,
                selectedIndex = selectedImportIndex,
                onSelectMessage = { selectedImportIndex = it },
                executing = importing,
                errorText = importError,
                onImport = { performImport(messages[selectedImportIndex.coerceIn(0, messages.lastIndex)]) },
                onDismiss = { if (!importing) importPreview = null },
            )
        }
        importFallback?.let { (fileName, body) ->
            DmpmqmsgFallbackDialog(
                fileName = fileName,
                executing = importing,
                errorText = importError,
                onSendPlain = { performImport(DmpmqmsgMessage(attrs = emptyMap(), body = body)) },
                onDismiss = { if (!importing) importFallback = null },
            )
        }
    }

    when (val state = uiState) {
        is DetailUiState.Loading -> ObjectDetailDialog(
            title = stringResource("ibm_mq_msg_detail_title").withArg(summary.messageId),
            onDismiss = onDismiss,
            minWidth = 720.dp,
            minHeight = 480.dp,
        ) {
            MessageDetailLoadingView(
                text = stringResource("ibm_mq_msg_detail_loading"),
                modifier = Modifier.fillMaxSize(),
            )
            hostOverlay()
        }
        is DetailUiState.Failed -> ObjectDetailDialog(
            title = stringResource("ibm_mq_msg_detail_title").withArg(summary.messageId),
            onDismiss = onDismiss,
            minWidth = 720.dp,
            minHeight = 480.dp,
        ) {
            MessageDetailErrorView(
                reason = state.reason,
                onRetry = { retryKey++ },
                modifier = Modifier.fillMaxSize(),
            )
            hostOverlay()
        }
        is DetailUiState.Ready -> {
            val detail = state.detail
            MessageDetailDialog(
                title = stringResource("ibm_mq_msg_detail_title").withArg(summary.messageId),
                rawBody = detail.content,
                onDismiss = onDismiss,
                subtitleText = summary.messageId,
                encoding = detail.encoding,
                codedCharSetId = detail.codedCharSetId,
                headerContent = {
                    Column {
                        for ((key, value) in detail.headers) {
                            MessageHeaderRow(key = stringResource(key), value = value)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                },
                extraBottomActions = {
                    FooterAction(stringResource("ibm_mq_msg_detail_download")) {
                        downloadDetail(detail)
                    }
                    FooterAction(stringResource("ibm_mq_msg_detail_export_dmpmqmsg")) {
                        exportDmpmqmsg(detail)
                    }
                    FooterAction(stringResource("ibm_mq_msg_detail_import_dmpmqmsg")) { startImport() }
                },
                minWidth = 720.dp,
                minHeight = 480.dp,
                nestedContent = hostOverlay,
            )
        }
    }
}

/** 底部操作栏的文本按钮（extraBottomActions 用），尺寸走 PressableButton 的全项目约定。 */
@Composable
private fun RowScope.FooterAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    PressableButton(
        onClick = onClick,
        enabled = enabled,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) { Text(label) }
}
