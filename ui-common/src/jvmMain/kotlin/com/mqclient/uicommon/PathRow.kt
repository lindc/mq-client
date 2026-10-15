package com.mqclient.uicommon

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
 */
@Composable
fun PathRow(
    label: String,
    value: String,
    onBrowse: () -> Unit,
) {
    LabeledField(label) {
        OutlinedTextField(
            value = value,
            onValueChange = { /* 只读：路径一律来自文件选择器 */ },
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = onBrowse) { Text(stringResource("common_browse")) }
            },
        )
    }
}
