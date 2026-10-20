package com.mqclient.uicommon

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mqclient.uicommon.i18n.stringResource

/**
 * 只读路径字段 + 「浏览」按钮：路径只能经文件选择器填入（不允许手写），
 * 取消选择则保持原值。IBM MQ 与 Kafka 的连接表单共用同一套 —— 按钮位置
 * （trailingIcon）、文案（common_browse）、只读语义全部一致。
 *
 * [onClear] 可选：传入时显示「清空」按钮（common_clear），供「选填」型路径
 * （如 TLS Keystore）撤掉已选文件；不传保持原样，已有调用方零影响。
 */
@Composable
fun PathRow(
    label: String,
    value: String,
    onBrowse: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    LabeledField(label) {
        OutlinedTextField(
            value = value,
            onValueChange = { /* 只读：路径一律来自文件选择器 */ },
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                Row {
                    if (onClear != null) {
                        TextButton(onClick = onClear) { Text(stringResource("common_clear")) }
                    }
                    TextButton(onClick = onBrowse) { Text(stringResource("common_browse")) }
                }
            },
        )
    }
}
