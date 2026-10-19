@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mqclient.uicommon.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.MessageInterpreters
import com.mqclient.message.interpreter.ParamKind
import com.mqclient.message.interpreter.hexPreview
import com.mqclient.message.interpreter.isProbablyBinary
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 正文预览长度上限（字符）：超出只显示前段，底部「展开全部」。 */
private const val MAX_PREVIEW_CHARS = 20_000

/**
 * 送进解码 / 解释器的字节上限（1 MB）：超大消息在 Default 线程全量解码 + 解释器
 * 渲染（JSON pretty-print 等）动辄秒级卡顿甚至 OOM，超限只取前 1 MB —— 解释失败
 * 自动回落原始文本（现有 Failure 路径），渲染侧永远有界。「复制正文」仍复制完整
 * 原文（点击时一次性解码，不进渲染链路）。
 */
private const val MAX_BODY_BYTES = 1024 * 1024

/** 大内容警告横幅的语义橙（不随主题翻转，与各模块的告警橙同一约定）。 */
private val LargeBodyWarning = Color(0xFFED6C02)

/** 「Hex 预览」解释器（message-interpreter-hex）的 id：二进制内容默认选中它。 */
private const val HEX_INTERPRETER_ID = "hex"

/** 解释器渲染结果：展示文本 + 解析失败原因（失败时展示原始内容 + 提示）。 */
private data class RenderedText(val text: String, val parseError: String? = null)

/** 正文基线：二进制判定结果 + 原始文本（非二进制）或 hex 预览（二进制）。 */
private data class ContentPreview(val isBinary: Boolean, val rawText: String, val hex: String)

/**
 * 消息详情对话框（公用，IBM MQ / Kafka / RabbitMQ 三家共用，外观 100% 一致）：
 * 左右分栏 3:2 —— 左侧「正文 / Header」两个 Tab（正文由本组件完全接管：UTF-8
 * 解码、二进制 hex 回退、解释器渲染、解析失败横幅、大文本截断/展开），Header Tab
 * 渲染 [headerContent] 插槽（各行用 [MessageHeaderRow]，小节标题用
 * [MessageDetailSectionTitle]）；右侧解释器选择 + 参数区（列表来自
 * [MessageInterpreters]，不在本组件写死；二进制内容默认选中 Hex 解释器）。
 * 底部统一「复制正文」+ 关闭，[extraBottomActions] 渲染在二者之前。
 *
 * **加载三态不在本组件**：数据获取方式各模块不同（IBM/Kafka 现查 service，
 * Rabbit 本地对象）—— 宿主自行 when 分发，非就绪态用 [MessageDetailLoadingView]
 * / [MessageDetailErrorView] 配一个普通 [ObjectDetailDialog] 呈现。
 *
 * [nestedContent]：需要**挂在本对话框窗口内**的嵌套对话框（如 IBM 的 dmpmqmsg
 * 导入预览）从这里声明 —— compose Dialog 的窗口归属随声明组合走，挂到外层会被
 * 本对话框压住（见 [ObjectDetailDialog] KDoc）。
 *
 * [encoding] / [codedCharSetId] 是 MQMD.Encoding / CodedCharSetId 口径的原始值：
 * IBM 传消息详情里的真值；Kafka / RabbitMQ 无此元数据，用默认 0 / 1208（UTF-8
 * 中性值，与原实现一致）。
 */
