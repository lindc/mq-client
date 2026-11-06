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
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.PrimaryButton
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
            PrimaryButton(onClick = onDismiss) { Text(stringResource("common_close")) }
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
