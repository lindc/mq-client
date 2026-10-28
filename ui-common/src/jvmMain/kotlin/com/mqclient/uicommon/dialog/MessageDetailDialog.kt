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
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.file.saveFileBytes
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 正文预览长度上限（字符）：超出只显示前段，底部「展开全部」。 */
private const val MAX_PREVIEW_CHARS = 20_000

/**
 * 送进解码 / 解释器的字节上限（1 MB）：超大消息在 Default 线程全量解码 + 解释器
 * 渲染（JSON pretty-print 等）动辄秒级卡顿甚至 OOM，超限只取前 1 MB —— 解释失败
 * 自动回落原始文本（现有 Failure 路径），渲染侧永远有界。「复制正文」复制**当前
 * 展示的文本**（解释器 formatted 全量 / hex；显示截断不影响复制；未就绪回退全量
 * 原文解码），「下载消息」写原始字节（无损，不受 1 MB 渲染上限影响）。
 */
private const val MAX_BODY_BYTES = 1024 * 1024

/** 大内容警告横幅的语义橙（不随主题翻转，与各模块的告警橙同一约定）。 */
private val LargeBodyWarning = Color(0xFFED6C02)

/** 「Hex 预览」解释器（message-interpreter-hex）的 id：二进制内容默认选中它。 */
private const val HEX_INTERPRETER_ID = "hex"

/**
 * 正文解码 / 解释器渲染的**協程层**超时。
 *
 * 重要：它只能让**可取消**的等待提前结束，**管不住阻塞调用**。实测（
 * `withTimeout(200)` 套一个阻塞 1.2s 的块，实际耗时 1245ms）：`withTimeout` 要等块体
 * 自己跑完才抛 `TimeoutCancellationException`。解码 `String(bytes, charset)` 是纯 CPU
 * 工作，没问题；但解释器是 SPI，第三方实现里完全可以出现 DOM 解析 / 正则灾难 / IO ——
 * 那些都会让 5s 变成「5s 之后才开始算」。
 *
 * 所以这里只是第一道闂，真正保证「界面不会无声地转圈」的是 [STALL_HINT_MS] 那条
 * 独立的看门狗：它在另一个協程里计时，不受被阻塞的渲染協程影响。
 */
private const val RENDER_TIMEOUT_MS = 5_000L

/**
 * 看门狗的报警点（毫秒）：到点仍没出结果，就在界面上把「正在加载」换成可读的异常提示。
 *
 * 为什么需要它：上面那个 `withTimeout` 对阻塞块无效，而 `ContentTab` 原先在两个
 * `null` 分支里只有一个永不停歇的 `CircularProgressIndicator` —— 一旦解释器卡住，
 * 用户看到的就是「永远转圈」，既无原因也无出口。
 *
 * 取比 [RENDER_TIMEOUT_MS] 大的值：正常超时路径（可取消的那种）先走文字回退，
 * 只有真卡住才轮到它。
 */
private const val STALL_HINT_MS = 8_000L

/** 解释器渲染结果：展示文本 + 解析失败原因（失败时展示原始内容 + 提示）。 */
private data class RenderedText(val text: String, val parseError: String? = null)

/**
 * 正文基线：二进制判定结果 + 原始文本（非二进制）或 hex 预览（二进制）。
 *
 * [decodeError] 非 null 表示解码本身超时 / 抛异常：这时连“原文”都拿不到，正文区
 * 必须直接给原因而不是继续转圈。
 */
private data class ContentPreview(
    val isBinary: Boolean,
    val rawText: String,
    val hex: String,
    val decodeError: String? = null,
)

