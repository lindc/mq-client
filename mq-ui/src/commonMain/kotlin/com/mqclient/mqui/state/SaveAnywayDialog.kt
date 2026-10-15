package com.mqclient.mqui.state

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.stringResource

/**
 * 「连接失败，是否仍要保存」确认框：保存前测试失败（含超时）时弹出。原
 * ibm-mq-ui 的 SaveAnywayDialog，抽到 mq-ui 供 IBM MQ / Kafka 共用。
 *
 * 「保存」= 表单的 confirmSave，无视失败按测试时的配置快照落盘（宿主随后经
 * onSaved 关窗）；「取消」= dismissFailed，只关本框，主对话框原样保留、
 * 表单继续可改。
 */
@Composable
fun SaveAnywayDialog(
    message: String,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource("common_save_confirm_title")) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource("common_save_confirm_text"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            // PressableButton：与设置/连接弹窗同一按钮体系（保存=primary 默认款）。
            PressableButton(onClick = onSave) { Text(stringResource("common_save")) }
        },
        dismissButton = {
            PressableButton(
                onClick = onCancel,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_cancel")) }
        },
    )
}
