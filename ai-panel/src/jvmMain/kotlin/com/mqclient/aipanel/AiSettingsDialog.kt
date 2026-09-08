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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mqclient.uicommon.PressableButton

/**
 * Modal settings dialog. Edits are kept in a local draft until "保存" so the
 * panel state only updates with a deliberate commit. Reset only clears the
 * chat history — settings are left alone.
 *
 * Uses plain text glyphs (✕ ▼) instead of Material icons to avoid pulling
 * in the icons-extended artifact.
 */
@Composable
fun AiSettingsDialog(
    initial: AiSettings,
    onDismiss: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onClearHistory: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }

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
                        selected = draft.provider,
                        onSelected = { picked ->
                            draft = draft.copy(provider = picked, model = picked.defaultModel, serverUrl = picked.defaultServerUrl)
                        },
                    )
                    Spacer(Modifier.height(12.dp))

                    FieldLabel("API Key")
                    OutlinedTextField(
                        value = draft.apiKey,
                        onValueChange = { draft = draft.copy(apiKey = it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text("sk-...") },
                    )
                    Spacer(Modifier.height(12.dp))

                    FieldLabel("服务端 URL")
                    OutlinedTextField(
                        value = draft.serverUrl,
                        onValueChange = { draft = draft.copy(serverUrl = it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://...") },
                    )
                    Spacer(Modifier.height(12.dp))

                    FieldLabel("模型")
                    OutlinedTextField(
                        value = draft.model,
                        onValueChange = { draft = draft.copy(model = it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))

                    FieldLabel("系统提示词")
                    OutlinedTextField(
                        value = draft.systemPrompt,
                        onValueChange = { draft = draft.copy(systemPrompt = it) },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        singleLine = false,
                    )
                    Spacer(Modifier.height(12.dp))

                    FieldLabel("上下文附加")
                    ContextToggle("自动附加当前连接信息", draft.attachConnection) {
                        draft = draft.copy(attachConnection = it)
                    }
                    ContextToggle("自动附加当前对象类型", draft.attachObjectType) {
                        draft = draft.copy(attachObjectType = it)
                    }
                    ContextToggle("自动附加当前消息内容", draft.attachMessages) {
                        draft = draft.copy(attachMessages = it)
                    }
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
                        PressableButton(onClick = { onSave(draft) }) { Text("保存") }
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
private fun ProviderDropdown(selected: Provider, onSelected: (Provider) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
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
                "${selected.displayName} — ${selected.defaultModel}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("▼", style = MaterialTheme.typography.bodyMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Provider.entries.forEach { p ->
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

@Composable
private fun ContextToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}