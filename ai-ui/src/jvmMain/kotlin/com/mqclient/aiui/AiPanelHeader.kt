package com.mqclient.aiui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mqclient.core.AiConnection
import com.mqclient.uicommon.i18n.stringResource

/**
 * Header band: title + provider dropdown + close, plus a single info row
 * for the current selection context.
 *
 * The dropdown lists the enabled AI connections
 * ([com.mqclient.core.AiConfig.connections]) — pure switcher, no trailing
 * entries. Selecting one writes its id back to
 * [com.mqclient.core.AiConfig.activeConnectionId].
 *
 * Icons are Material Icons (extended); the module now pulls that dependency —
 * emoji glyphs depend on the system font and break on minimal Linux installs.
 * Buttons stay as [TextButton] — lightweight,
 * no shadow / press-scale animation, so they don't fight the rest of the
 * title bar's chrome.
 */
@Composable
fun AiPanelHeader(
    connections: List<AiConnection>,
    activeConnectionId: String?,
    context: AiContext?,
    onConnectionSelected: (AiConnection) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource("ai_panel_title"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            ConnectionDropdownButton(
                connections = connections,
                activeConnectionId = activeConnectionId,
                onConnectionSelected = onConnectionSelected,
            )
            TextButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ContextRow(context = context)
        }
    }
}

/**
 * The button shows the active connection's name + a ▼ glyph. Click opens a
 * menu listing every enabled connection (active marked with a leading "✓ ")
 * — pure switcher, no trailing entries.
 */
@Composable
private fun ConnectionDropdownButton(
    connections: List<AiConnection>,
    activeConnectionId: String?,
    onConnectionSelected: (AiConnection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val activeLabel = connections.firstOrNull { it.id == activeConnectionId }?.name
        ?: if (connections.isEmpty()) stringResource("ai_no_connections") else stringResource("ai_connection_pick")
    TextButton(onClick = { expanded = true }) {
        Text(activeLabel, style = MaterialTheme.typography.labelLarge)
        Text(" ▼", style = MaterialTheme.typography.labelLarge)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        connections.forEach { connection ->
            val isActive = connection.id == activeConnectionId
            DropdownMenuItem(
                text = { Text(connection.name) },
                // active 项加 Check 图标；lambda 恒给（内容可为空），保证各行缩进一致。
                leadingIcon = {
                    if (isActive) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                },
                onClick = {
                    expanded = false
                    if (!isActive) onConnectionSelected(connection)
                },
            )
        }
    }
}

@Composable
private fun ContextRow(context: AiContext?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource("ai_context_label"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        if (context?.connectionName != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.connectionName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            context.objectType?.let { type ->
                Spacer(Modifier.width(6.dp))
                Text(
                    "· $type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                stringResource("ai_context_none"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}