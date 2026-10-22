package com.mqclient.rocketmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.SettingsDropdownRow
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** RocketMQ 主题名的合法字符（比消费组多点号，如 `app.orders`）。 */
private val TOPIC_NAME_REGEX = Regex("^[%|a-zA-Z0-9._-]+$")

/**
 * 创建主题对话框（主题列表工具栏「+ 新建主题」入口）：ObjectDetailDialog 外壳 +
 * 表单（名称 / 读队列数 / 写队列数 / 权限下拉）。提交走
 * [com.mqclient.rocketmq.RocketMqScanService.createTopic]
 * （`createAndUpdateTopicConfig` 逐 broker 写 TopicConfig，update 语义幂等）；
 * 成功后 [onCreated] 回调（父级 scanNow 刷新 —— 新路由经 broker 心跳注册到
 * NameServer 有秒级延迟，首扫可能暂缺，属预期）。
 *
 * 注：TopicConfig 没有 description 字段（broker 侧无该属性），无备注输入项。
 */
@Composable
fun RocketMqCreateTopicDialog(
    connectionId: String,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var readQueues by remember { mutableStateOf("8") }
    var writeQueues by remember { mutableStateOf("8") }
    var perm by remember { mutableStateOf(6) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val trimmed = name.trim()
        val read = readQueues.trim().toIntOrNull()
        val write = writeQueues.trim().toIntOrNull()
        val validationError = when {
            trimmed.isEmpty() -> stringResource("rocketmq_ui_topic_create_name_required")
            !TOPIC_NAME_REGEX.matches(trimmed) -> stringResource("rocketmq_ui_topic_create_name_invalid")
            read == null || read < 1 -> stringResource("rocketmq_ui_topic_create_queues_invalid")
            write == null || write < 1 -> stringResource("rocketmq_ui_topic_create_queues_invalid")
            else -> null
        }
        if (validationError != null) {
            error = validationError
            return
        }
        val service = RocketMqModule.serviceOrNull() ?: run {
            error = stringResource("rocketmq_ui_conn_props_no_service")
            return
        }
        creating = true
        error = null
        scope.launch {
            try {
                service.createTopic(connectionId, trimmed, read ?: 8, write ?: 8, perm)
                creating = false
                onCreated()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                creating = false
                error = stringResource("rocketmq_ui_topic_create_failed_prefix") +
                    (e.message ?: e::class.simpleName ?: "unknown")
            }
        }
    }

    ObjectDetailDialog(
        title = stringResource("rocketmq_ui_topic_create_title"),
        onDismiss = onDismiss,
        minWidth = 480.dp,
        minHeight = 420.dp,
        footerActions = {
            PressableButton(onClick = { submit() }, enabled = !creating) {
                Text(stringResource("rocketmq_ui_topic_create_action"))
            }
            SecondaryButton(
                onClick = onDismiss,
            ) { Text(stringResource("common_cancel")) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource("rocketmq_ui_topic_create_name"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                singleLine = true,
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource("rocketmq_ui_topic_create_name_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource("rocketmq_ui_topic_create_read_queues"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = readQueues,
                onValueChange = { readQueues = it; error = null },
                singleLine = true,
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource("rocketmq_ui_topic_create_write_queues"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = writeQueues,
                onValueChange = { writeQueues = it; error = null },
                singleLine = true,
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            // 权限下拉：位值 6 读写 / 2 只读 / 4 只写（与 formatPerm 同口径）。
            // 标签在组合期预解析（optionLabel lambda 非 @Composable）。
            val permLabels = mapOf(
                6 to stringResource("rocketmq_ui_topic_perm_rw"),
                2 to stringResource("rocketmq_ui_topic_perm_r"),
                4 to stringResource("rocketmq_ui_topic_perm_w"),
            )
            SettingsDropdownRow(
                label = stringResource("rocketmq_ui_topic_create_perm"),
                currentLabel = permLabels[perm] ?: "-",
                options = permLabels.keys.toList(),
                optionLabel = { permLabels[it] ?: "-" },
                onPick = { perm = it },
            )
            error?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