@Composable
fun MessageDetailDialog(
    title: String,
    rawBody: ByteArray,
    onDismiss: () -> Unit,
    headerContent: (@Composable () -> Unit)? = null,
    extraBottomActions: @Composable RowScope.() -> Unit = {},
    subtitleText: String? = null,
    encoding: Int = 0,
    codedCharSetId: Int = 1208,
    minWidth: Dp = 760.dp,
    minHeight: Dp = 520.dp,
    nestedContent: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    fun showCopied() {
        scope.launch { snackbarHostState.showSnackbar(stringResource("msg_detail_copied")) }
    }

    ObjectDetailDialog(
        title = title,
        onDismiss = onDismiss,
        minWidth = minWidth,
        minHeight = minHeight,
        footerActions = {
            extraBottomActions()
            PressableButton(
                onClick = {
                    clipboard.setText(AnnotatedString(MessageCharsets.decode(rawBody, codedCharSetId)))
                    showCopied()
                },
                enabled = rawBody.isNotEmpty(),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("msg_detail_copy_body")) }
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
                // ---- 副标题：等宽文本 + 复制按钮（可选）----
                if (subtitleText != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(subtitleText))
                            showCopied()
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource("msg_detail_copied"),
                                modifier = Modifier.height(16.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }

                DetailReadyPane(
                    rawBody = rawBody,
                    encoding = encoding,
                    codedCharSetId = codedCharSetId,
                    headerContent = headerContent,
                    modifier = Modifier.weight(1f),
                )
            }
            ThemedSnackbarHost(
                snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        nestedContent()
    }
}

/** 就绪态主体：左侧 3/5（正文 / Header Tab）+ 右侧 2/5（解释器区），中间细分隔线。 */
@Composable
private fun DetailReadyPane(
    rawBody: ByteArray,
    encoding: Int,
    codedCharSetId: Int,
    headerContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // 解释器选择与参数（切换解释器时参数回默认值）；二进制内容默认 Hex。
    val interpreters = remember { MessageInterpreters.loadAll() }
    // 大消息防线：解码 / 解释 / hex 全部只喂前 1 MB（渲染结果有界）；原始 body
    // 只留给「复制正文」按钮（footer 的一次性解码，不进渲染链路）。
    val oversized = rawBody.size > MAX_BODY_BYTES
    val effectiveBody = if (oversized) rawBody.copyOf(MAX_BODY_BYTES) else rawBody
    val binary = remember(effectiveBody, codedCharSetId) { isProbablyBinary(effectiveBody, codedCharSetId) }
    var selectedId by remember(effectiveBody) {
        val initial = if (binary && interpreters.any { it.id == HEX_INTERPRETER_ID }) {
            HEX_INTERPRETER_ID
        } else {
            interpreters.firstOrNull()?.id ?: ""
        }
        mutableStateOf(initial)
    }
    val interpreter = remember(selectedId, interpreters) {
        interpreters.firstOrNull { it.id == selectedId } ?: interpreters.firstOrNull()
    }
    var params by remember(interpreter) { mutableStateOf(interpreter?.defaultParams ?: emptyMap()) }
    var selectedTab by remember { mutableStateOf(0) }

    Row(modifier = modifier) {
        // ---- 左 3/5：正文 / Header ----
        Column(modifier = Modifier.weight(3f).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource("msg_detail_tab_content")) },
                )
                // Header 插槽未提供 → Tab 禁用（空 Header 与没有 Header Tab 是两回事）。
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    enabled = headerContent != null,
                    text = { Text(stringResource("msg_detail_tab_headers")) },
                )
            }
            if (selectedTab == 1 && headerContent != null) {
                headerContent()
            } else {
                ContentTab(
                    rawBody = effectiveBody,
                    oversized = oversized,
                    originalSizeBytes = rawBody.size.toLong(),
                    encoding = encoding,
                    codedCharSetId = codedCharSetId,
                    interpreter = interpreter,
                    params = params,
                    binary = binary,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ---- 中间细分隔线 ----
        VerticalDividerThin()

        // ---- 右 2/5：解释器选择（上）+ 参数（下）----
        InterpreterPane(
            interpreters = interpreters,
            selectedId = selectedId,
            onSelect = { selectedId = it },
            interpreter = interpreter,
            params = params,
            onParamsChange = { params = it },
            modifier = Modifier.weight(2f).fillMaxSize(),
        )
    }
}

