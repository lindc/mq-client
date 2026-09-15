package com.mqclient.openai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.mqclient.ai.AiProviders
import com.mqclient.core.AiConnection
import com.mqclient.core.AiConnectionRepository
import com.mqclient.core.ConnectionFormState
import com.mqclient.core.ConnectionFormUi
import com.mqclient.uicommon.LabeledField
import com.mqclient.uicommon.SettingsDropdownRow
import com.mqclient.uicommon.i18n.stringResource
import java.util.UUID

/**
 * openai 兼容服务的「新建连接」表单（`ConnectionFormUi` SPI 实现）：
 * 字段、校验、错误展示、落盘全部归本模块 —— 宿主对话框只给容器和「完成」按钮。
 *
 * 提交语义（见 [ConnectionFormState.submit]）：落盘成功返回 `true`（宿主关窗），
 * 失败把错误留在面板内展示并返回 `false`（对话框保持打开）。成功后顺手把新连接
 * 设为激活（与设置页行为一致），激活失败不影响「已创建」的事实。
 */
class OpenAiConnectionForm : ConnectionFormUi {

    override val serviceId = AiProviders.OPENAI_COMPATIBLE.id

    @Composable
    override fun rememberFormState(defaultName: String): ConnectionFormState {
        val providers = remember { AiAdapterRegistry.providers() }
        var saveError by remember { mutableStateOf<String?>(null) }
        var name by remember { mutableStateOf(defaultName) }
        var providerId by remember { mutableStateOf(providers.firstOrNull()?.id.orEmpty()) }
        var baseUrl by remember { mutableStateOf(providers.firstOrNull()?.defaultBaseUrl.orEmpty()) }
        var token by remember { mutableStateOf("") }
        var tokenVisible by remember { mutableStateOf(false) }
        var model by remember { mutableStateOf(providers.firstOrNull()?.defaultModels?.firstOrNull().orEmpty()) }

        return object : ConnectionFormState {
            override fun reset(defaultName: String) {
                name = defaultName
                providerId = providers.firstOrNull()?.id.orEmpty()
                baseUrl = providers.firstOrNull()?.defaultBaseUrl.orEmpty()
                token = ""
                model = providers.firstOrNull()?.defaultModels?.firstOrNull().orEmpty()
                saveError = null
            }

            override fun submit(): Boolean {
                val connection = AiConnection(
                    id = UUID.randomUUID().toString(),
                    name = name.ifBlank { defaultName },
                    providerId = providerId,
                    baseUrl = baseUrl.trim(),
                    token = token,
                    model = model.trim(),
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
                            onChange = { model = it },
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
