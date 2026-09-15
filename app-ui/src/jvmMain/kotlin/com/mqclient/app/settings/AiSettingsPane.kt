package com.mqclient.app.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mqclient.ai.AiAdapterRegistry
import com.mqclient.ai.AiProvider
import com.mqclient.core.AiConfig
import com.mqclient.core.AiConnection
import com.mqclient.openai.ui.ModelComboBox
import com.mqclient.openai.ui.openAiIcon
import com.mqclient.uicommon.LabeledField
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.SettingsDropdownRow
import com.mqclient.uicommon.i18n.stringResource
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * AI settings tab（Provider + Connection 模型）：连接选择器、「新建连接」、历史条数、
 * 附加上下文开关，以及**当前连接**的配置卡。
 *
 * 编辑风格与设置对话框一致：每次修改都把新的 [AiConnection] 经 [onChange] 写回草稿，
 * 落盘由对话框底部的「保存」统一决定（取消即丢弃）。
 */
@Composable
internal fun AiSettingsPane(
    value: AiConfig,
    onChange: (AiConfig) -> Unit,
) {
    val providers = remember { AiAdapterRegistry.providers() }
    val activeConnection = value.connections.firstOrNull { it.id == value.activeConnectionId }
        ?: value.connections.firstOrNull()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource("settings_tab_ai"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        ConnectionPickerRow(
            connections = value.connections,
            activeId = activeConnection?.id,
            onPick = { id -> onChange(value.copy(activeConnectionId = id)) },
            onCreate = {
                // 新连接的默认值取第一个内置 Provider（注册表初始化即有，拿不到就留空由用户填）。
                val provider = providers.firstOrNull()
                val created = AiConnection(
                    id = UUID.randomUUID().toString(),
                    name = "连接 ${value.connections.size + 1}",
                    providerId = provider?.id.orEmpty(),
                    baseUrl = provider?.defaultBaseUrl.orEmpty(),
                    model = provider?.defaultModels?.firstOrNull().orEmpty(),
                )
                onChange(value.copy(connections = value.connections + created, activeConnectionId = created.id))
            },
        )
        Spacer(Modifier.height(16.dp))

        NumberRow(
            label = stringResource("ai_history_limit_label"),
            suffix = stringResource("unit_items"),
            value = value.historyLimit,
            min = 10,
            max = 500,
            onChange = { onChange(value.copy(historyLimit = it)) },
        )
        Spacer(Modifier.height(12.dp))

        ToggleRow(
            label = stringResource("ai_attach_context"),
            description = stringResource("ai_attach_context_desc"),
            checked = value.autoAttachContext,
            onCheckedChange = { onChange(value.copy(autoAttachContext = it)) },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource("ai_attach_off_note"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )

        if (activeConnection != null) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 目前只有 openai-compatible 一个 provider，logo 直接用它的；
                // 将来多 provider 时这里按 providerId 分发（谁的品牌归谁的 *-ui 模块）。
                Image(
                    painter = openAiIcon(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource("ai_connection_card_prefix") + " ${activeConnection.name}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            ConnectionCard(
                connection = activeConnection,
                providers = providers,
                onChange = { updated ->
                    onChange(
                        value.copy(
                            connections = value.connections.map { if (it.id == updated.id) updated else it },
                        ),
                    )
                },
                onDelete = {
                    onChange(
                        value.copy(
                            connections = value.connections.filterNot { it.id == activeConnection.id },
                            // 刚删掉的连接不该继续当激活项。
                            activeConnectionId = "",
                        ),
                    )
                },
            )
        } else {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource("ai_no_connections"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ----------------------------- Connection picker ----------------------------- */

@Composable
private fun ConnectionPickerRow(
    connections: List<AiConnection>,
    activeId: String?,
    onPick: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val currentLabel = connections.firstOrNull { it.id == activeId }?.name
        ?: if (connections.isEmpty()) stringResource("ai_no_connections") else stringResource("ai_not_selected")
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsDropdownRow(
            label = stringResource("ai_connection_label"),
            currentLabel = currentLabel,
            options = connections,
            optionLabel = { it.name },
            onPick = { onPick(it.id) },
        )
        Spacer(Modifier.height(8.dp))
        PressableButton(
            onClick = onCreate,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) { Text(stringResource("ai_connection_new")) }
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

/* ----------------------------- Connection card ----------------------------- */

/**
 * 当前连接的配置卡。
 *
 * 输入框状态按连接 id 建键（和旧 provider 卡同一理由）：每次敲键都会把新值经
 * [onChange] 上抛，拿「值」当键等于每键重建输入状态（IME 合成被打断）；按 id 建键则
 * 切换连接自然重建，卡内编辑也不会被外部重组冲掉。
 */
@Composable
private fun ConnectionCard(
    connection: AiConnection,
    providers: List<AiProvider>,
    onChange: (AiConnection) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(connection.id) { mutableStateOf(connection.name) }
    var providerId by remember(connection.id) { mutableStateOf(connection.providerId) }
    var baseUrl by remember(connection.id) { mutableStateOf(connection.baseUrl) }
    var token by remember(connection.id) { mutableStateOf(connection.token) }
    var model by remember(connection.id) { mutableStateOf(connection.model) }
    var prompt by remember(connection.id) { mutableStateOf(connection.systemPrompt) }
    var enabled by remember(connection.id) { mutableStateOf(connection.enabled) }
    // API key 默认遮蔽；眼睛按钮切换明文，方便粘贴 / 核对。
    var tokenVisible by remember { mutableStateOf(false) }
    var testing by remember(connection.id) { mutableStateOf(false) }
    var testResult by remember(connection.id) { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    fun flush() {
        onChange(
            connection.copy(
                name = name,
                providerId = providerId,
                baseUrl = baseUrl,
                token = token,
                model = model,
                systemPrompt = prompt,
                enabled = enabled,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        LabeledField(stringResource("conn_name_label")) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    flush()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        Spacer(Modifier.height(8.dp))

        SettingsDropdownRow(
            label = stringResource("ai_field_provider"),
            currentLabel = providers.firstOrNull { it.id == providerId }?.name ?: providerId.ifBlank { "—" },
            options = providers,
            optionLabel = { it.name },
            onPick = { picked ->
                providerId = picked.id
                // 切 Provider 时把没填过的默认值补上；用户填过的值不动。
                if (baseUrl.isBlank()) baseUrl = picked.defaultBaseUrl
                if (model.isBlank()) model = picked.defaultModels.firstOrNull().orEmpty()
                flush()
            },
        )
        Spacer(Modifier.height(8.dp))

        LabeledField(stringResource("ai_field_url")) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    flush()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(providers.firstOrNull { it.id == providerId }?.defaultBaseUrl.orEmpty())
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        LabeledField(stringResource("ai_field_api_key")) {
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    flush()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                // 空字段也给个圆点提示「这里是密钥」；一开始输入就消失。
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

        LabeledField(stringResource("ai_field_model")) {
            ModelComboBox(
                provider = providers.firstOrNull { it.id == providerId },
                model = model,
                onChange = {
                    model = it
                    flush()
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        LabeledField(stringResource("ai_field_system_prompt")) {
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
                placeholder = { Text(stringResource("ai_system_prompt_placeholder")) },
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    enabled = !enabled
                    flush()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource("ai_field_enabled"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    flush()
                },
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            PressableButton(
                onClick = {
                    // 用输入框里的当前值发起测试（可能还没 flush 回上层草稿）。
                    val snapshot = connection.copy(
                        name = name,
                        providerId = providerId,
                        baseUrl = baseUrl,
                        token = token,
                        model = model,
                        systemPrompt = prompt,
                        enabled = enabled,
                    )
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = runCatching {
                            AiAdapterRegistry.adapter(snapshot.providerId)?.testConnection(snapshot) ?: false
                        }.getOrDefault(false)
                        testing = false
                    }
                },
                enabled = !testing,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(if (testing) stringResource("ai_testing") else stringResource("conn_test_connection"))
            }
            Spacer(Modifier.width(8.dp))
            testResult?.let { ok ->
                Text(
                    text = if (ok) stringResource("ai_test_ok") else stringResource("ai_test_fail"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.weight(1f))
            PressableButton(
                onClick = onDelete,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) { Text(stringResource("common_delete")) }
        }
    }
}