/**
 * 消息详情对话框（公用，IBM MQ / Kafka / RabbitMQ 三家共用，外观 100% 一致）：
 * 左右分栏 3:2 —— 左侧「正文 / Header」两个 Tab（正文由本组件完全接管：UTF-8
 * 解码、二进制 hex 回退、解释器渲染、解析失败横幅、大文本截断/展开），Header Tab
 * 渲染 [headerContent] 插槽（各行用 [MessageHeaderRow]，小节标题用
 * [MessageDetailSectionTitle]）；右侧解释器选择 + 参数区（列表来自
 * [MessageInterpreters]，不在本组件写死；二进制内容默认选中 Hex 解释器）。
 * 底部统一「下载消息 / 复制正文」+ 关闭，[extraBottomActions] 渲染在三者之前；
 * [downloadBaseName] 定制下载文件名主体。
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
    /** 下载消息的默认文件名主体（不带扩展名）；null = msg_<yyyyMMdd_HHmmss>。IBM / Kafka 传各自的定制名。 */
    downloadBaseName: String? = null,
    minWidth: Dp = 760.dp,
    minHeight: Dp = 520.dp,
    nestedContent: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    // 解释器选择与渲染状态：提升到对话框级 —— footer 的「下载消息 / 复制正文」需要
    // 读取当前展示的文本、二进制标志与选中解释器（原先在 ContentTab 内部拿不到）。
    //
    // 用 [MessageInterpreters.all]（进程级缓存）而不是 loadAll()：后者每次都要走一遍
    // ServiceLoader 扫 classpath，而本对话框每开一次都会读它 —— 开销落在 composition
    // 线程上。缓存后只有进程内首次要付这个代价。
    val interpreters = remember { MessageInterpreters.all() }
    val oversized = rawBody.size > MAX_BODY_BYTES
    // remember(rawBody)：超大消息的截断拷贝**不能**每次重组都重做。之前写成裸的
    // `if (oversized) rawBody.copyOf(...)`，数组身份每次都变 → remember / LaunchedEffect
    // 的 key 每次都变 → 解码与渲染无限重启，最终表现就是转圈不停。
    val effectiveBody = remember(rawBody) {
        if (rawBody.size > MAX_BODY_BYTES) rawBody.copyOf(MAX_BODY_BYTES) else rawBody
    }
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
    var preview by remember(effectiveBody) { mutableStateOf<ContentPreview?>(null) }
    var rendered by remember(effectiveBody) { mutableStateOf<RenderedText?>(null) }
    var rendering by remember(effectiveBody) { mutableStateOf(false) }

    // 看门狗：到点还没出结果就把「转圈」换成提示。
    //
    // 必须是**独立**的 LaunchedEffect：它观测 result，而不是干活的那个協程 —— 后者一旦
    // 卡在阻塞调用上，既不会写状态也不会被 withTimeout 打断（见 RENDER_TIMEOUT_MS）。
    var stalled by remember(effectiveBody, selectedId, params) { mutableStateOf(false) }
    LaunchedEffect(effectiveBody, selectedId, params, preview, rendered) {
        if (preview != null && rendered != null) {
            stalled = false
            return@LaunchedEffect
        }
        stalled = false
        delay(STALL_HINT_MS)
        stalled = true
    }

    // 超时 / 解码失败文案先在这里取好：LaunchedEffect 与异常分支都不是 @Composable
    // 上下文，里面拿不到 stringResource。
    val decodeFailedMessage = stringResource("msg_detail_decode_failed")
    val renderTimeoutReason = stringResource("msg_detail_render_timeout").withArg(RENDER_TIMEOUT_MS / 1_000)

    LaunchedEffect(effectiveBody, binary, codedCharSetId) {
        preview =
            try {
                withTimeout(RENDER_TIMEOUT_MS) {
                    withContext(Dispatchers.Default) {
                        ContentPreview(
                            isBinary = binary,
                            rawText = if (binary) "" else MessageCharsets.decode(effectiveBody, codedCharSetId),
                            hex = if (binary) hexPreview(effectiveBody) else "",
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // 超时不是取消：状态必须落地，否则正文区永远停在转圈。
                ContentPreview(isBinary = binary, rawText = "", hex = "", decodeError = decodeFailedMessage)
            } catch (e: CancellationException) {
                // 真正的取消（对话框已关）原样抛出，不写任何状态。
                throw e
            } catch (e: Exception) {
                ContentPreview(isBinary = binary, rawText = "", hex = "", decodeError = decodeFailedMessage)
            }
    }
    LaunchedEffect(effectiveBody, interpreter, params, preview) {
        val base = preview ?: return@LaunchedEffect
        // 二进制只支持 Hex 解释器（对字节流跑文本解释器无意义）。
        if (base.isBinary && interpreter?.id != HEX_INTERPRETER_ID) return@LaunchedEffect
        // 解码本身没成功：没有可渲染的文本，详情页直接展示解码失败原因。
        if (base.decodeError != null) return@LaunchedEffect
        val target = interpreter ?: return@LaunchedEffect
        rendering = true
        try {
            rendered =
                withTimeout(RENDER_TIMEOUT_MS) {
                    withContext(Dispatchers.Default) {
                        when (val result = target.interpret(effectiveBody, encoding, codedCharSetId, params)) {
                            is InterpreterResult.Success -> RenderedText(result.formatted)
                            is InterpreterResult.Failure -> RenderedText(base.rawText, result.message)
                        }
                    }
                }
        } catch (e: TimeoutCancellationException) {
            // 超时只影响“格式化”：原文照样展示，原因走解析失败条。
            rendered = RenderedText(base.rawText, renderTimeoutReason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 解释器抛异常 = 渲染失败：回退原文。
            // 关键：异常绝不能穿出 LaunchedEffect —— Compose 会因此取消整个组合的
            // 作用域，之后再也不重组，按钮照旧收到点击但界面不再响应（
            // “关闭没反应”就是这么来的）。
            rendered = RenderedText(base.rawText, e.message ?: e::class.simpleName ?: "")
        } finally {
            // ← 本次 bug 的根：这个标志是右上角“渲染中”小转圈的开关，之前只置
            // true、从未置 false，于是“正文已渲染 + 小转圈永不停”同时成立。
            // 成功 / 失败 / 超时 / 取消一律清掉，状态不可能停在加载中。
            rendering = false
        }
    }

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
            SecondaryButton(
                onClick = {
                    scope.launch {
                        try {
                            val ext = when {
                                binary || selectedId == HEX_INTERPRETER_ID -> "bin"
                                selectedId == "json" -> "json"
                                else -> "txt"
                            }
                            val baseName = downloadBaseName
                                ?: "msg_" + java.time.LocalDateTime.now()
                                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            val saved = withContext(Dispatchers.IO) {
                                saveFileBytes(baseName + "." + ext, ext, rawBody)
                            }
                            if (saved != null) {
                                snackbarHostState.showSnackbar(stringResource("msg_detail_saved").withArg(saved))
                            }
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar(stringResource("msg_detail_save_failed").withArg(e.message ?: ""))
                        }
                    }
                },
                enabled = rawBody.isNotEmpty(),
            ) { Text(stringResource("msg_detail_download")) }
            SecondaryButton(
                onClick = {
                    // 复制当前展示的文本：hex 模式复制 hex；解释器模式复制全量 formatted
                    // （显示截断不影响复制）；未就绪 / 解析失败回退全量原文解码。
                    val copied = when {
                        binary || selectedId == HEX_INTERPRETER_ID -> preview?.hex ?: ""
                        else -> rendered?.text?.takeIf { it.isNotEmpty() }
                            ?: preview?.rawText
                            ?: MessageCharsets.decode(rawBody, codedCharSetId)
                    }
                    clipboard.setText(AnnotatedString(copied))
                    showCopied()
                },
                enabled = rawBody.isNotEmpty(),
            ) { Text(stringResource("msg_detail_copy_body")) }
            Spacer(Modifier.weight(1f))
            // 关闭必须无条件、立即生效：**不要**给它加 `enabled = !rendering` 之类的
            // 门禁。渲染 / 下载可以慢，退出不能慢 —— 一旦这里被拦，用户能选的只剩
            // 杀进程。界面上清理状态的职责在渲染协程的 finally，不在这个按钮。
            SecondaryButton(
                onClick = onDismiss,
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
                    oversized = oversized,
                    effectiveBody = effectiveBody,
                    originalSizeBytes = rawBody.size.toLong(),
                    binary = binary,
                    selectedId = selectedId,
                    interpreters = interpreters,
                    interpreter = interpreter,
                    params = params,
                    preview = preview,
                    rendered = rendered,
                    rendering = rendering,
                    stalled = stalled,
                    onSelect = { selectedId = it },
                    onParamsChange = { params = it },
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

/** 就绪态主体：左侧 3/5（正文 / Header Tab）+ 右侧 2/5（解释器区），中间细分隔线。
 *  解释器选择与渲染状态由 [MessageDetailDialog] 持有（footer 的复制 / 下载按钮要读）。
 */
@Composable
private fun DetailReadyPane(
    interpreters: List<MessageInterpreter>,
    oversized: Boolean,
    effectiveBody: ByteArray,
    originalSizeBytes: Long,
    binary: Boolean,
    selectedId: String,
    interpreter: MessageInterpreter?,
    params: Map<String, String>,
    preview: ContentPreview?,
    rendered: RenderedText?,
    rendering: Boolean,
    stalled: Boolean,
    onSelect: (String) -> Unit,
    onParamsChange: (Map<String, String>) -> Unit,
    headerContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
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
                    originalSizeBytes = originalSizeBytes,
                    binary = binary,
                    preview = preview,
                    rendered = rendered,
                    rendering = rendering,
                    stalled = stalled,
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
            onSelect = onSelect,
            interpreter = interpreter,
            params = params,
            onParamsChange = onParamsChange,
            modifier = Modifier.weight(2f).fillMaxSize(),
        )
    }
}

/** Tab 1：消息正文（纯视图）：解释器渲染 + 预览截断 + 解析失败回落原文 + 二进制 hex 预览。 */
@Composable
private fun ContentTab(
    rawBody: ByteArray,
    oversized: Boolean,
    originalSizeBytes: Long,
    binary: Boolean,
    preview: ContentPreview?,
    rendered: RenderedText?,
    rendering: Boolean,
    stalled: Boolean,
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
                // 首帧：基线还没算完。卡住时不能只转圈 —— 看门狗到点就给原因。
                base == null && !stalled -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                )
                base == null -> Text(
                    text = stringResource("msg_detail_stall_decoding").withArg(STALL_HINT_MS / 1_000),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                        .padding(8.dp),
                )
                // 解码超时 / 抛异常：既拿不到文本也没有 hex，直接给原因，**不转圈**。
                base.decodeError != null -> Text(
                    text = base.decodeError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                        .padding(8.dp),
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
                    if (renderedValue == null && !stalled) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
                        )
                    } else {
                        // 卡住时**仍然给出内容**：基线（原文解码）早就好了，没必要陪着转圈。
                        // 这是用户真正能用的出口 —— 看得到字，才能判断要不要换解释器。
                        if (renderedValue == null) {
                            Text(
                                text = stringResource("msg_detail_stall_rendering").withArg(STALL_HINT_MS / 1_000),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                                    .padding(8.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        val text = renderedValue?.text ?: base.rawText
                        val parseError = renderedValue?.parseError
                        // 解析失败：保留原始内容 + 错误提示条。
                        parseError?.let { err ->
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
                        val display = if (expanded || text.length <= MAX_PREVIEW_CHARS) {
                            text
                        } else {
                            text.take(MAX_PREVIEW_CHARS) + "\n…"
                        }
                        Text(
                            text = display,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (text.length > MAX_PREVIEW_CHARS) {
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
        PrimaryButton(onClick = onRetry) { Text(stringResource("common_retry")) }
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
