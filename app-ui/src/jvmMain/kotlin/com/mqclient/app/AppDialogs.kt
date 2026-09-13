package com.mqclient.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.AppError
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.stringResource

/**
 * Application-level error dialog. Its data source is [CoreModule.lastError] — a state,
 * not an event, so errors raised during startup (before the UI subscribes) still show up.
 */
@Composable
internal fun ErrorDialog(error: AppError, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PressableButton(onClick = onDismiss) { Text(stringResource("common_close")) }
        },
        title = { Text(stringResource("error_dialog_title")) },
        text = {
            Column {
                Text(error.message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error.source + (error.cause?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * 新建连接对话框：功能还没实现，所以先把话说清楚 —— 空窗口会让人以为是界面坏了。
 * TODO 接上连接表单（写入 `config.connections` 并广播 `core.connection.added`）。
 */
@Composable
internal fun NewConnectionDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PressableButton(onClick = onDismiss) { Text(stringResource("common_close")) }
        },
        title = { Text(stringResource("menu_new_connection")) },
        text = { Text(stringResource("new_connection_todo")) },
    )
}
