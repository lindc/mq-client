package com.mqclient.aipanel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource

/**
 * Bottom band: multi-line prompt + send button + a small context-attachment
 * hint that mirrors what the header shows. Enter sends; Shift+Enter inserts
 * a real newline.
 */
@Composable
fun AiInputArea(
    isStreaming: Boolean,
    context: AiContext?,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 120.dp)
                    .onPreviewKeyEvent { event ->
                        // Plain Enter submits; Shift+Enter (and friends) keep
                        // their default behaviour so the user can insert
                        // multi-line prompts.
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Enter &&
                            !event.isShiftPressed
                        ) {
                            val snapshot = text.trim()
                            if (snapshot.isNotEmpty() && !isStreaming) {
                                onSend(snapshot)
                                text = ""
                            }
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text(stringResource("ai_input_placeholder")) },
                enabled = !isStreaming,
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val snapshot = text.trim()
                        if (snapshot.isNotEmpty() && !isStreaming) {
                            onSend(snapshot)
                            text = ""
                        }
                    },
                ),
            )
            Spacer(Modifier.width(8.dp))
            Box {
                IconButton(
                    onClick = {
                        val snapshot = text.trim()
                        if (snapshot.isNotEmpty()) {
                            onSend(snapshot)
                            text = ""
                        }
                    },
                    enabled = !isStreaming && text.isNotBlank(),
                ) {
                    Text(
                        "➤",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (!isStreaming && text.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (context?.connectionName != null) {
                stringResource("ai_context_attached")
            } else {
                stringResource("ai_context_missing")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}