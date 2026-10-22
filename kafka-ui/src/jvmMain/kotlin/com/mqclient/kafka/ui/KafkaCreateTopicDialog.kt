package com.mqclient.kafka.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.mqclient.kafka.KafkaModule
import com.mqclient.uicommon.LabeledField
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.launch

/**
 * 创建主题对话框（主题列表页「创建主题」/ 空态引导入口）：ObjectDetailDialog 外壳 +
 * 三字段表单（主题名 / 分区数 / 副本因子）。提交走
 * [com.mqclient.kafka.KafkaScanService.createTopic]（AdminClient.createTopics，v1 不带
 * config）；成功后服务端把新主题即时写入缓存并广播 OBJECTS_SCANNED，列表立即刷新。
 *
 * 校验：主题名必填；分区数 / 副本因子须为正整数。服务端错误（已存在 / 副本数超
 * broker 数等）解包装后显示在表单内，不闪关。
 */
@Composable
fun KafkaCreateTopicDialog(
    connectionId: String,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var partitions by remember { mutableStateOf("1") }
    var replicas by remember { mutableStateOf("1") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var partitionsError by remember { mutableStateOf<String?>(null) }
    var replicasError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        var ok = true
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            nameError = stringResource("kafka_ui_topic_create_error_name_required")
            ok = false
        } else {
            nameError = null
        }
        val p = partitions.trim().toIntOrNull()
        if (p == null || p < 1) {
            partitionsError = stringResource("kafka_ui_topic_create_error_partitions")
            ok = false
        } else {
            partitionsError = null
        }
        val r = replicas.trim().toIntOrNull()
        if (r == null || r < 1) {
            replicasError = stringResource("kafka_ui_topic_create_error_replicas")
            ok = false
        } else {
            replicasError = null
        }
        if (!ok) return

        val service = KafkaModule.serviceOrNull() ?: run {
            submitError = "服务未初始化"
            return
        }
        submitting = true
        submitError = null
        scope.launch {
            try {
                service.createTopic(connectionId, trimmedName, p!!, r!!)
                // 成功：服务端已把新主题写入缓存并广播 OBJECTS_SCANNED，列表立即刷新。
                onDismiss()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                submitting = false
                submitError = failureText(e)
            }
        }
    }

    ObjectDetailDialog(
        title = stringResource("kafka_ui_topic_create_title"),
        onDismiss = onDismiss,
        minWidth = 480.dp,
        minHeight = 320.dp,
        footerActions = {
            PressableButton(
                onClick = { submit() },
                enabled = !submitting,
            ) { Text(stringResource("kafka_ui_topic_create_confirm")) }
            SecondaryButton(
                onClick = onDismiss,
            ) { Text(stringResource("common_cancel")) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            LabeledField(stringResource("kafka_ui_topic_create_field_name")) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                    },
                    isError = nameError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FormFieldError(nameError)
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                // weight(1f) 各占一半：LabeledField 自带 fillMaxWidth，但不挂 weight
                // 会被第一个字段的输入框吃满整行（实测裁切 bug）。
                Column(modifier = Modifier.weight(1f)) {
                    LabeledField(stringResource("kafka_ui_topic_create_field_partitions")) {
                        OutlinedTextField(
                            value = partitions,
                            onValueChange = {
                                partitions = it
                                partitionsError = null
                            },
                            isError = partitionsError != null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FormFieldError(partitionsError)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    LabeledField(stringResource("kafka_ui_topic_create_field_replicas")) {
                        OutlinedTextField(
                            value = replicas,
                            onValueChange = {
                                replicas = it
                                replicasError = null
                            },
                            isError = replicasError != null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FormFieldError(replicasError)
                    }
                }
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
