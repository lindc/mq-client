package com.mqclient.ibmmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mqclient.ibmmq.messaging.DmpmqmsgMessage
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * dmpmqmsg 导入预览对话框（消息详情对话框「导入 dmpmqmsg」）：展示解析出的消息
 * 属性 + 消息体预览，确认后由调用方发送。多消息文件经下拉选择要导入的那一条。
 *
 * 属性还原策略（见 DmpmqmsgFormat / sendMessageToQueue）：MessageId 由 MQ 重新生成
 * 不还原；CorrelId（全 0 视为空）/ Persistence / Priority / Expiry / ReplyTo /
 * Format / CCSID 还原。
 */
@Composable
fun DmpmqmsgImportDialog(
    fileName: String,
    messages: List<DmpmqmsgMessage>,
    selectedIndex: Int,
    onSelectMessage: (Int) -> Unit,
    executing: Boolean,
    errorText: String?,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = messages[selectedIndex.coerceIn(0, messages.lastIndex)]

    AlertDialog(
        onDismissRequest = { if (!executing) onDismiss() },
        title = { Text(stringResource("ibm_mq_msg_import_title").withArg(fileName)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource("ibm_mq_msg_import_count").withArg(messages.size.toString()))
                if (messages.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    ImportMessageSelector(
                        messages = messages,
                        selectedIndex = selectedIndex,
                        onSelect = onSelectMessage,
                    )
                }

                Spacer(Modifier.height(12.dp))
                ImportAttrRow(stringResource("ibm_mq_msg_detail_h_msgid"), message.msgIdHex.ifBlank { "-" })
                ImportAttrRow(
                    stringResource("ibm_mq_msg_detail_h_correlid"),
                    if (message.hasCorrelId) message.correlIdHex else "-",
                )
                ImportAttrRow(stringResource("ibm_mq_msg_detail_h_msgtype"), msgTypePretty(message.msgType))
                ImportAttrRow(stringResource("ibm_mq_msg_detail_h_persistence"), persistencePretty(message.persistence))
                ImportAttrRow(stringResource("ibm_mq_msg_detail_h_priority"), message.priority?.toString() ?: "-")
                ImportAttrRow(stringResource("ibm_mq_msg_detail_h_expiry"), expiryPretty(message.expiry))
                ImportAttrRow(stringResource("ibm_mq_msg_detail_h_format"), message.format.ifBlank { "-" })
                ImportAttrRow(stringResource("ibm_mq_msg_detail_h_ccsid"), message.ccsid?.toString() ?: "-")
                ImportAttrRow(
                    stringResource("ibm_mq_msg_detail_h_replyto"),
                    listOf(message.replyToQMgr, message.replyToQueue).filter { it.isNotBlank() }
                        .joinToString("/").ifBlank { "-" },
                )
                ImportAttrRow(stringResource("ibm_mq_msg_detail_h_datalength"), "${message.body.size}")

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource("ibm_mq_msg_import_body_preview"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = dmpmqmsgBodyPreview(message),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(8.dp),
                )
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource("ibm_mq_msg_import_failed").withArg(errorText),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(onClick = onImport, enabled = !executing) {
                Text(if (executing) stringResource("ibm_mq_msg_import_executing") else stringResource("ibm_mq_msg_import_confirm"))
            }
        },
        dismissButton = {
            SecondaryButton(
                onClick = onDismiss,
                enabled = !executing,
            ) { Text(stringResource("common_cancel")) }
        },
    )
}

/** 多消息文件的消息选择器（只读文本框 + 透明点击层开下拉，复用参数区交互模式）。 */
@Composable
private fun ImportMessageSelector(
    messages: List<DmpmqmsgMessage>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = stringResource("ibm_mq_msg_import_message_label").withArg((selectedIndex + 1).toString()),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { androidx.compose.material3.Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box {
            Column(modifier = Modifier.matchParentSize()) {}
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { menuExpanded = true },
            )
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                messages.forEachIndexed { index, item ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource("ibm_mq_msg_import_message_label").withArg((index + 1).toString()) +
                                    " (${item.format.ifBlank { "-" }}, ${item.body.size} B)",
                            )
                        },
                        onClick = {
                            onSelect(index)
                            menuExpanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * 非 dmpmqmsg 文件的 fallback 对话框：用户可取消，或把文件内容直接当消息体发送
 * （不解析任何 MQMD 属性，全部走 MQ 默认值）。
 */
@Composable
fun DmpmqmsgFallbackDialog(
    fileName: String,
    executing: Boolean,
    errorText: String?,
    onSendPlain: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!executing) onDismiss() },
        title = { Text(stringResource("ibm_mq_msg_import_fallback_title")) },
        text = {
            Column {
                Text(stringResource("ibm_mq_msg_import_fallback_message").withArg(fileName))
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource("ibm_mq_msg_import_failed").withArg(errorText),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(onClick = onSendPlain, enabled = !executing) {
                Text(if (executing) stringResource("ibm_mq_msg_import_executing") else stringResource("ibm_mq_msg_import_fallback_send"))
            }
        },
        dismissButton = {
            SecondaryButton(
                onClick = onDismiss,
                enabled = !executing,
            ) { Text(stringResource("common_cancel")) }
        },
    )
}

/** 属性行：左标签（i18n 键复用 Header Tab 的）+ 右值（等宽）。 */
@Composable
private fun ImportAttrRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

/* ------------------------------ 展示辅助 ------------------------------ */

private fun msgTypePretty(value: Int?): String = when (value) {
    null -> "-"
    1 -> "$value (REQUEST)"
    2 -> "$value (REPLY)"
    4 -> "$value (REPORT)"
    8 -> "$value (DATAGRAM)"
    else -> value.toString()
}

private fun persistencePretty(value: Int?): String = when (value) {
    null -> "-"
    1 -> "$value (PERSISTENT)"
    0 -> "$value (NOT PERSISTENT)"
    else -> value.toString()
}

private fun expiryPretty(value: Int?): String = when (value) {
    null -> "-"
    -1 -> "$value (UNLIMITED)"
    else -> "${value / 10.0}s"
}

/** 消息体预览：文本按 CCSID 解码（前 2000 字符）；二进制给 hex 预览（前 256 字节）。 */
private fun dmpmqmsgBodyPreview(message: DmpmqmsgMessage): String {
    if (message.body.isEmpty()) return stringResource("ibm_mq_msg_detail_empty_body")
    val ccsid = message.ccsid ?: 819
    val binary = message.body.size >= 8 && message.body.take(8192).any { it == 0.toByte() }
    return if (binary) {
        val bytes = message.body.take(256).toByteArray()
        bytes.joinToString(" ") { "%02X".format(it) } + if (message.body.size > 256) "\n…" else ""
    } else {
        MessageCharsets.decode(message.body, ccsid).take(2000)
    }
}
