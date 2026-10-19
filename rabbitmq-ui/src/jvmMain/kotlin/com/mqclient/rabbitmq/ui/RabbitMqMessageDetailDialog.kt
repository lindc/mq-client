package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreters
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.ParamKind
import com.mqclient.message.interpreter.hexPreview
import com.mqclient.message.interpreter.isProbablyBinary
import com.mqclient.rabbitmq.RabbitMqFetchedMessage
import com.mqclient.uicommon.SettingsDropdownRow
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** RabbitMQ 传给解释器 SPI 的 MQMD 味参数：AMQP 只有 contentEncoding 字符串，第一版统一按 UTF-8（与 Kafka 的 1208 同口径）。 */
private const val INTERPRETER_ENCODING = 0
private const val INTERPRETER_CCSID = 1208

/**
 * AMQP 消息详情对话框（队列消息列表「详情」入口）：左右分栏 —— 左 = 正文（解释器
 * SPI 渲染，参数区随 paramDefs 生成，二进制内容提供 hex 预览切换），右 = AMQP
 * BasicProperties + 自定义 Headers 键值表。参照 KafkaMessageDetailDialog 的结构。
 */
@Composable
fun RabbitMqMessageDetailDialog(
    message: RabbitMqFetchedMessage,
    onDismiss: () -> Unit,
) {
    val interpreters = remember { MessageInterpreters.loadAll() }
    var interpreterId by remember { mutableStateOf(interpreters.firstOrNull()?.id ?: "") }
    val interpreter = interpreters.firstOrNull { it.id == interpreterId } ?: interpreters.firstOrNull()
    var params by remember(interpreterId) { mutableStateOf(interpreter?.defaultParams ?: emptyMap()) }
    val binary = remember(message.deliveryTag) { isProbablyBinary(message.body, INTERPRETER_CCSID) }
    var showHex by remember(message.deliveryTag) { mutableStateOf(binary) }

    // 解释器渲染：纯函数但可能解码大 body，放 Default 线程；二进制不走解释器。
    val rendered by produceState<Pair<String, String>?>(null, message.deliveryTag, interpreterId, params) {
        value = null
        if (binary || interpreter == null) return@produceState
        value = withContext(Dispatchers.Default) {
            when (val result = interpreter.interpret(message.body, INTERPRETER_ENCODING, INTERPRETER_CCSID, params)) {
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
            // ---- 左：正文 ----
            Column(modifier = Modifier.weight(1.4f).fillMaxHeight().padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource("rabbitmq_ui_msg_detail_body"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = showHex,
                        onClick = { showHex = !showHex },
                        label = { Text(stringResource("rabbitmq_ui_msg_detail_hex"), style = MaterialTheme.typography.labelMedium) },
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (interpreter != null && interpreter.paramDefs.isNotEmpty()) {
                    interpreter.paramDefs.forEach { def ->
                        when (def.kind) {
                            ParamKind.SELECT -> SettingsDropdownRow(
                                label = def.label,
                                currentLabel = def.options.firstOrNull { it.value == (params[def.id] ?: "") }?.label ?: "",
                                options = def.options,
                                optionLabel = { it.label },
                                onPick = { params = params + (def.id to it.value) },
                            )
                            else -> OutlinedTextField(
                                value = params[def.id] ?: "",
                                onValueChange = { params = params + (def.id to it) },
                                label = { Text(def.label) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val display: String = when {
                        showHex -> hexPreview(message.body)
                        rendered == null -> ""
                        else -> rendered!!.first.ifEmpty { message.body.toString(Charsets.UTF_8) }
                    }
                    when {
                        !showHex && rendered == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                        !showHex && rendered!!.second.isNotEmpty() -> Column {
                            Text(
                                text = stringResource("rabbitmq_ui_msg_detail_render_failed").withArg(rendered!!.second),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                            ScrollableMonoText(display)
                        }
                        else -> ScrollableMonoText(display)
                    }
                }
            }
            // ---- 分隔竖线 ----
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            // ---- 右：AMQP Properties + Headers ----
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource("rabbitmq_ui_msg_detail_props"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                PropertyRow("message id", message.messageId)
                PropertyRow(
                    "timestamp",
                    message.timestamp?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(it) },
                )
                PropertyRow("delivery mode", stringResource(if (message.persistent) "rabbitmq_ui_msg_persistent" else "rabbitmq_ui_msg_transient"))
                PropertyRow("content type", message.contentType)
                PropertyRow("content encoding", message.contentEncoding)
                PropertyRow("priority", message.priority?.toString())
                PropertyRow("expiration", message.expiration)
                PropertyRow("correlation id", message.correlationId)
                PropertyRow("reply to", message.replyTo)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource("rabbitmq_ui_msg_detail_headers"),
                    style = MaterialTheme.typography.labelMedium,
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
                        PropertyRow(k, v)
                    }
                }
            }
        }
    }
}

/** 可滚动的等宽正文（hex / 渲染文本共用）。 */
@Composable
private fun ScrollableMonoText(text: String) {
    Text(
        text = text.ifEmpty { " " },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    )
}

/** 右栏属性行：小写属性名（次要色）+ 值（monospace，null 显示 "-"）。 */
@Composable
private fun PropertyRow(name: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value ?: "-",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
