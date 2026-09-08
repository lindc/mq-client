package com.mqclient.aipanel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mqclient.core.AiConfig
import com.mqclient.core.AiProvider
import com.mqclient.core.AiProviderRegistry
import com.mqclient.core.DEFAULT_SYSTEM_PROMPT
import com.mqclient.uicommon.PressableButton

/**
 * Modal settings dialog. Edits are kept in a local draft until "保存" so the
 * panel state only updates with a deliberate commit. Reset only clears the
 * chat history — settings are left alone.
 *
 * The dialog binds to [AiConfig] (the shared config type from app-core).
 * The system prompt field shows plaintext to the user but the storage form
 * is Base64 — encoding/decoding happens here so the panel state never sees
 * raw bytes.
 */
@Composable
fun AiSettingsDialog(
    initial: AiConfig,
    onDismiss: () -> Unit,
    onSave: (AiConfig) -> Unit,
    onClearHistory: () -> Unit,
) {
    // Decoded view of the prompt, plus everything else straight from config.
    var draftProviderId by remember { mutableStateOf(initial.providerId) }
    var draftAccessKey by remember { mutableStateOf(initial.accessKey) }
    var draftProviderUrl by remember { mutableStateOf(initial.providerUrl) }
    var draftModel by remember { mutableStateOf(initial.model) }
    var draftSystemPrompt by remember {
        mutableStateOf(
            try {
                if (initial.systemPromptBase64.isBlank()) DEFAULT_SYSTEM_PROMPT
                else String(java.util.Base64.getDecoder().decode(initial.systemPromptBase64), Charsets.UTF_8)
            } catch (_: Throwable) {
                DEFAULT_SYSTEM_PROMPT
            },
        )
    }
    var draftHistoryLimit by remember { mutableStateOf(initial.historyLimit.toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp).heightIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "AI 设置",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text("✕", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // Scrollable middle — the form can grow taller than the dialog's
                // height budget (especially once 系统提示词 is multi-line), and
                // we still want 重置对话 / 取消 / 保存 to stay visible at the
                // bottom. Header and footer are pinned outside this Column.
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    FieldLabel("服务提供商")
                    ProviderDropdown(
                        selected = AiProviderRegistry.findById(draftProviderId)
                            ?: AiProviderRegistry.all.firstOrNull(),
                        onSelected = { picked ->
                            draftProviderId = picked.id
                            draftModel = picked.defaultModel
                            draftProviderUrl = picked.defaultServerUrl
                        },
                    )
                    Spacer(Modifier.height(12.dp))

                    FieldLabel("Access Key")
                    OutlinedTextField(
                        value = draftAccessKey,
                        onValueChange = { draftAccessKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text("sk-...") },
                    )
                    Spacer(Modifier.height(12.dp))

                    FieldLabel("服务端 URL")
                    OutlinedTextField(
                        value = draftProviderUrl,
                        onValueChange = { draftProviderUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://...") },
                    )
                    Spacer(Modifier.height(12.dp))

                    FieldLabel("模型")
                    OutlinedTextField(
                        value = draftModel,
                        onValueChange = { draftModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))

                    FieldLabel("系统提示词")
                    OutlinedTextField(
                        value = draftSystemPrompt,
                        onValueChange = { draftSystemPrompt = it },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        singleLine = false,
                    )
                    Spacer(Modifier.height(12.dp))

                    FieldLabel("历史记录条数")
                    OutlinedTextField(
                        value = draftHistoryLimit,
                        onValueChange = { newValue ->
                            // Accept digits only; clamp to a sane range on commit.
                            if (newValue.all { it.isDigit() } && newValue.length <= 6) {
                                draftHistoryLimit = newValue
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        placeholder = { Text("200") },
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PressableButton(onClick = onClearHistory) { Text("重置对话") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PressableButton(
                            onClick = onDismiss,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) { Text("取消") }
                        PressableButton(
                            onClick = {
                                onSave(
                                    initial.copy(
                                        providerId = draftProviderId,
                                        accessKey = draftAccessKey,
                                        providerUrl = draftProviderUrl,
                                        model = draftModel,
                                        systemPromptBase64 = AiPanelState.encodePrompt(draftSystemPrompt),
                                        historyLimit = draftHistoryLimit.toIntOrNull()?.coerceIn(0, 999_999) ?: initial.historyLimit,
                                    ),
                                )
                            },
                        ) { Text("保存") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ProviderDropdown(selected: AiProvider?, onSelected: (AiProvider) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = selected ?: AiProviderRegistry.all.firstOrNull()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { expanded = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (current != null) "${current.displayName} — ${current.defaultModel}" else "选择 AI",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("▼", style = MaterialTheme.typography.bodyMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AiProviderRegistry.all.forEach { p ->
                DropdownMenuItem(
                    text = { Text("${p.displayName} — ${p.defaultModel}") },
                    onClick = {
                        expanded = false
                        onSelected(p)
                    },
                )
            }
        }
    }
}