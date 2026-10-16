@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.mqclient.kafka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.mqclient.kafka.KafkaMessageDetail
import com.mqclient.kafka.KafkaMessageSummary
import com.mqclient.kafka.KafkaModule
import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.MessageInterpreters
import com.mqclient.message.interpreter.ParamKind
import com.mqclient.message.interpreter.hexPreview
import com.mqclient.message.interpreter.isProbablyBinary
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.file.saveFileBytes
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 正文预览长度上限（字符）：超出只显示前段，底部「展开全部」。 */
private const val MAX_PREVIEW_CHARS = 20_000

/**
 * Kafka 传给解释器 SPI 的 MQMD 味参数：Kafka 消息没有 encoding / CCSID 元数据，
 * 固定传「UTF-8 + 默认字节序」的中性值 —— [MessageCharsets.decode] 按它选对字符集，
 * 未知值回落 UTF-8 的兜底也一致。
 */
private const val INTERPRETER_ENCODING = 0
private const val INTERPRETER_CCSID = 1208

/** 详情对话框的三态：加载中 / 失败（带原因，可重试）/ 就绪。 */
private sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Failed(val reason: String) : DetailUiState
    data class Ready(val detail: KafkaMessageDetail) : DetailUiState
}

/** 解释器渲染结果：展示文本 + 解析失败原因（失败时展示原始内容 + 提示）。 */
private data class RenderedText(val text: String, val parseError: String? = null)

/** 正文基线：二进制判定结果 + 原始文本（非二进制）或 hex 预览（二进制）。 */
private data class ContentPreview(val isBinary: Boolean, val rawText: String, val hex: String)

/**
 * 消息详情对话框（浏览表格「详情」按钮打开）：左右分栏 3:2 —— 左侧正文 / Header
 * 两个 Tab，右侧解释器选择 + 参数；底部「下载 | 关闭」。**不做删除**（Kafka 没有
 * 按消息删除的概念）。
 *
 * 数据经 service 的 readMessageDetail 按 (partition, offset) 现查（assign + seek，
 * 不提交 offset）；浏览到打开之间消息可能被 retention 清除 —— 失败态给出可读原因。
 */
