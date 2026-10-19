@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreters
import com.mqclient.message.interpreter.ParamKind
import com.mqclient.message.interpreter.hexPreview
import com.mqclient.message.interpreter.isProbablyBinary
import com.mqclient.rabbitmq.RabbitMqFetchedMessage
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** RabbitMQ 传给解释器 SPI 的 MQMD 味参数：AMQP 只有 contentEncoding 字符串，第一版统一按 UTF-8（与 Kafka 的 1208 同口径）。 */
private const val INTERPRETER_ENCODING = 0
private const val INTERPRETER_CCSID = 1208

/** 「Hex 预览」伪解释器的 id（右侧列表末位选项；不是真正的解释器实现）。 */
private const val HEX_INTERPRETER_ID = "hex"

/**
 * AMQP 消息详情对话框（队列消息列表「详情」入口）—— 布局对齐 IBM MQ 的
 * MessageDetailDialog：**左 3/5** = PrimaryTabRow 两 Tab（消息正文 / 消息属性）、
 * **右 2/5** = 解释器区（FlowRow FilterChip 流式排列 + 参数区，末位是「Hex 预览」
 * 伪选项，二进制内容默认选中它），中间细分隔线。
 *
 * 渲染：解释器纯函数但可能解码大 body，放 Default 线程（produceState）；
 * Success 显示 formatted，Failure 走柔和错误横幅（errorContainer 30%）+ 回退原文；
 * Hex 选中直接显示 [hexPreview]。只读展示。
 */
