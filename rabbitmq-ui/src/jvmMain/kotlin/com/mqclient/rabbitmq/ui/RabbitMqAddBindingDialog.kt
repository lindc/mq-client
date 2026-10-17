package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.uicommon.LabeledField
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.SettingsDropdownRow
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 添加绑定对话框（交换机属性对话框的「+ 添加绑定」入口）：ObjectDetailDialog 外壳 +
 * 表单（目标队列下拉 / Routing Key）。提交走
 * [com.mqclient.rabbitmq.RabbitMqScanService.createBinding]
 * （`POST /api/bindings/{vhost}/e/{exchange}/q/{queue}`）；成功后 [onCreated] 回调
 * （父级重载实时列表 + scanNow 同步慢扫缓存）。
 *
 * 校验：目标队列必选（缓存无队列时下拉禁用 + 提示）；direct / topic 类型 Routing Key
 * 必填，fanout 可留空（提示语义）。Arguments 第一版固定 `{}`（KDoc 注明，后续按需
 * 加键值对编辑）。仅支持绑定到 Queue（Exchange 目标的第一版不画，见拓扑 KDoc）。
 */
@Composable
fun RabbitMqAddBindingDialog(
    connectionId: String,
    exchangeName: String,
    exchangeType: String,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val queues = remember(connectionId) {
        RabbitMqModule.serviceOrNull()?.cachedQueues(connectionId).orEmpty().map { it.name }
    }
    var destination by remember { mutableStateOf<String?>(null) }
    var routingKey by remember { mutableStateOf("") }
    var destinationError by remember { mutableStateOf<String?>(null) }
    var routingKeyError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        val target = destination
        when {
            target == null -> {
                destinationError = stringResource("rabbitmq_ui_binding_add_error_queue_required")
                return
            }
            exchangeType != "fanout" && routingKey.isBlank() -> {
                routingKeyError = stringResource("rabbitmq_ui_binding_add_error_routing_key_required")
                return
            }
            else -> {
                destinationError = null
                routingKeyError = null
            }
        }

        val service = RabbitMqModule.serviceOrNull() ?: run {
            submitError = "服务未初始化"
            return
        }
        submitting = true
        submitError = null
        scope.launch {
            try {
                service.createBinding(connectionId, exchangeName, target, routingKey.trim())
                onCreated()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                submitting = false
                submitError = failureText(e)
            }
        }
    }

    ObjectDetailDialog(
        title = stringResource("rabbitmq_ui_binding_add_title").withArg(exchangeName),
        onDismiss = onDismiss,
        minWidth = 480.dp,
        minHeight = 340.dp,
        footerActions = {
            PressableButton(
                onClick = { submit() },
                enabled = !submitting && queues.isNotEmpty(),
            ) { Text(stringResource("rabbitmq_ui_binding_add_confirm")) }
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_cancel")) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (queues.isEmpty()) {
                Text(
                    text = stringResource("rabbitmq_ui_binding_add_destination_none"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SettingsDropdownRow(
                    label = stringResource("rabbitmq_ui_binding_add_destination"),
                    currentLabel = destination
                        ?: stringResource("rabbitmq_ui_binding_add_destination_pick"),
                    options = queues,
                    optionLabel = { it },
                    onPick = {
                        destination = it
                        destinationError = null
                    },
                )
                destinationError?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LabeledField(stringResource("rabbitmq_ui_binding_add_routing_key")) {
                OutlinedTextField(
                    value = routingKey,
                    onValueChange = {
                        routingKey = it
                        routingKeyError = null
                    },
                    isError = routingKeyError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                routingKeyError?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (exchangeType == "fanout") {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource("rabbitmq_ui_binding_add_fanout_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            submitError?.let { error ->
                Spacer(Modifier.height(12.dp))
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

/** 失败原因：解 ExecutionException 包装（与其他调用处同一简化版）。 */
private fun failureText(error: Throwable): String =
    (error as? java.util.concurrent.ExecutionException)?.cause?.message
        ?: error.message
        ?: error::class.simpleName
        ?: "unknown"
