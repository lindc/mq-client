package com.mqclient.aipanel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
                    providers = state.providers,
                    activeProviderId = state.activeProvider?.id,
                    context = currentContext,
                    onProviderSelected = { picked -> state.updateConfig { it.copy(activeProviderId = picked.id) } },
                    onDismiss = onDismiss,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AiMessageList(
                    messages = state.messages,
                    onFeedback = { id, useful -> state.recordFeedback(id, useful) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AiInputArea(
                    isStreaming = state.isStreaming,
                    context = currentContext,
                    onSend = { text -> state.sendUserMessage(text, currentContext) },
                )
            }
        }
    }
}