@Composable
fun RabbitMqMessageDetailDialog(
    message: RabbitMqFetchedMessage,
    onDismiss: () -> Unit,
) {
    val interpreters = remember { MessageInterpreters.loadAll() }
    val binary = remember(message.deliveryTag) { isProbablyBinary(message.body, INTERPRETER_CCSID) }
    var selectedId by remember(message.deliveryTag) {
        // 二进制内容默认 Hex（对字节流跑文本解释器无意义）。
        mutableStateOf(if (binary) HEX_INTERPRETER_ID else interpreters.firstOrNull()?.id ?: "")
    }
    val selected = interpreters.firstOrNull { it.id == selectedId }
    var params by remember(selectedId) { mutableStateOf(selected?.defaultParams ?: emptyMap()) }
    val showHex = selectedId == HEX_INTERPRETER_ID

    val rendered by produceState<Pair<String, String>?>(null, message.deliveryTag, selectedId, params) {
        value = null
        if (showHex || selected == null) return@produceState
        value = withContext(Dispatchers.Default) {
            when (val result = selected.interpret(message.body, INTERPRETER_ENCODING, INTERPRETER_CCSID, params)) {
                is InterpreterResult.Success -> result.formatted to ""
                is InterpreterResult.Failure -> MessageCharsets.decode(message.body, INTERPRETER_CCSID) to result.message
            }
        }
    }

    ObjectDetailDialog(
        title = stringResource("rabbitmq_ui_msg_detail_title"),
        onDismiss = onDismiss,
        minWidth = 760.dp,
        minHeight = 520.dp,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ---- 左 3/5：正文 / 属性 两个 Tab ----
            Column(modifier = Modifier.weight(3f).fillMaxSize()) {
                var tab by remember(message.deliveryTag) { mutableStateOf(0) }
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text(stringResource("rabbitmq_ui_msg_detail_tab_body")) },
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text(stringResource("rabbitmq_ui_msg_detail_tab_props")) },
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (tab == 0) {
                        ContentTab(showHex = showHex, binary = binary, rendered = rendered, body = message.body)
                    } else {
                        HeaderTab(message = message, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            VerticalDividerThin()
            // ---- 右 2/5：解释器列表（FlowRow FilterChip，含 Hex 伪选项）+ 参数 ----
            Column(modifier = Modifier.weight(2f).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(
                    text = stringResource("rabbitmq_ui_msg_detail_interpreters"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    interpreters.forEach { candidate ->
                        FilterChip(
                            selected = selectedId == candidate.id,
                            onClick = { selectedId = candidate.id },
                            label = { Text(stringResource(candidate.displayName)) },
                        )
                    }
                    FilterChip(
                        selected = showHex,
                        onClick = { selectedId = HEX_INTERPRETER_ID },
                        label = { Text(stringResource("rabbitmq_ui_msg_detail_hex")) },
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                // ---- 参数区：按 paramDefs 渲染；无参数给提示文案（IBM 同款）----
                val defs = selected?.paramDefs ?: emptyList()
                if (showHex || defs.isEmpty()) {
                    Text(
                        text = stringResource("rabbitmq_ui_msg_detail_no_params"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    defs.forEach { def ->
                        when (def.kind) {
                            ParamKind.BOOLEAN -> Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(def.label), style = MaterialTheme.typography.bodySmall)
                                Switch(
                                    checked = params[def.id] == "true",
                                    onCheckedChange = { checked -> params = params + (def.id to checked.toString()) },
                                )
                            }
                            ParamKind.SELECT -> {
                                // readOnly 文本框展示当前选中项，透明点击层负责开菜单（IBM 同款）。
                                var menuExpanded by remember(def.id) { mutableStateOf(false) }
                                val selectedValue = params[def.id] ?: ""
                                val selectedLabel = def.options
                                    .firstOrNull { it.value == selectedValue }
                                    ?.let { stringResource(it.label) }
                                    ?: selectedValue
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(stringResource(def.label), style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.height(4.dp))
                                    Box {
                                        OutlinedTextField(
                                            value = selectedLabel,
                                            onValueChange = {},
                                            readOnly = true,
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall,
                                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clickable { menuExpanded = true },
                                        )
                                    }
                                    androidx.compose.material3.DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                    ) {
                                        def.options.forEach { opt ->
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { Text(stringResource(opt.label)) },
                                                onClick = {
                                                    params = params + (def.id to opt.value)
                                                    menuExpanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            else -> OutlinedTextField(
                                value = params[def.id] ?: "",
                                onValueChange = { params = params + (def.id to it) },
                                label = { Text(stringResource(def.label)) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------ 左：两个 Tab ------------------------------ */

/**
 * 消息正文（IBM ContentTab 同款）：二进制 = 提示行 + hex 预览；解析失败 = 柔和
 * 错误横幅（errorContainer 30%）+ 回退原文；整体在同一个滚动容器里。
 */
@Composable
private fun ContentTab(
    showHex: Boolean,
    binary: Boolean,
    rendered: Pair<String, String>?,
    body: ByteArray,
) {
    val display: String = when {
        showHex -> hexPreview(body)
        rendered == null -> ""
        else -> rendered.first.ifEmpty { body.toString(Charsets.UTF_8) }
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        when {
            // 首帧：渲染还没算完。
            !showHex && rendered == null -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
            )
            else -> {
                if (binary) {
                    // 二进制提示行（IBM ibm_mq_msg_detail_binary_notice 同款）。
                    Text(
                        text = stringResource("rabbitmq_ui_msg_detail_binary_notice"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    rendered?.second?.let { err ->
                        // 解析失败：柔和错误横幅 + 回退原文（IBM 同款）。
                        Text(
                            text = stringResource("rabbitmq_ui_msg_detail_render_failed").withArg(err),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                                .padding(8.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Text(
                    text = display.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** 消息属性：AMQP BasicProperties 投影 + 自定义 Headers 键值表（IBM HeaderTab 同款：key Medium + 复制按钮）。 */
@Composable
private fun HeaderTab(message: RabbitMqFetchedMessage, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Text(
            text = stringResource("rabbitmq_ui_msg_detail_props"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        HeaderRow(clipboard, "message id", message.messageId)
        HeaderRow(clipboard, "timestamp", message.timestamp?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(it) })
        HeaderRow(clipboard, "delivery mode", stringResource(if (message.persistent) "rabbitmq_ui_msg_persistent" else "rabbitmq_ui_msg_transient"))
        HeaderRow(clipboard, "content type", message.contentType)
        HeaderRow(clipboard, "content encoding", message.contentEncoding)
        HeaderRow(clipboard, "priority", message.priority?.toString())
        HeaderRow(clipboard, "expiration", message.expiration)
        HeaderRow(clipboard, "correlation id", message.correlationId)
        HeaderRow(clipboard, "reply to", message.replyTo)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource("rabbitmq_ui_msg_detail_headers"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        if (message.headers.isEmpty()) {
            Text(
                text = stringResource("rabbitmq_ui_msg_detail_no_headers"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            message.headers.forEach { (k, v) ->
                HeaderRow(clipboard, k, v)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/* ------------------------------ 右 / 公共小件 ------------------------------ */

/** 属性行（IBM HeaderTab 同款）：key Medium 140dp + 值 monospace + 复制按钮 + 0.5dp 分隔线。 */
@Composable
private fun HeaderRow(clipboard: ClipboardManager, name: String, value: String?) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
            Text(
                text = name,
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
            IconButton(onClick = { clipboard.setText(AnnotatedString(value ?: "")) }, modifier = Modifier.height(20.dp)) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = stringResource("rabbitmq_ui_msg_detail_copy_value"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        HorizontalDivider(modifier = Modifier.fillMaxWidth(), thickness = 0.5.dp)
    }
}

/** 细分隔线（左右分栏之间，与 IBM MessageDetailDialog 同款）。 */
@Composable
private fun VerticalDividerThin() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
