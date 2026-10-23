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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import com.mqclient.core.ModuleRegistry
import com.mqclient.aiui.AiSslHint
import com.mqclient.openai.ui.ModelComboBox
import com.mqclient.openai.ui.openAiIcon
import com.mqclient.uicommon.LabeledField
import com.mqclient.uicommon.DangerButton
import com.mqclient.uicommon.SettingsDropdownRow
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.launch

/**
 * AI settings tab（Provider + Connection 模型）：连接选择器、历史条数、
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
                // 按 providerId 从 ModuleIcon SPI 取品牌图标（anthropic-ui 等注册即生效）；
                // provider 没有 ui 模块时回落 openai 的 logo（保持既有展示）。
                Image(
                    painter =
                        ModuleRegistry.icons.firstOrNull { it.serviceId == activeConnection.providerId }
                            ?.painter() ?: openAiIcon(),
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
) {
    val currentLabel = connections.firstOrNull { it.id == activeId }?.name
        ?: if (connections.isEmpty()) stringResource("ai_no_connections") else stringResource("ai_not_selected")
    // 新建连接统一走主菜单的「新建连接」向导（SPI 表单、落盘后重载），这里只负责选择。
    SettingsDropdownRow(
        label = stringResource("ai_connection_label"),
        currentLabel = currentLabel,
        options = connections,
        optionLabel = { it.name },
        onPick = { onPick(it.id) },
    )
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
    var ignoreSsl by remember(connection.id) { mutableStateOf(connection.ignoreSsl) }
    // API key 默认遮蔽；眼睛按钮切换明文，方便粘贴 / 核对。
    var tokenVisible by remember { mutableStateOf(false) }
    var testing by remember(connection.id) { mutableStateOf(false) }
    // null = 未测过 / 测试中；true = 成功；失败存可读原因（SSL 类经 AiSslHint 翻译）。
    var testOutcome by remember(connection.id) { mutableStateOf<TestOutcome?>(null) }
    val scope = rememberCoroutineScope()

    /** 测试按钮的输入快照（可能还没 flush 回上层草稿）。 */
    fun snapshot(): AiConnection = connection.copy(
        name = name,
        providerId = providerId,
        baseUrl = baseUrl,
        token = token,
        model = model,
        systemPrompt = prompt,
        enabled = enabled,
        ignoreSsl = ignoreSsl,
    )

    fun flush() {
        onChange(snapshot())
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
                        // Material 图标替代 emoji（🙈/👁）：Linux 无 Emoji 字体也能正常显示。
                        Icon(
                            imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
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

        // 忽略 SSL 证书校验（信任自签名证书）：per-connection 开关，只影响本连接
        // 走的 HTTP 客户端（trust-all），默认关 —— 严格校验。
        ToggleRow(
            label = stringResource("ai_field_ignore_ssl"),
            description = stringResource("ai_field_ignore_ssl_desc"),
            checked = ignoreSsl,
            onCheckedChange = {
                ignoreSsl = it
                flush()
            },
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
                onClick = {
                    testing = true
                    testOutcome = null
                    scope.launch {
                        // 异常经 AiSslHint 翻译（自签名证书 → 开启忽略 SSL 的指引；
                        // testQuietly 对 SSL 类失败会重抛，其余失败仍返回 false）。
                        val outcome = runCatching {
                            val adapter = AiAdapterRegistry.adapter(snapshot().providerId)
                            adapter != null && adapter.testConnection(snapshot())
                        }
                        testOutcome = when {
                            outcome.getOrDefault(false) -> TestOutcome.Success
                            else -> TestOutcome.Failed(
                                reason = outcome.exceptionOrNull()
                                    ?.let { AiSslHint.displayMessage(it) },
                            )
                        }
                        testing = false
                    }
                },
                enabled = !testing,
            ) {
                Text(if (testing) stringResource("ai_testing") else stringResource("conn_test_connection"))
            }
            Spacer(Modifier.width(8.dp))
            testOutcome?.let { outcome ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (outcome) {
                        TestOutcome.Success -> {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource("ai_test_ok"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        is TestOutcome.Failed -> {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = outcome.reason
                                    ?: stringResource("ai_test_fail"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            DangerButton(
                onClick = onDelete,
            ) { Text(stringResource("common_delete")) }
        }
    }
}

/** 测试连接的展示结果：成功 / 失败（带可读原因；null = 走通用「详情见日志」文案）。 */
private sealed interface TestOutcome {
    data object Success : TestOutcome
    data class Failed(val reason: String?) : TestOutcome
}
