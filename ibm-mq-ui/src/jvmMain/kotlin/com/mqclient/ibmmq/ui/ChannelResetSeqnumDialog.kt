package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 重置通道序列号的二次确认对话框（通道属性对话框「运行状态」分组用）：
 *
 *  - 展示通道名与当前序列号，新序列号输入框默认「1」，仅接受正整数；
 *  - 警示文案说明用途与不可撤销性；
 *  - [executing] 为 true 时确认按钮禁用并显示「重置中…」，输入框一并锁定；
 *  - [errorText] 非空（上次 RESET 失败）时在输入框下方以错误色展示原因码可读文案
 *    —— 对话框不关闭，用户可修正后重试或取消。
 *
 * 确认后回调 [onConfirm]（已校验的正整数），命令构造与执行在调用方
 * （ChannelRuntimeStatusPane，经既有 MqscExecutor 通道）。
 */
@Composable
fun ChannelResetSeqnumDialog(
    channelName: String,
    currentSeqNum: Long?,
    executing: Boolean,
    errorText: String?,
    onConfirm: (newSeqNum: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("1") }
    val parsed = input.toLongOrNull()?.takeIf { it > 0 }

    AlertDialog(
        onDismissRequest = { if (!executing) onDismiss() },
        title = { Text(stringResource("ibm_mq_channel_reset_title").withArg(channelName)) },
        text = {
            Column {
                Text(stringResource("ibm_mq_channel_reset_current").withArg(currentSeqNum?.toString() ?: "-"))
                Spacer(Modifier.height(12.dp))
                Text(stringResource("ibm_mq_channel_reset_message"))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { text ->
                        // 只放行数字，天然挡掉负号 / 非法字符；范围校验在确认时做。
                        if (text.all { it.isDigit() } && text.length <= 10) input = text
                    },
                    label = { Text(stringResource("ibm_mq_channel_reset_new")) },
                    isError = input.isNotBlank() && parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !executing,
                    supportingText = {
                        if (input.isNotBlank() && parsed == null) {
                            Text(stringResource("ibm_mq_channel_reset_invalid"))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource("ibm_mq_channel_reset_failed").withArg(errorText),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            // PressableButton(danger)：与项目其余危险确认弹窗同一按钮体系（error 语义色）。
            PressableButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null && !executing,
                danger = true,
            ) {
                Text(if (executing) stringResource("ibm_mq_channel_reset_executing") else stringResource("ibm_mq_channel_reset_confirm"))
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