@Composable
internal fun KafkaMessageDetailDialog(
    connectionId: String,
    topicName: String,
    summary: KafkaMessageSummary,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    val positionKey = "${summary.partition}:${summary.offset}"
    var uiState by remember(positionKey) { mutableStateOf<DetailUiState>(DetailUiState.Loading) }
    var retryKey by remember(positionKey) { mutableIntStateOf(0) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(positionKey, retryKey) {
        uiState = DetailUiState.Loading
        uiState = try {
            val service = KafkaModule.messageServiceOrNull()
                ?: throw IllegalStateException(stringResource("kafka_ui_send_service_not_ready"))
            DetailUiState.Ready(service.readMessageDetail(connectionId, topicName, summary.partition, summary.offset))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DetailUiState.Failed(e.message ?: e::class.simpleName ?: "unknown")
        }
    }

    fun showCopied() {
        scope.launch { snackbarHostState.showSnackbar(stringResource("kafka_ui_msg_copied")) }
    }

    /** 下载：消息体已在详情里（Ready 时才可见），直接写盘，不再回查。 */
    fun downloadDetail(detail: KafkaMessageDetail) {
        scope.launch {
            try {
                val saved = saveFileBytes(
                    "msg_${detail.topic}_p${detail.partition}_o${detail.offset}.bin",
                    "bin",
                    detail.valueBytes,
                )
                if (saved != null) {
                    snackbarHostState.showSnackbar(stringResource("kafka_ui_msg_saved").withArg(saved))
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(
                    stringResource("kafka_ui_msg_save_failed").withArg(e.message ?: ""),
                )
            }
        }
    }

    ObjectDetailDialog(
        title = stringResource("kafka_ui_msg_detail_title").withArg(summary.partition, summary.offset),
        onDismiss = onDismiss,
        minWidth = 720.dp,
        minHeight = 480.dp,
        footerActions = {
            val ready = uiState as? DetailUiState.Ready
            PressableButton(
                onClick = { downloadDetail(ready!!.detail) },
                enabled = ready != null,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("kafka_ui_msg_detail_download")) }
            Spacer(Modifier.weight(1f))
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_close")) }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 副标题：topic [p:o] · TimestampType 时间（等宽）+ 复制 ----
                val subtitle = "$topicName [${summary.partition}:${summary.offset}] · " +
                    formatTimestamp(summary.timestamp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(subtitle))
                        showCopied()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource("kafka_ui_msg_copied"),
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
}

/** 就绪态主体：左侧 3/5（正文 / Header Tab）+ 右侧 2/5（解释器区），中间细分隔线。 */
@Composable
private fun DetailReadyPane(
    detail: KafkaMessageDetail,
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
                    text = { Text(stringResource("kafka_ui_msg_detail_tab_content")) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { onTabChange(1) },
                    text = { Text(stringResource("kafka_ui_msg_detail_tab_header")) },
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
    detail: KafkaMessageDetail,
    interpreter: MessageInterpreter,
    params: Map<String, String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (detail.valueBytes.isEmpty()) {
        // 「消息体为空」只看字节数，与解析失败是两个状态。
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("kafka_ui_msg_detail_empty_body"),
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
        preview = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val binary = isProbablyBinary(detail.valueBytes, INTERPRETER_CCSID)
            ContentPreview(
                isBinary = binary,
                rawText = if (binary) "" else MessageCharsets.decode(detail.valueBytes, INTERPRETER_CCSID),
                hex = if (binary) hexPreview(detail.valueBytes) else "",
            )
        }
    }
    LaunchedEffect(detail, interpreter, params, preview) {
        val base = preview ?: return@LaunchedEffect
        if (base.isBinary) return@LaunchedEffect // 二进制不走解释器（对字节流无意义）
        rendering = true
        rendered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            when (
                val result = interpreter.interpret(
                    detail.valueBytes, INTERPRETER_ENCODING, INTERPRETER_CCSID, params,
                )
            ) {
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
                        text = stringResource("kafka_ui_msg_detail_binary"),
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
                                text = stringResource("kafka_ui_msg_detail_interpreter_error").withArg(err),
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
                                text = if (expanded) {
                                    stringResource("kafka_ui_msg_detail_collapse")
                                } else {
                                    stringResource("kafka_ui_msg_detail_expand")
                                },
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

/** Tab 2：消息元数据 + Headers（两列 Key / Value；每行 Value 右侧复制按钮）。 */
@Composable
private fun HeaderTab(detail: KafkaMessageDetail, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        // ---- 元数据小节：Topic / Partition / Offset / Timestamp / TimestampType / Key / 大小 ----
        MetaRow(stringResource("kafka_ui_msg_detail_h_topic"), detail.topic, clipboard)
        MetaRow(stringResource("kafka_ui_msg_detail_h_partition"), detail.partition.toString(), clipboard)
        MetaRow(stringResource("kafka_ui_msg_detail_h_offset"), detail.offset.toString(), clipboard)
        MetaRow(stringResource("kafka_ui_msg_detail_h_timestamp"), formatTimestamp(detail.timestamp), clipboard)
        MetaRow(stringResource("kafka_ui_msg_detail_h_timestamp_type"), detail.timestampType, clipboard)
        detail.keyBytes?.let { keyBytes ->
            MetaRow(
                stringResource("kafka_ui_msg_detail_h_key"),
                MessageCharsets.decode(keyBytes, INTERPRETER_CCSID),
                clipboard,
            )
            MetaRow(
                stringResource("kafka_ui_msg_detail_h_key_size"),
                detail.keySize?.toString() ?: "-",
                clipboard,
            )
        }
        MetaRow(stringResource("kafka_ui_msg_detail_h_value_size"), detail.valueSize.toString(), clipboard)

        // ---- 消息 Headers 小节 ----
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (detail.headers.isEmpty()) {
                stringResource("kafka_ui_msg_detail_headers_empty")
            } else {
                stringResource("kafka_ui_msg_detail_h_message_headers").withArg(detail.headers.size)
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        for (header in detail.headers) {
            val value = header.value
                ?.let { MessageCharsets.decode(it, INTERPRETER_CCSID) }
                ?: "(null)"
            MetaRow(header.key, value, clipboard)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Header Tab 的一行：key 140dp + 等宽 value（弹性）+ 复制按钮，行间细分隔线。 */
@Composable
private fun MetaRow(key: String, value: String, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = key,
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
                    contentDescription = stringResource("kafka_ui_msg_copied"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.height(14.dp),
                )
            }
        }
        HorizontalDivider(modifier = Modifier.fillMaxWidth(), thickness = 0.5.dp)
    }
}

/** 右侧：解释器选择（FilterChip）+ 参数（paramDefs 渲染）+ 辅助操作（与左侧联动）。 */
@Composable
private fun InterpreterPane(
    detail: KafkaMessageDetail,
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
            text = stringResource("kafka_ui_msg_detail_interpreter"),
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
                text = stringResource("kafka_ui_msg_detail_no_params"),
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
        if (detail.valueBytes.isNotEmpty()) {
            Text(
                text = stringResource("kafka_ui_msg_detail_copy_body"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        clipboard.setText(
                            AnnotatedString(MessageCharsets.decode(detail.valueBytes, INTERPRETER_CCSID)),
                        )
                        onCopy()
                    }
                    .padding(vertical = 6.dp),
            )
            if (MessageCharsets.decode(detail.valueBytes, INTERPRETER_CCSID).length > MAX_PREVIEW_CHARS) {
                Text(
                    text = if (expanded) {
                        stringResource("kafka_ui_msg_detail_collapse")
                    } else {
                        stringResource("kafka_ui_msg_detail_expand")
                    },
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
            text = stringResource("kafka_ui_msg_browse_loading"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 加载失败：原因（可能已过期清除）+ 重试。 */
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
            text = stringResource("kafka_ui_msg_browse_failed").withArg(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        PressableButton(onClick = onRetry) { Text(stringResource("common_retry")) }
    }
}
