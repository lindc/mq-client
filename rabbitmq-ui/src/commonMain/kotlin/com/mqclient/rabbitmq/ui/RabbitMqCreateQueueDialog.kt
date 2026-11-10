package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.dialog.DialogSizeClass
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 队列名的合法字符集（字母 / 数字 / . _ - —— RabbitMQ 官方建议，比交换机少冒号）。 */
private val QUEUE_NAME_REGEX = Regex("[A-Za-z0-9._-]+")

/**
 * 创建队列对话框（队列列表页「+ 创建队列」入口）：ObjectDetailDialog 外壳 + 表单
 * （名称 / 持久化 / 自动删除 / 排他 / Arguments）。提交走
 * [com.mqclient.rabbitmq.RabbitMqScanService.createQueue]（管理 API PUT）；成功后
 * 服务端已把新队列写入缓存并广播 OBJECTS_SCANNED，列表立即刷新。
 *
 * 校验：名称必填 + 字符集 + 禁 amq. 前缀（系统命名空间）；Exclusive 勾选时强制
 * Durable=false 并禁用该开关（服务端拒绝 durable+exclusive 组合）。Arguments 键值
 * 对复用 [HeadersEditor]，提交时值做类型化：纯整数字符串 → Long、"true"/"false" →
 * Boolean，其余按字符串（与管理端一致）。服务端错误解包装后显示在表单内，不闪关。
 */
@Composable
fun RabbitMqCreateQueueDialog(
    connectionId: String,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var durable by remember { mutableStateOf(true) }
    var autoDelete by remember { mutableStateOf(false) }
    var exclusive by remember { mutableStateOf(false) }
    var arguments by remember { mutableStateOf(listOf("" to "")) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /** 参数值类型化在 Service 层完成（纯整数 → Long、"true"/"false" → Boolean、其余字符串）。 */
    fun submit() {
        val trimmed = name.trim()
        when {
            trimmed.isBlank() -> {
                nameError = stringResource("rabbitmq_ui_queue_create_error_name_required")
                return
            }
            QUEUE_NAME_REGEX.matchEntire(trimmed) == null -> {
                nameError = stringResource("rabbitmq_ui_queue_create_error_name_charset")
                return
            }
            trimmed.startsWith("amq.") -> {
                nameError = stringResource("rabbitmq_ui_queue_create_error_name_amq")
                return
            }
            else -> nameError = null
        }

        val args: Map<String, String> = arguments
            .filter { it.first.isNotBlank() }
            .associate { it.first.trim() to it.second }

        val service = RabbitMqModule.serviceOrNull() ?: run {
            submitError = stringResource("rabbitmq_ui_send_service_not_ready")
            return
        }
        submitting = true
        submitError = null
        scope.launch {
            try {
                service.createQueue(
                    connectionId = connectionId,
                    name = trimmed,
                    durable = durable,
                    autoDelete = autoDelete,
                    exclusive = exclusive,
                    arguments = args,
                )
                // 成功：服务端已把新队列写入缓存并广播 OBJECTS_SCANNED，列表立即刷新。
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                submitting = false
                submitError = e.userMessage()
            }
        }
    }

    ObjectDetailDialog(
        sizeClass = DialogSizeClass.FORM,
        title = stringResource("rabbitmq_ui_queue_create_title"),
        onDismiss = onDismiss,
        footerActions = {
            PrimaryButton(
                onClick = { submit() },
                enabled = !submitting,
            ) { Text(stringResource("rabbitmq_ui_queue_create_confirm")) }
            SecondaryButton(
                onClick = onDismiss,
            ) { Text(stringResource("common_cancel")) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            LabeledField(stringResource("rabbitmq_ui_queue_create_field_name")) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                    },
                    isError = nameError != null,
                    singleLine = true,
                    placeholder = { Text("worker.tasks") },
                    modifier = Modifier.fillMaxWidth(),
                )
                FormFieldError(nameError)
            }
            Spacer(Modifier.height(12.dp))
            // Exclusive ⇒ 非持久化（服务端拒绝 durable+exclusive 组合）：勾选排他时
            // 强制关掉持久化并禁用该开关。
            BooleanOptionRow(
                label = stringResource("rabbitmq_ui_queue_create_field_durable"),
                checked = durable,
                enabled = !exclusive,
                onChange = { durable = it },
            )
            BooleanOptionRow(
                label = stringResource("rabbitmq_ui_queue_create_field_auto_delete"),
                checked = autoDelete,
                onChange = { autoDelete = it },
            )
            BooleanOptionRow(
                label = stringResource("rabbitmq_ui_queue_create_field_exclusive"),
                checked = exclusive,
                onChange = {
                    exclusive = it
                    if (it) durable = false
                },
            )
            Spacer(Modifier.height(12.dp))
            HeadersEditor(
                headers = arguments,
                enabled = !submitting,
                onChange = { arguments = it },
                titleLabel = stringResource("rabbitmq_ui_queue_create_field_arguments"),
                addLabel = stringResource("rabbitmq_ui_queue_create_add_argument"),
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

/** 布尔选项行：Checkbox + 标签同行（与创建交换机对话框同款）。 */
@Composable
private fun BooleanOptionRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { if (enabled) onChange(it) }, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
        )
    }
}

/** 字段下方的红色错误小字；null 不占位（与创建交换机对话框同款）。 */
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

