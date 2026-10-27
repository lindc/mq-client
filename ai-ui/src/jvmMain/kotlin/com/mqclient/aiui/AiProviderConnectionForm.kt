package com.mqclient.aiui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
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
import com.mqclient.ai.AiAdapterRegistry
import com.mqclient.aiservice.AiConnectionRepository
import com.mqclient.core.AiConnection
import com.mqclient.core.ConnectionFormState
import com.mqclient.core.ConnectionFormUi
import com.mqclient.core.newConnectionId
import com.mqclient.uicommon.LabeledField
import com.mqclient.uicommon.SettingsDropdownRow
import com.mqclient.uicommon.i18n.stringResource

/**
 * AI Provider 的「新建连接」表单（`ConnectionFormUi` SPI 实现的**共享基类**）。
 *
 * 此前 `OpenAiConnectionForm` 与 `AnthropicConnectionForm` 是两份逐字副本
 * （466 行，diff 下来只有「预选哪个 provider」这一处不同），而且为了复用
 * `ModelComboBox`，`anthropic-ui` 还得 `implementation(projects.openaiUi)` ——
 * 两个平级 provider 产生了横向依赖，「删掉一个 provider 不影响其余」就不成立了。
 *
 * 现在表单只有一份：子类只声明 [serviceId] 与 [presetProviderId]。
 *
 * 字段与提交语义：
 *  - Provider 下拉是**全量列表**（在任何一个 AI 表单里都能切换 provider 类型）；
 *  - 落盘成功返回 `true`（宿主关窗），失败把错误留在面板内并返回 `false`；
 *  - 成功后顺手把新连接设为激活（与设置页行为一致），激活失败不影响「已创建」。
 *
 * @param presetProviderId 进入表单时预选哪个 provider（候选的 baseUrl / 默认模型也跟着
 *   取该 provider 的预置）。为 null 或查不到时退化为列表第一项。
 */
abstract class AiProviderConnectionForm(
    private val presetProviderId: String?,
) : ConnectionFormUi {

    @Composable
    override fun rememberFormState(defaultName: String): ConnectionFormState {
        val providers = remember { AiAdapterRegistry.providers() }
        // 预选 provider：id 对得上就用它，否则退第一条（注册表为空时全程为 null）。
        val defaultProvider = remember(providers) {
            presetProviderId?.let { id -> providers.firstOrNull { it.id == id } }
                ?: providers.firstOrNull()
        }
        var saveError by remember { mutableStateOf<String?>(null) }
        var name by remember { mutableStateOf(defaultName) }
        var providerId by remember { mutableStateOf(defaultProvider?.id.orEmpty()) }
        var baseUrl by remember { mutableStateOf(defaultProvider?.defaultBaseUrl.orEmpty()) }
        var token by remember { mutableStateOf("") }
        var tokenVisible by remember { mutableStateOf(false) }
        var model by remember { mutableStateOf(defaultProvider?.defaultModels?.firstOrNull().orEmpty()) }
        var ignoreSsl by remember { mutableStateOf(false) }

        return object : ConnectionFormState {
            override fun reset(defaultName: String) {
                name = defaultName
                providerId = defaultProvider?.id.orEmpty()
                baseUrl = defaultProvider?.defaultBaseUrl.orEmpty()
                token = ""
                model = defaultProvider?.defaultModels?.firstOrNull().orEmpty()
                ignoreSsl = false
                saveError = null
            }

            override fun submit(): Boolean {
                val connection = AiConnection(
                    id = newConnectionId(),
                    name = name.ifBlank { defaultName },
                    providerId = providerId,
                    baseUrl = baseUrl.trim(),
                    token = token,
                    model = model.trim(),
                    ignoreSsl = ignoreSsl,
                )
                val created = AiConnectionRepository.create(connection)
                val failure = created.exceptionOrNull()
                if (failure != null) {
                    saveError = failure.message ?: failure::class.simpleName
                    return false
                }
                AiConnectionRepository.setActive(connection.id)
                return true
            }

            @Composable
            override fun Content() {
                Column(
                    // 填满宿主给的固定空间（随窗口缩放），内容超出时内部滚动。
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                ) {
                    LabeledField(stringResource("conn_name_label")) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    SettingsDropdownRow(
                        label = stringResource("ai_field_provider"),
                        currentLabel =
                            providers.firstOrNull { it.id == providerId }?.name
                                ?: providerId.ifBlank { "—" },
                        options = providers,
                        optionLabel = { it.name },
                        onPick = { picked ->
                            providerId = picked.id
                            // 切 Provider 时把没填过的默认值补上；用户填过的值不动。
                            if (baseUrl.isBlank()) baseUrl = picked.defaultBaseUrl
                            if (model.isBlank()) {
                                model = picked.defaultModels.firstOrNull().orEmpty()
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))

                    LabeledField(stringResource("ai_field_url")) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    providers.firstOrNull { it.id == providerId }
                                        ?.defaultBaseUrl.orEmpty(),
                                )
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    LabeledField(stringResource("ai_field_api_key")) {
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation =
                                if (tokenVisible) VisualTransformation.None
                                else PasswordVisualTransformation(),
                            // 空字段也给个圆点提示「这里是密钥」；一开始输入就消失。
                            placeholder = { Text("••••••••") },
                            trailingIcon = {
                                Box(
                                    modifier =
                                        Modifier
                                            .clickable { tokenVisible = !tokenVisible }
                                            .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // Material 图标替代 emoji：Linux 无 Emoji 字体也能正常显示。
                                    Icon(
                                        imageVector =
                                            if (tokenVisible) Icons.Default.VisibilityOff
                                            else Icons.Default.Visibility,
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
                            onChange = { model = it },
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    // 忽略 SSL 证书校验（信任自签名证书）：per-connection，默认关。
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { ignoreSsl = !ignoreSsl }
                                .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource("ai_field_ignore_ssl"), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource("ai_field_ignore_ssl_desc"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = ignoreSsl,
                            onCheckedChange = { ignoreSsl = it },
                        )
                    }

                    if (saveError != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = saveError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
