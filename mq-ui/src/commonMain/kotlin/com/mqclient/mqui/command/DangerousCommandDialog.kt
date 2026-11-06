package com.mqclient.mqui.command

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.DangerButton

/**
 * 危险命令（由注入的 [com.mqclient.mqui.command.api.CommandParser.isDangerous]
 * 判定）的二次确认框：展示将要执行的命令全文，确认后才真正跑。
 */
@Composable
fun DangerousCommandDialog(
    command: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("console_dangerous_title")) },
        text = {
            Column {
                Text(stringResource("console_dangerous_message"))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )
            }
        },
        confirmButton = {
            // DangerButton：与项目其余确认弹窗同一按钮体系（error 语义色）。
            DangerButton(onClick = onConfirm) {
                Text(stringResource("console_dangerous_confirm"))
            }
        },
        dismissButton = {
            SecondaryButton(
                onClick = onDismiss,
            ) { Text(stringResource("common_cancel")) }
        },
    )
}
