package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.rabbitmq.RabbitMqFetchedMessage
import com.mqclient.rabbitmq.RabbitMqMessageSession
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.uicommon.LabeledField
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.SettingsDropdownRow
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 消息转发对话框（浏览列表「转发」入口，DLQ 重新投递场景）：目标类型（队列 /
 * 交换机）+ 目标下拉 + Routing Key（仅交换机目标显示）+「转发后删除原消息」开关
 * （默认勾选 = 搬移语义 publish + ack；不勾 = 复制语义只发不删）。
 *
 * 转发经当前 AMQP 会话的 [RabbitMqMessageSession.publish]（原始字节直发），属性
 * 用 [RabbitMqMessageSession.buildProps] 重建 —— 原消息的 messageId / headers /
 * contentType 等元数据全保留；勾选删除时 [RabbitMqMessageSession.ack] 原消息。
 * [onForwarded](deleteSource) 回调父级刷新列表。
 */
@Composable
fun RabbitMqForwardMessageDialog(
    connectionId: String,
    sourceQueue: String,
    message: RabbitMqFetchedMessage,
    session: RabbitMqMessageSession,
    onDismiss: () -> Unit,
    onForwarded: (deleteSource: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var destinationType by remember { mutableStateOf("queue") }
    var destination by remember { mutableStateOf<String?>(null) }
    var routingKey by remember { mutableStateOf("") }
    var deleteSource by remember { mutableStateOf(true) }
    var forwarding by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    // 目标候选：队列排除当前队列（搬回自身 = 死循环）；交换机排除默认（""，不可
    // 作为 destination）与 amq.* 系统。
    val queueCandidates = remember(connectionId) {
        RabbitMqModule.serviceOrNull()?.cachedQueues(connectionId).orEmpty()
            .map { it.name }
            .filter { it != sourceQueue }
    }
    val exchangeCandidates = remember(connectionId) {
        RabbitMqModule.serviceOrNull()?.cachedExchanges(connectionId).orEmpty()
            .map { it.name }
            .filter { it.isNotEmpty() && !it.startsWith("amq.") }
    }
    val candidates = if (destinationType == "queue") queueCandidates else exchangeCandidates

    fun submit() {
        val target = destination
        if (target == null) {
            submitError = stringResource("rabbitmq_ui_binding_add_error_queue_required")
            return
        }
        forwarding = true
        submitError = null
        scope.launch {
            try {
                val rk = if (destinationType == "queue") target else routingKey.trim()
                session.forward(
                    message = message,
                    exchange = if (destinationType == "queue") "" else target,
                    routingKey = rk,
                )
                if (deleteSource) {
                    session.ack(message.deliveryTag)
                }
                onForwarded(deleteSource)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                forwarding = false
                submitError = failureText(e)
            }
        }
    }

    ObjectDetailDialog(
        title = stringResource("rabbitmq_ui_msg_forward_title"),
        onDismiss = onDismiss,
        minWidth = 480.dp,
        minHeight = 380.dp,
        footerActions = {
            PressableButton(onClick = { submit() }, enabled = !forwarding && destination != null) {
                Text(stringResource("rabbitmq_ui_msg_action_forward"))
            }
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_cancel")) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            // 消息摘要（只读）：MessageId + 大小。
            Text(
                text = (message.messageId ?: "-") + " · " + formatSizeOf(message.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            SettingsDropdownRow(
                label = stringResource("rabbitmq_ui_binding_add_dest_type"),
                currentLabel = stringResource(
                    if (destinationType == "queue") "rabbitmq_ui_binding_add_dest_type_queue"
                    else "rabbitmq_ui_binding_add_dest_type_exchange",
                ),
                options = listOf("queue", "exchange"),
                optionLabel = {
                    stringResource(
                        if (it == "queue") "rabbitmq_ui_binding_add_dest_type_queue"
                        else "rabbitmq_ui_binding_add_dest_type_exchange",
                    )
                },
                onPick = {
                    destinationType = it
                    destination = null
                },
            )
            Spacer(Modifier.height(8.dp))
            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(
                        if (destinationType == "queue") "rabbitmq_ui_binding_add_destination_none"
                        else "rabbitmq_ui_binding_add_destination_none_exchange",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SettingsDropdownRow(
                    label = stringResource("rabbitmq_ui_binding_add_destination"),
                    currentLabel = destination
                        ?: stringResource("rabbitmq_ui_binding_add_destination_pick"),
                    options = candidates,
                    optionLabel = { it },
                    onPick = { destination = it },
                )
            }
            Spacer(Modifier.height(12.dp))
            if (destinationType == "exchange") {
                LabeledField(stringResource("rabbitmq_ui_binding_add_routing_key")) {
                    OutlinedTextField(
                        value = routingKey,
                        onValueChange = { routingKey = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = stringResource("rabbitmq_ui_binding_add_fanout_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = deleteSource, onCheckedChange = { deleteSource = it }, enabled = !forwarding)
                Text(
                    text = stringResource("rabbitmq_ui_msg_forward_delete_source"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            submitError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 失败原因：解 ExecutionException 包装（与其他对话框调用处同一简化版）。 */
private fun failureText(error: Throwable): String =
    (error as? java.util.concurrent.ExecutionException)?.cause?.message
        ?: error.message
        ?: error::class.simpleName
        ?: "unknown"

private fun formatSizeOf(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
