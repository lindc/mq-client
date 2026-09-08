package com.mqclient.aipanel

import androidx.compose.foundation.background
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
import com.mqclient.core.AiConfig
import com.mqclient.core.AiProvider

/**
 * Header band: title + provider dropdown + close, plus a single info row
 * for the current selection context.
 *
 * The dropdown is a pure switcher — it lists every registered [AiProvider]
 * (active one prefixed with "✓ ") and nothing else. Configuration lives
 * in `~/.mqclient/config.json`; users edit it out-of-band.
 *
 * Glyphs are plain text (🤖 ✕ ▼) so the module doesn't pull in
 * material-icons-extended. Buttons stay as [TextButton] — lightweight,
 * no shadow / press-scale animation, so they don't fight the rest of the
 * title bar's chrome.
 */
@Composable
fun AiPanelHeader(
    config: AiConfig,
    providers: List<AiProvider>,
    context: AiContext?,
    onProviderSelected: (AiProvider) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = providers.firstOrNull { it.id == config.providerId } ?: providers.firstOrNull()
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
            Text("🤖", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                "AI 助手",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            ProviderDropdownButton(
                current = active,
                providers = providers,
                onProviderSelected = onProviderSelected,
            )
            TextButton(onClick = onDismiss) {
                Text("✕", style = MaterialTheme.typography.titleMedium)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ContextRow(context = context)
        }
    }
}

/**
 * The button shows the active provider's display name + a ▼ glyph. Click
 * opens a menu listing every registered provider (active marked with a
 * leading "✓ ") — pure switcher, no trailing entries.
 */
@Composable
private fun ProviderDropdownButton(
    current: AiProvider?,
    providers: List<AiProvider>,
    onProviderSelected: (AiProvider) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text(
            text = current?.displayName ?: "选择 AI",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(" ▼", style = MaterialTheme.typography.labelLarge)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        providers.forEach { provider ->
            val isActive = provider.id == current?.id
            DropdownMenuItem(
                text = {
                    Text(
                        if (isActive) "✓ ${provider.displayName}" else "   ${provider.displayName}",
                    )
                },
                onClick = {
                    expanded = false
                    if (!isActive) onProviderSelected(provider)
                },
            )
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