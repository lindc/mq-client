package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 交换机类型的候选项（AMQP 0.9.1 四类）。 */
private val EXCHANGE_TYPE_OPTIONS = listOf("direct", "fanout", "topic", "headers")

/** 交换机名的合法字符集（字母 / 数字 / . _ : - —— 与 RabbitMQ 官方建议一致）。 */
private val EXCHANGE_NAME_REGEX = Regex("[A-Za-z0-9._:-]+")

/**
 * 创建交换机对话框（交换机列表页「添加交换机」入口）：ObjectDetailDialog 外壳 +
 * 表单（名称 / 类型 / 持久化 / 自动删除）。提交走
 * [com.mqclient.rabbitmq.RabbitMqScanService.createExchange]（管理 API PUT）；成功后
 * 服务端已把新交换机写入缓存并广播 OBJECTS_SCANNED，列表立即刷新。
 *
 * 校验：名称必填 + 字符集（AMQP 建议字符）+ 禁 amq. 前缀（系统命名空间）。服务端
 * 错误（已存在 / 权限不足等）解包装后显示在表单内，不闪关。
 */
@Composable
fun RabbitMqCreateExchangeDialog(
    connectionId: String,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("direct") }
    var durable by remember { mutableStateOf(true) }
    var autoDelete by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        val trimmed = name.trim()
        when {
            trimmed.isBlank() -> {
                nameError = stringResource("rabbitmq_ui_exchange_create_error_name_required")
                return
            }
            EXCHANGE_NAME_REGEX.matchEntire(trimmed) == null -> {
                nameError = stringResource("rabbitmq_ui_exchange_create_error_name_charset")
                return
            }
            trimmed.startsWith("amq.") -> {
                nameError = stringResource("rabbitmq_ui_exchange_create_error_name_amq")
                return
            }
            else -> nameError = null
        }

        val service = RabbitMqModule.serviceOrNull() ?: run {
            submitError = "服务未初始化"
            return
        }
        submitting = true
        submitError = null
        scope.launch {
            try {
                service.createExchange(connectionId, trimmed, type, durable, autoDelete)
                // 成功：服务端已把新交换机写入缓存并广播 OBJECTS_SCANNED，列表立即刷新。
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
        title = stringResource("rabbitmq_ui_exchange_create_title"),
        onDismiss = onDismiss,
        minWidth = 480.dp,
        minHeight = 360.dp,
        footerActions = {
            PressableButton(
                onClick = { submit() },
                enabled = !submitting,
            ) { Text(stringResource("rabbitmq_ui_exchange_create_confirm")) }
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
            LabeledField(stringResource("rabbitmq_ui_exchange_create_field_name")) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                    },
                    isError = nameError != null,
                    singleLine = true,
                    placeholder = { Text("app.orders") },
                    modifier = Modifier.fillMaxWidth(),
                )
                FormFieldError(nameError)
            }
            Spacer(Modifier.height(12.dp))
            SettingsDropdownRow(
                label = stringResource("rabbitmq_ui_exchange_create_field_type"),
                currentLabel = type,
                options = EXCHANGE_TYPE_OPTIONS,
                optionLabel = { it },
                onPick = { type = it },
            )
            Spacer(Modifier.height(12.dp))
            BooleanOptionRow(
                label = stringResource("rabbitmq_ui_exchange_create_field_durable"),
                checked = durable,
                onChange = { durable = it },
            )
            BooleanOptionRow(
                label = stringResource("rabbitmq_ui_exchange_create_field_auto_delete"),
                checked = autoDelete,
                onChange = { autoDelete = it },
            )
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

/** 布尔选项行：Checkbox + 标签同行。 */
@Composable
private fun BooleanOptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 字段下方的红色错误小字；null 不占位。 */
@Composable
private fun FormFieldError(message: String?) {
    if (message != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 失败原因：解 ExecutionException 包装（与其他调用处同一简化版）。 */
private fun failureText(error: Throwable): String =
    (error as? java.util.concurrent.ExecutionException)?.cause?.message
        ?: error.message
        ?: error::class.simpleName
        ?: "unknown"
