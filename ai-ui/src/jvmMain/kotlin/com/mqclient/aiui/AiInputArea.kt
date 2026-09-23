package com.mqclient.aiui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
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
    onCancel: () -> Unit,
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
                if (isStreaming) {
                    // 流式中换成「停止」：没有它用户只能等回复自然结束 —— 长回复、
                    // 卡住的服务端都下不来。
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            val snapshot = text.trim()
                            if (snapshot.isNotEmpty()) {
                                onSend(snapshot)
                                text = ""
                            }
                        },
                        enabled = text.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = null,
                            tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 附件状态图标替代原字符串里的 📎 前缀（emoji 依赖系统字体）。
            Icon(
                imageVector = if (context?.connectionName != null) Icons.Default.Link else Icons.Default.LinkOff,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
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
}