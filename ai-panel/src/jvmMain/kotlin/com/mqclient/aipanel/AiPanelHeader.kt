package com.mqclient.aipanel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

/**
 * Header band: title + close, plus two info rows for service and context.
 * The service row is a click target that opens a dropdown to swap [Provider].
 *
 * Glyphs are plain text (🤖 / ✕ / ▼) so the module doesn't pull in
 * material-icons-extended. The settings and close buttons both live inside
 * the same Row at the trailing edge so they line up and their hit areas
 * don't overlap.
 */
@Composable
fun AiPanelHeader(
    settings: AiSettings,
    context: AiContext?,
    onProviderSelected: (Provider) -> Unit,
    onOpenSettings: () -> Unit,
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
            Text(
                "🤖",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "AI 助手",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) {
                Text("⚙", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onDismiss) {
                Text("✕", style = MaterialTheme.typography.titleMedium)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ServiceRow(settings = settings, onProviderSelected = onProviderSelected)
            Spacer(Modifier.height(4.dp))
            ContextRow(context = context)
        }
    }
}

@Composable
private fun ServiceRow(settings: AiSettings, onProviderSelected: (Provider) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "服务:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Box {
            Row(
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${settings.provider.displayName} (${settings.model})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    " ▼",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Provider.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("${p.displayName} — ${p.defaultModel}") },
                        onClick = {
                            expanded = false
                            onProviderSelected(p)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextRow(context: AiContext?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "上下文:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        if (context?.connectionName != null) {
            Text(
                "${context.connectionName} ✅",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
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
                "未选中连接",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}