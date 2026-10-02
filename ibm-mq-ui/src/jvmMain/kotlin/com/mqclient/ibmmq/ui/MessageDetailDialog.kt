@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mqclient.ibmmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.mqclient.message.interpreter.MessageInterpreters
import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.ParamKind
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 正文预览长度上限（字符）：超出只显示前段，底部「展开全部」。 */
private const val MAX_PREVIEW_CHARS = 20_000

/** 详情对话框的三态：加载中 / 失败（带原因，可重试）/ 就绪。 */
private sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Failed(val reason: String) : DetailUiState
    data class Ready(val detail: MessageDetail) : DetailUiState
}

/** 解释器渲染结果：展示文本 + 解析失败原因（失败时展示原始内容 + 提示）。 */
private data class RenderedText(val text: String, val parseError: String? = null)

/**
 * 消息详情对话框（浏览消息表格「详细」按钮打开）：左右分栏 3:2 —— 左侧正文 /
 * Header 两个 Tab，右侧解释器选择 + 参数 + 辅助操作；底部「下载 | 关闭」。
 * 删除消息不走详情对话框，入口在消息列表的行操作。
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
    val clipboard = LocalClipboardManager.current

    var uiState by remember(summary.messageId) { mutableStateOf<DetailUiState>(DetailUiState.Loading) }
    var retryKey by remember(summary.messageId) { mutableStateOf(0) }
    var selectedTab by remember { mutableStateOf(0) }

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

    fun showCopied() {
        scope.launch { snackbarHostState.showSnackbar(stringResource("msg_copied")) }
    }

    /** 下载：消息体已在详情里（Ready 时才可见），直接写盘，不再回查队列。 */
    fun downloadDetail(detail: MessageDetail) {
        scope.launch {
            try {
                val saved = saveFileBytes("msg_${detail.messageId.take(8)}.bin", "bin", detail.content)
                if (saved != null) {
                    snackbarHostState.showSnackbar(stringResource("msg_browse_saved").withArg(saved))
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(stringResource("msg_save_failed").withArg(e.message ?: ""))
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
                    snackbarHostState.showSnackbar(stringResource("msg_export_success").withArg(saved))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(stringResource("msg_save_failed").withArg(e.message ?: ""))
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
            val path = pickFile(stringResource("msg_import_pick_title"), emptyList()) ?: return@launch
            val bytes = try {
                withContext(Dispatchers.IO) { java.io.File(path).readBytes() }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(stringResource("msg_import_read_failed").withArg(e.message ?: ""))
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
                val service = IbmMQModule.serviceOrNull() ?: error(stringResource("msg_service_not_ready"))
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
                        stringResource("msg_import_success").withArg(result.messageId ?: ""),
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

    ObjectDetailDialog(
        title = stringResource("msg_detail_title").withArg(summary.messageId),
        onDismiss = onDismiss,
        minWidth = 720.dp,
        minHeight = 480.dp,
        footerActions = {
            val ready = uiState as? DetailUiState.Ready
            FooterAction(stringResource("msg_detail_download"), enabled = ready != null) {
                downloadDetail(ready!!.detail)
            }
            FooterAction(stringResource("msg_detail_export_dmpmqmsg"), enabled = ready != null) {
                exportDmpmqmsg(ready!!.detail)
            }
            FooterAction(stringResource("msg_detail_import_dmpmqmsg")) { startImport() }
            Spacer(Modifier.weight(1f))
            FooterAction(stringResource("common_close")) { onDismiss() }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 副标题：完整 MessageId（等宽）+ 复制 ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = summary.messageId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(summary.messageId))
                        showCopied()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource("msg_detail_copy_id"),
                            modifier = Modifier.height(16.dp),
                        )
                    }
                }
                HorizontalDivider()

                when (val state = uiState) {
                    is DetailUiState.Loading -> DetailLoadingView(Modifier.weight(1f))
                    is DetailUiState.Failed -> DetailErrorView(
                        reason = state.reason,
                        onRetry = { retryKey++ },
                        modifier = Modifier.weight(1f),
                    )
                    is DetailUiState.Ready -> DetailReadyPane(
                        detail = state.detail,
                        selectedTab = selectedTab,
                        onTabChange = { selectedTab = it },
                        onCopy = ::showCopied,
                        clipboard = clipboard,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            ThemedSnackbarHost(
                snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // dmpmqmsg 导入：预览确认 / fallback（文件不是 dmpmqmsg 格式）
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

/** 底部操作栏的文本按钮（footerActions 用），尺寸走 PressableButton 的全项目约定。 */
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

/** 就绪态主体：左侧 3/5（正文 / Header Tab）+ 右侧 2/5（解释器区），中间细分隔线。 */
@Composable
private fun DetailReadyPane(
    detail: MessageDetail,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onCopy: () -> Unit,
    clipboard: ClipboardManager,
    modifier: Modifier = Modifier,
) {
    // 解释器选择与参数（切换解释器时参数回默认值）。
    val interpreters = remember { MessageInterpreters.loadAll() }
    var selectedId by remember { mutableStateOf(MessageInterpreters.byId("text").id) }
    val interpreter = remember(selectedId, interpreters) {
        interpreters.firstOrNull { it.id == selectedId } ?: MessageInterpreters.byId(selectedId)
    }
    var params by remember(interpreter) { mutableStateOf(interpreter.defaultParams) }
    var expanded by remember(detail) { mutableStateOf(false) }

    Row(modifier = modifier) {
        // ---- 左 3/5：正文 / Header ----
        Column(modifier = Modifier.weight(3f).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { onTabChange(0) },
                    text = { Text(stringResource("msg_detail_tab_content")) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { onTabChange(1) },
                    text = { Text(stringResource("msg_detail_tab_headers")) },
                )
            }
            when (selectedTab) {
                0 -> ContentTab(
                    detail = detail,
                    interpreter = interpreter,
                    params = params,
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> HeaderTab(detail = detail, modifier = Modifier.fillMaxSize())
            }
        }

        // ---- 中间细分隔线 ----
        VerticalDividerThin()

        // ---- 右 2/5：解释器选择（上）+ 参数与辅助操作（下）----
        InterpreterPane(
            detail = detail,
            interpreters = interpreters,
            selectedId = selectedId,
            onSelect = { selectedId = it },
            interpreter = interpreter,
            params = params,
            onParamsChange = { params = it },
            expanded = expanded,
            onExpandedChange = { expanded = it },
            onCopy = onCopy,
            clipboard = clipboard,
            modifier = Modifier.weight(2f).fillMaxSize(),
        )
    }
}

/** Tab 1：消息正文（解释器渲染 + 预览截断 + 解析失败回落原始文本 + 二进制 hex 预览）。 */
@Composable
private fun ContentTab(
    detail: MessageDetail,
    interpreter: MessageInterpreter,
    params: Map<String, String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (detail.dataLength == 0 || detail.content.isEmpty()) {
        // 「消息体为空」只看字节数，与解析失败是两个状态。
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("msg_detail_empty_body"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // 预览基线（Default 线程算，避免大消息卡组合）：二进制判定 + 原始文本 / hex。
    var preview by remember(detail) { mutableStateOf<ContentPreview?>(null) }
    // 解释器渲染结果只按 detail 记忆：切换解释器 / 参数时保留旧内容继续显示
    // （不清空、不丢滚动位置），新结果算完替换；仅首帧为 null 显示转圈。
    var rendered by remember(detail) { mutableStateOf<RenderedText?>(null) }
    var rendering by remember(detail) { mutableStateOf(false) }

    LaunchedEffect(detail) {
        preview = withContext(Dispatchers.Default) {
            val binary = isProbablyBinary(detail.content, detail.codedCharSetId)
            ContentPreview(
                isBinary = binary,
                rawText = if (binary) "" else MessageCharsets.decode(detail.content, detail.codedCharSetId),
                hex = if (binary) hexPreview(detail.content) else "",
            )
        }
    }
    LaunchedEffect(detail, interpreter, params, preview) {
        val base = preview ?: return@LaunchedEffect
        if (base.isBinary) return@LaunchedEffect // 二进制不走解释器（对字节流无意义）
        rendering = true
        rendered = withContext(Dispatchers.Default) {
            when (val result = interpreter.interpret(detail.content, detail.encoding, detail.codedCharSetId, params)) {
                is InterpreterResult.Success -> RenderedText(result.formatted)
                is InterpreterResult.Failure -> RenderedText(base.rawText, result.message)
            }
        }
        rendering = false
    }

    Box(modifier = modifier) {
        val scroll = rememberScrollState()
        val base = preview
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
            when {
                // 首帧：基线还没算完。
                base == null -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                )
                // 二进制：提示行 + hex 预览（不渲染乱码）。
                base.isBinary -> {
                    Text(
                        text = stringResource("msg_detail_binary_notice"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = base.hex,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                else -> {
                    val renderedValue = rendered
                    if (renderedValue == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                        )
                    } else {
                        // 解析失败：保留原始内容 + 错误提示条。
                        renderedValue.parseError?.let { err ->
                            Text(
                                text = stringResource("msg_detail_parse_failed").withArg(err),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                                    .padding(8.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        // 大内容预览截断：展开/折叠与右侧辅助操作联动（同一 expanded 状态）。
                        val display = if (expanded || renderedValue.text.length <= MAX_PREVIEW_CHARS) {
                            renderedValue.text
                        } else {
                            renderedValue.text.take(MAX_PREVIEW_CHARS) + "\n…"
                        }
                        Text(
                            text = display,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (renderedValue.text.length > MAX_PREVIEW_CHARS) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (expanded) stringResource("msg_detail_collapse") else stringResource("msg_detail_expand"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable { onExpandedChange(!expanded) }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        // 重渲染中（切换解释器 / 参数）的小指示器：不遮内容、不清空。
        if (rendering && base != null && !base.isBinary && rendered != null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .height(16.dp)
                    .width(16.dp),
            )
        }
    }
}

/** 正文基线：二进制判定结果 + 原始文本（非二进制）或 hex 预览（二进制）。 */
private data class ContentPreview(
    val isBinary: Boolean,
    val rawText: String,
    val hex: String,
)

/**
 * 二进制内容判定：前 8KB 内含 NUL 字节，或按 CCSID 解码后替换符（�）占比 > 5%
 * —— 任一命中即判为二进制，正文显示 hex 预览而非解码乱码。
 */
private fun isProbablyBinary(content: ByteArray, codedCharSetId: Int): Boolean {
    val scanEnd = minOf(content.size, 8192)
    for (i in 0 until scanEnd) {
        if (content[i] == 0.toByte()) return true
    }
    if (content.isEmpty()) return false
    val text = MessageCharsets.decode(content, codedCharSetId)
    if (text.isEmpty()) return false
    val replacements = text.count { it == '�' }
    return replacements.toFloat() / text.length > 0.05f
}

/**
 * 十六进制预览：`偏移  hex×16  |ASCII|` 行格式（不可见字节显示 ·），只取前
 * [maxBytes] 字节，超出以 … 收尾。二进制内容的兜底展示，用户至少能看到
 * 字节层面的事实（魔数、结构）。
 */
private fun hexPreview(content: ByteArray, maxBytes: Int = 1024): String {
    val end = minOf(content.size, maxBytes)
    val sb = StringBuilder()
    for (offset in 0 until end step 16) {
        val rowEnd = minOf(offset + 16, end)
        sb.append("%08X  ".format(offset))
        for (i in offset until rowEnd) {
            sb.append("%02X ".format(content[i]))
        }
        repeat(16 - (rowEnd - offset)) { sb.append("   ") }
        sb.append(" |")
        for (i in offset until rowEnd) {
            val b = content[i].toInt() and 0xFF
            sb.append(if (b in 0x20..0x7E) b.toChar() else '·')
        }
        sb.append('|').append('\n')
    }
    if (content.size > end) sb.append("…")
    return sb.toString()
}

/** Tab 2：消息 Header（两列 Key / Value；每行 Value 右侧复制按钮）。 */
@Composable
private fun HeaderTab(detail: MessageDetail, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        for ((key, value) in detail.headers) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(key),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(140.dp),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(value)) },
                    modifier = Modifier.height(20.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource("msg_detail_copy_value"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(14.dp),
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.fillMaxWidth(), thickness = 0.5.dp)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 右侧：解释器选择（FilterChip，选中高亮；自动检测只给推荐提示，不强制切换）+ 参数 + 辅助操作。 */
@Composable
private fun InterpreterPane(
    detail: MessageDetail,
    interpreters: List<MessageInterpreter>,
    selectedId: String,
    onSelect: (String) -> Unit,
    interpreter: MessageInterpreter,
    params: Map<String, String>,
    onParamsChange: (Map<String, String>) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCopy: () -> Unit,
    clipboard: ClipboardManager,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(
            text = stringResource("msg_detail_interpreter"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (item in interpreters) {
                FilterChip(
                    selected = item.id == selectedId,
                    onClick = { onSelect(item.id) },
                    label = { Text(stringResource(item.displayName)) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // ---- 参数区：按 paramDefs 渲染；无参数给提示文案 ----
        if (interpreter.paramDefs.isEmpty()) {
            Text(
                text = stringResource("msg_detail_no_params"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (def in interpreter.paramDefs) {
                when (def.kind) {
                    ParamKind.BOOLEAN -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(def.label), style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = params[def.id] == "true",
                                onCheckedChange = { checked ->
                                    onParamsChange(params + (def.id to checked.toString()))
                                },
                            )
                        }
                    }
                    ParamKind.SELECT -> {
                        // 标签在下拉框上方；readOnly 文本框展示当前选中项，透明点击层
                        // 负责开菜单（readOnly 文本框自己会抢焦点）。
                        var menuExpanded by remember(def.id) { mutableStateOf(false) }
                        val selectedValue = params[def.id] ?: ""
                        val selectedLabel = def.options
                            .firstOrNull { it.value == selectedValue }
                            ?.let { stringResource(it.label) }
                            ?: selectedValue
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                text = stringResource(def.label),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            Box {
                                OutlinedTextField(
                                    value = selectedLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    singleLine = true,
                                    trailingIcon = {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clickable { menuExpanded = true },
                                )
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                ) {
                                    for (option in def.options) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(option.label)) },
                                            onClick = {
                                                onParamsChange(params + (def.id to option.value))
                                                menuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        OutlinedTextField(
                            value = params[def.id] ?: "",
                            onValueChange = { v -> onParamsChange(params + (def.id to v)) },
                            label = { Text(stringResource(def.label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // ---- 辅助操作：复制正文 / 展开折叠（与左侧联动）----
        if (detail.dataLength > 0) {
            Text(
                text = stringResource("msg_detail_copy_body"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        clipboard.setText(AnnotatedString(MessageCharsets.decode(detail.content, detail.codedCharSetId)))
                        onCopy()
                    }
                    .padding(vertical = 6.dp),
            )
            if (MessageCharsets.decode(detail.content, detail.codedCharSetId).length > MAX_PREVIEW_CHARS) {
                Text(
                    text = if (expanded) stringResource("msg_detail_collapse") else stringResource("msg_detail_expand"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onExpandedChange(!expanded) }
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}

/** 中间细分隔线（左右分栏的立式分隔）。 */
@Composable
private fun VerticalDividerThin() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/** 加载中：居中转圈 + 提示。 */
@Composable
private fun DetailLoadingView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource("msg_detail_loading"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 加载失败：原因（MQRC 已翻译）+ 重试。 */
@Composable
private fun DetailErrorView(reason: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource("msg_detail_load_failed").withArg(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        PressableButton(onClick = onRetry) { Text(stringResource("msg_detail_retry")) }
    }
}