/** Tab 1：消息正文（解释器渲染 + 预览截断 + 解析失败回落原始文本 + 二进制 hex 预览）。 */
@Composable
private fun ContentTab(
    rawBody: ByteArray,
    oversized: Boolean,
    originalSizeBytes: Long,
    encoding: Int,
    codedCharSetId: Int,
    interpreter: MessageInterpreter?,
    params: Map<String, String>,
    binary: Boolean,
    modifier: Modifier = Modifier,
) {
    if (rawBody.isEmpty()) {
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

    // 大内容展开/折叠（只在本 Tab 内；截断时正文底部给切换入口）。
    var expanded by remember(rawBody) { mutableStateOf(false) }

    // 预览基线（Default 线程算，避免大消息卡组合）：原始文本 / hex。
    var preview by remember(rawBody) { mutableStateOf<ContentPreview?>(null) }
    // 解释器渲染结果只按 rawBody 记忆：切换解释器 / 参数时保留旧内容继续显示
    // （不清空、不丢滚动位置），新结果算完替换；仅首帧为 null 显示转圈。
    var rendered by remember(rawBody) { mutableStateOf<RenderedText?>(null) }
    var rendering by remember(rawBody) { mutableStateOf(false) }

    LaunchedEffect(rawBody, binary) {
        preview = withContext(Dispatchers.Default) {
            ContentPreview(
                isBinary = binary,
                rawText = if (binary) "" else MessageCharsets.decode(rawBody, codedCharSetId),
                hex = if (binary) hexPreview(rawBody) else "",
            )
        }
    }
    LaunchedEffect(rawBody, interpreter, params, preview) {
        val base = preview ?: return@LaunchedEffect
        // 二进制只支持 Hex 解释器（对字节流跑文本解释器无意义）。
        if (base.isBinary && interpreter?.id != HEX_INTERPRETER_ID) return@LaunchedEffect
        val target = interpreter ?: return@LaunchedEffect
        rendering = true
        rendered = withContext(Dispatchers.Default) {
            when (val result = target.interpret(rawBody, encoding, codedCharSetId, params)) {
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
            // 大消息警告横幅：截断告知在最上方（浅橙底 + 语义橙文字，不随主题翻转）。
            if (oversized) {
                val mbText = String.format(
                    java.util.Locale.ROOT,
                    "%.1f",
                    originalSizeBytes / (1024.0 * 1024.0),
                )
                Text(
                    text = stringResource("msg_detail_large_body").withArg(mbText),
                    style = MaterialTheme.typography.bodySmall,
                    color = LargeBodyWarning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LargeBodyWarning.copy(alpha = 0.12f))
                        .padding(8.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            when {
                // 首帧：基线还没算完。
                base == null -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                )
                // 二进制：提示行 + hex 预览（Hex 解释器的结果就是同款 hex 文本）。
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
                        text = rendered?.text?.takeIf { it.isNotEmpty() } ?: base.hex,
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
                        // 大内容预览截断：超出只显示前段。
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
                                    .clickable { expanded = !expanded }
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

/** 右侧：解释器选择（FilterChip，来自 [MessageInterpreters]，不写死）+ 参数区（按 paramDefs 渲染）。 */
@Composable
private fun InterpreterPane(
    interpreters: List<MessageInterpreter>,
    selectedId: String,
    onSelect: (String) -> Unit,
    interpreter: MessageInterpreter?,
    params: Map<String, String>,
    onParamsChange: (Map<String, String>) -> Unit,
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
        val defs = interpreter?.paramDefs ?: emptyList()
        if (defs.isEmpty()) {
            Text(
                text = stringResource("msg_detail_no_params"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (def in defs) {
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
    }
}

/**
 * Header 插槽的标准行：key 140dp（Medium）+ 等宽 value（弹性，null 显示 -）+
 * 值复制按钮，行间 0.5dp 分隔线。IBM MQ / Kafka / RabbitMQ 的 Header Tab 共用。
 */
@Composable
fun MessageHeaderRow(key: String, value: String?, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier) {
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
                text = value ?: "-",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(value ?: "")) },
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
}

/** Header 插槽的小节标题（如 Kafka 的「消息 Headers (n)」、RabbitMQ 的「消息属性」）。 */
@Composable
fun MessageDetailSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** 加载中：居中转圈 + 提示（宿主三态分发的非就绪态用）。 */
@Composable
fun MessageDetailLoadingView(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 加载失败：原因 + 重试（宿主三态分发的非就绪态用）。 */
@Composable
fun MessageDetailErrorView(reason: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
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
        PressableButton(onClick = onRetry) { Text(stringResource("common_retry")) }
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
