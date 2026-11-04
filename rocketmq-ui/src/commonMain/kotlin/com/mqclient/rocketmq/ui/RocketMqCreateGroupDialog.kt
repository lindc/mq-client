package com.mqclient.rocketmq.ui

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
import androidx.compose.ui.unit.dp
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.dialog.DialogSizeClass
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** RocketMQ 消费组名的合法字符（与 mqadmin 校验同口径）。 */
private val GROUP_NAME_REGEX = Regex("^[%|a-zA-Z0-9_-]+$")

/**
 * 创建消费组对话框（消费组列表工具栏「+ 添加消费组」入口）：ObjectDetailDialog
 * 外壳 + 表单（名称 / 是否顺序消费）。提交走
 * [com.mqclient.rocketmq.RocketMqScanService.createConsumerGroup]
 * （`createAndUpdateSubscriptionGroupConfig` 写全部 Master broker，update 语义
 * 幂等）；成功后 [onCreated] 回调（父级 scanNow 刷新列表 + Snackbar）。
 *
 * 注：SubscriptionGroupConfig 没有 description 字段（broker 侧无该属性），故无
 * 描述输入项。
 */
@Composable
fun RocketMqCreateGroupDialog(
    connectionId: String,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var orderly by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val trimmed = name.trim()
        val validationError = when {
            trimmed.isEmpty() -> stringResource("rocketmq_ui_group_create_name_required")
            !GROUP_NAME_REGEX.matches(trimmed) ->
                stringResource("rocketmq_ui_group_create_name_invalid")
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
                service.createConsumerGroup(connectionId, trimmed, orderly)
                creating = false
                onCreated()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                creating = false
                error = stringResource("rocketmq_ui_group_create_failed_prefix") +
                    (e.message ?: e::class.simpleName ?: "unknown")
            }
        }
    }

    ObjectDetailDialog(
        sizeClass = DialogSizeClass.FORM,
        title = stringResource("rocketmq_ui_group_create_title"),
        onDismiss = onDismiss,
        footerActions = {
            PrimaryButton(onClick = { submit() }, enabled = !creating) {
                Text(stringResource("rocketmq_ui_group_create_action"))
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
                text = stringResource("rocketmq_ui_group_create_name"),
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
                text = stringResource("rocketmq_ui_group_create_name_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = orderly, onCheckedChange = { orderly = it }, enabled = !creating)
                Text(
                    text = stringResource("rocketmq_ui_group_create_orderly"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
