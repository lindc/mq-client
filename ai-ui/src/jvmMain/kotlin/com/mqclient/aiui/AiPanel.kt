package com.mqclient.aiui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Right-side AI panel. Composed of three stacked bands: header (title +
 * service-dropdown + close), scrollable message list, and input area.
 *
 * Width is fixed at 360 dp so the main content never gets squeezed off
 * screen by an over-eager side panel.
 */
@Composable
fun AiPanel(
    state: AiPanelState,
    currentContext: AiContext?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.width(360.dp).fillMaxHeight()) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(
            modifier = Modifier.fillMaxHeight().fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AiPanelHeader(
                    connections = state.connections,
                    activeConnectionId = state.activeConnection?.id,
                    context = currentContext,
                    onConnectionSelected = { picked -> state.updateConfig { it.copy(activeConnectionId = picked.id) } },
                    onDismiss = onDismiss,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AiMessageList(
                    messages = state.messages,
                    streamingMessageId = state.streamingMessageId,
                    onFeedback = { id, useful -> state.recordFeedback(id, useful) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // 失败提示条：请求异常必须看得见（未捕获过的话面板会直接无响应）。
                state.panelError?.let { message ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { state.dismissError() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AiInputArea(
                    isStreaming = state.isStreaming,
                    context = currentContext,
                    onSend = { text -> state.sendUserMessage(text, currentContext) },
                    onCancel = { state.cancelStreaming() },
                )
            }
        }
    }
}