package com.mqclient.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mqclient.core.AiConfig
import com.mqclient.core.AiProvider
import com.mqclient.core.AiProviderConfig
import com.mqclient.core.AiProviderRegistry
import java.util.Base64

/**
 * AI settings tab — active provider picker, history limit, auto-attach
 * toggle, and the per-provider config card for the **currently active**
 * provider only.
 *
 * Until the user picks a service in the top dropdown, the config card is
 * not rendered at all — there's nothing meaningful to configure without a
 * target provider.
 *
 * For each provider the user can override URL, API key, model and system
 * prompt; the app-level [AiConfig.providerConfigs] is sparse — missing
 * entries render with the provider's built-in defaults.
 */
@Composable
internal fun AiSettingsPane(
    value: AiConfig,
    onChange: (AiConfig) -> Unit,
) {
    val registeredProviders = remember { AiProviderRegistry.all }
    // Build a quick id → override map; the single card looks itself up by
    // id later (other ids never get rendered).
    val overridesById = remember(value.providerConfigs) {
        value.providerConfigs.associateBy { it.providerId }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("AI", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        ActiveProviderRow(
            providers = registeredProviders,
            activeId = value.activeProviderId,
            onChange = { onChange(value.copy(activeProviderId = it)) },
        )
        Spacer(Modifier.height(16.dp))

        NumberRow(
            label = "历史记录条数",
            suffix = "条",
            value = value.historyLimit,
            min = 10,
            max = 500,
            onChange = { onChange(value.copy(historyLimit = it)) },
        )
        Spacer(Modifier.height(12.dp))

        ToggleRow(
            label = "附加上下文",
            description = "自动附加当前连接/队列信息到提问",
            checked = value.autoAttachContext,
            onCheckedChange = { onChange(value.copy(autoAttachContext = it)) },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "关闭后 AI 将不会感知当前选中的资源",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )

        // Only the explicitly-chosen provider gets a card. A blank id stays
        // blank — we deliberately don't fall back to "first registered",
        // because that would silently show a config the user never asked for.
        val activeProvider = value.activeProviderId
            .takeIf { it.isNotBlank() }
            ?.let { id -> registeredProviders.firstOrNull { it.id == id } }

        if (activeProvider != null) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "当前服务配置: ${activeProvider.displayName}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            ProviderCard(
                provider = activeProvider,
                override = overridesById[activeProvider.id],
                onChange = { newOverride ->
                    val newList = value.providerConfigs
                        .filterNot { it.providerId == activeProvider.id } + newOverride
                    onChange(value.copy(providerConfigs = newList))
                },
            )
        } else if (registeredProviders.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "请先在上方选择一个 AI 服务。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "未注册任何 AI 插件。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ----------------------------- Active provider ----------------------------- */

@Composable
private fun ActiveProviderRow(
    providers: List<AiProvider>,
    activeId: String,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "默认 AI 服务",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        val currentLabel = if (activeId.isBlank()) {
            "(未选择)"
        } else {
            providers.firstOrNull { it.id == activeId }?.displayName ?: activeId
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(currentLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("▼", style = MaterialTheme.typography.bodyMedium)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            providers.forEach { p ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(p.displayName) },
                    onClick = {
                        expanded = false
                        onChange(p.id)
                    },
                )
            }
        }
    }
}

/* -------------------------------- Toggle row -------------------------------- */

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/* ----------------------------- Provider card ----------------------------- */

/**
 * The config card for the single active provider. Shows the provider's
 * built-in defaults as placeholder text and lets the user fill in their own
 * values; [override] is whatever's already persisted for this provider
 * (null when the user hasn't customised it yet).
 */
@Composable
private fun ProviderCard(
    provider: AiProvider,
    override: AiProviderConfig?,
    onChange: (AiProviderConfig) -> Unit,
) {
    var url by remember(override) { mutableStateOf(override?.providerUrl ?: "") }
    var token by remember(override) { mutableStateOf(override?.accessToken ?: "") }
    var model by remember(override) {
        mutableStateOf(override?.model?.takeIf { it.isNotBlank() } ?: provider.defaultModel)
    }
    var prompt by remember(override) {
        mutableStateOf(decodePrompt(override?.systemPromptBase64))
    }
    // API key is masked by default; the eye toggle reveals it for pasting
    // / proof-reading.
    var tokenVisible by remember { mutableStateOf(false) }

    // Every keystroke writes a new [AiProviderConfig] back into the list.
    fun flush() {
        onChange(
            AiProviderConfig(
                providerId = provider.id,
                providerUrl = url,
                accessToken = token,
                model = model,
                systemPromptBase64 = encodePrompt(prompt),
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        LabeledField("服务 URL") {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    flush()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(provider.defaultServerUrl) },
            )
        }
        Spacer(Modifier.height(8.dp))

        LabeledField("API Key") {
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    flush()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                // Masked by default; the trailing eye flips to plain text.
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                // Dotted hint so an empty field still reads as "secret goes
                // here" — it disappears as soon as the user types.
                placeholder = { Text("••••••••") },
                trailingIcon = {
                    Box(
                        modifier = Modifier
                            .clickable { tokenVisible = !tokenVisible }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (tokenVisible) "🙈" else "👁",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        LabeledField("模型") {
            ModelComboBox(
                provider = provider,
                model = model,
                onChange = {
                    model = it
                    flush()
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        LabeledField("系统提示词") {
            OutlinedTextField(
                value = prompt,
                onValueChange = {
                    prompt = it
                    flush()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                singleLine = false,
                placeholder = { Text("可选，用于设定 AI 的角色和行为") },
            )
        }
    }
}

/**
 * Editable model combo box: the user can type straight into the field, or
 * open the menu to pick from the provider's curated models.
 *
 * Options are the provider's [AiProvider.models] plus whatever custom value
 * is already saved, deduped — so a hand-typed model never disappears from
 * the list just because the provider doesn't ship it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModelComboBox(
    provider: AiProvider,
    model: String,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Dedupe: curated list first, then whatever custom value is already
    // saved — so a hand-typed model never disappears from the list just
    // because the provider doesn't ship it.
    val options = remember(provider, model) {
        (provider.models + model).filter { it.isNotBlank() }.distinct()
    }
    // Filter as the user types, but always keep the in-progress text as an
    // entry so a fully custom name stays reachable.
    val visibleOptions = remember(options, model) {
        if (model.isBlank()) options
        else {
            val matches = options.filter { it.contains(model, ignoreCase = true) }
            if (matches.any { it.equals(model, ignoreCase = true) }) matches else matches + model
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = model,
            onValueChange = {
                onChange(it)
                expanded = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            singleLine = true,
            placeholder = { Text(provider.defaultModel) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            visibleOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/* -------------------------------- helpers -------------------------------- */

/**
 * Decode a stored base64 prompt back to a UTF-8 String. Base64 here is a
 * storage obfuscation, not security — bytes round-trip as UTF-8 so CJK,
 * quotes and newlines survive the JSON file unmolested.
 */
private fun decodePrompt(b64: String?): String =
    try {
        if (b64.isNullOrBlank()) ""
        else String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
    } catch (_: Throwable) {
        ""
    }

/** Encode any user-entered prompt text into the Base64 storage form. */
private fun encodePrompt(plain: String): String =
    Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))