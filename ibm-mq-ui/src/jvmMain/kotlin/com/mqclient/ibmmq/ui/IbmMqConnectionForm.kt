package com.mqclient.ibmmq.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mqclient.core.ConnectionFormUi
import com.mqclient.ibmmq.ConnectionMode
import com.mqclient.uicommon.LabeledField
import com.mqclient.uicommon.SettingsDropdownRow
import com.mqclient.uicommon.i18n.stringResource

/**
 * IBM MQ 的「新建 / 编辑连接」表单（`ConnectionFormUi` SPI 实现，注册见
 * `META-INF/services/com.mqclient.core.ConnectionFormUi`）。
 *
 * 字段、校验、错误展示、保存前测试连接、落盘全部归本面板 —— 宿主对话框只给
 * 容器和 footer 按钮（「完成」即保存，Testing 时经 busy 契约禁用）。布局规则：
 * 全部字段上下布局（标签在上、输入框占满整行宽），避免左右并排在窄面板里被
 * 截断；表单内容超出一屏走内部滚动。
 *
 * 动态显示：BINDING 模式只留 连接模式 / 连接名称 / 队列管理器 / 扫描间隔
 * （网络、认证、SSL、连接超时全部隐藏）；SSL 开关打开时经 AnimatedVisibility
 * 平滑展开子表单（缩进一级）。
 *
 * 输入约束：加密套件只允许从下拉里挑（应用启动时经 SSLContext 查询一次，
 * 见 [CipherSuiteOptions]）；信任库 / 密钥库路径只读展示、经文件选择器填入
 * （见 [pickFile]），不允许手写。没有独立的「测试连接」按钮 —— 保存时自动
 * 测（见 [IbmMqConnectionFormState.submit]）。
 */
class IbmMqConnectionForm : ConnectionFormUi {

    override val serviceId = "ibm-mq"

    @Composable
    override fun rememberFormState(defaultName: String): IbmMqConnectionFormState {
        val scope = rememberCoroutineScope()
        return remember { IbmMqConnectionFormState(defaultName, scope) }
    }
}

/** 必填字段的标签：加 `*` 前缀。 */
@Composable
private fun requiredLabel(key: String): String = "* " + stringResource(key)

/**
 * 表单内容：全部布局与动态显示都在这里；状态与业务规则在
 * [IbmMqConnectionFormState]。
 */
@Composable
internal fun IbmMQConnectionFormContent(state: IbmMqConnectionFormState) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
    ) {
        SectionHeader(stringResource("ibm_mq_section_basic"))
        SettingsDropdownRow(
            label = stringResource("ibm_mq_field_mode"),
            currentLabel = modeLabel(state.mode),
            options = ConnectionMode.entries,
            optionLabel = ::modeLabel,
            onPick = { state.mode = it },
        )
        Spacer(Modifier.height(12.dp))
        TextRow(
            label = requiredLabel("conn_name_label"),
            value = state.name,
            onChange = { state.name = it },
        )
        Spacer(Modifier.height(12.dp))
        TextRow(
            label = requiredLabel("ibm_mq_field_queue_manager"),
            value = state.queueManager,
            onChange = { state.queueManager = it; state.clearErrors() },
            placeholder = "QMGR_NAME",
            errorMessage = state.errors.queueManager,
        )

        if (state.mode == ConnectionMode.CLIENT) {
            SectionDivider()
            SectionHeader(stringResource("ibm_mq_section_network"))
            TextRow(
                label = requiredLabel("ibm_mq_field_host"),
                value = state.host,
                onChange = { state.host = it; state.clearErrors() },
                placeholder = "192.168.1.100",
                errorMessage = state.errors.host,
            )
            Spacer(Modifier.height(12.dp))
            TextRow(
                label = requiredLabel("ibm_mq_field_port"),
                value = state.port,
                onChange = { state.port = it; state.clearErrors() },
                errorMessage = state.errors.port,
            )
            Spacer(Modifier.height(12.dp))
            TextRow(
                label = requiredLabel("ibm_mq_field_channel"),
                value = state.channel,
                onChange = { state.channel = it; state.clearErrors() },
                errorMessage = state.errors.channel,
            )
            Spacer(Modifier.height(12.dp))
            TextRow(
                label = stringResource("ibm_mq_field_ccsid"),
                value = state.ccsid,
                onChange = { state.ccsid = it; state.clearErrors() },
                errorMessage = state.errors.ccsid,
            )

            SectionDivider()
            SectionHeader(stringResource("ibm_mq_section_auth"))
            TextRow(
                label = stringResource("ibm_mq_field_username"),
                value = state.username,
                onChange = { state.username = it },
            )
            Spacer(Modifier.height(12.dp))
            PasswordRow(
                label = stringResource("ibm_mq_field_password"),
                value = state.password,
                onChange = { state.password = it; state.passwordChanged = true },
                showPlaceholder = state.originalPassword.isNotEmpty(),
            )

            SectionDivider()
            SectionHeader(stringResource("ibm_mq_section_ssl"))
            SwitchRow(
                label = stringResource("ibm_mq_field_ssl_enabled"),
                checked = state.sslEnabled,
                onChange = { state.sslEnabled = it },
            )
            // SSL 子表单：开关打开平滑展开，缩进一级与主字段区分。
            AnimatedVisibility(visible = state.sslEnabled) {
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    PathRow(
                        label = stringResource("ibm_mq_field_trust_store_path"),
                        value = state.trustStorePath,
                        onBrowse = state::pickTrustStore,
                    )
                    Spacer(Modifier.height(12.dp))
                    PasswordRow(
                        label = stringResource("ibm_mq_field_trust_store_password"),
                        value = state.trustStorePassword,
                        onChange = {
                            state.trustStorePassword = it
                            state.trustStorePasswordChanged = true
                        },
                        showPlaceholder = state.originalTrustStorePassword.isNotEmpty(),
                    )
                    Spacer(Modifier.height(12.dp))
                    PathRow(
                        label = stringResource("ibm_mq_field_key_store_path"),
                        value = state.keyStorePath,
                        onBrowse = state::pickKeyStore,
                    )
                    Spacer(Modifier.height(12.dp))
                    PasswordRow(
                        label = stringResource("ibm_mq_field_key_store_password"),
                        value = state.keyStorePassword,
                        onChange = {
                            state.keyStorePassword = it
                            state.keyStorePasswordChanged = true
                        },
                        showPlaceholder = state.originalKeyStorePassword.isNotEmpty(),
                    )
                    Spacer(Modifier.height(12.dp))
                    CipherSuiteRow(state)
                    Spacer(Modifier.height(12.dp))
                    TextRow(
                        label = stringResource("ibm_mq_field_peer_name"),
                        value = state.peerName,
                        onChange = { state.peerName = it },
                    )
                }
            }

            SectionDivider()
            SectionHeader(stringResource("ibm_mq_section_advanced"))
            TextRow(
                label = stringResource("ibm_mq_field_connection_timeout"),
                value = state.connectionTimeout,
                onChange = { state.connectionTimeout = it; state.clearErrors() },
                errorMessage = state.errors.connectionTimeout,
            )
        }

        // 扫描间隔：CLIENT / BINDING 都显示。
        SectionDivider()
        SectionHeader(stringResource("ibm_mq_section_scan"))
        TextRow(
            label = stringResource("ibm_mq_field_message_scan_interval"),
            value = state.messageScanInterval,
            onChange = { state.messageScanInterval = it; state.clearErrors() },
            errorMessage = state.errors.messageScanInterval,
        )
        Spacer(Modifier.height(12.dp))
        TextRow(
            label = stringResource("ibm_mq_field_object_scan_interval"),
            value = state.objectScanInterval,
            onChange = { state.objectScanInterval = it; state.clearErrors() },
            errorMessage = state.errors.objectScanInterval,
        )

        // 测试进行中的提示（保存按钮已由宿主按 busy 禁用）。
        if (state.saveState == SaveState.Testing) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource("ibm_mq_test_running"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 落盘失败原因（校验错误在各自字段下面；错误从不出面板边界）。
        state.saveError?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 测试失败后的「是否仍要保存」确认框：「保存」按测试时的配置快照落盘；
    // 「取消」只关本框，主对话框与表单原样保留。
    (state.saveState as? SaveState.Failed)?.let { failed ->
        SaveAnywayDialog(
            message = failed.message,
            onSave = state::confirmSave,
            onCancel = state::dismissFailed,
        )
    }
}

/** 加密套件下拉：只允许从列表里挑（首项空串 = 自动协商），不允许手写。 */
@Composable
private fun CipherSuiteRow(state: IbmMqConnectionFormState) {
    val suites = remember { CipherSuiteOptions.all }
    SettingsDropdownRow(
        label = stringResource("ibm_mq_field_cipher_suite"),
        currentLabel = state.cipherSuite.ifBlank { stringResource("ibm_mq_cipher_auto") },
        options = suites,
        optionLabel = { suite -> suite.ifBlank { stringResource("ibm_mq_cipher_auto") } },
        onPick = { state.cipherSuite = it },
    )
}

private fun modeLabel(mode: ConnectionMode): String =
    stringResource(
        when (mode) {
            ConnectionMode.CLIENT -> "ibm_mq_mode_client"
            ConnectionMode.BINDING -> "ibm_mq_mode_binding"
        },
    )

/** 分组之间的分隔线 + 间距。 */
@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
}

/** 分组标题（不做合成加粗，用主色区分 —— 全界面统一字体不加粗的约定）。 */
@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** 字段下方的红色错误小字；无错误时不占位。 */
@Composable
private fun FieldError(message: String?) {
    if (message != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 普通文本字段（标签在上、输入框占满整行宽）+ 字段下方错误提示。 */
@Composable
private fun TextRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    errorMessage: String? = null,
) {
    LabeledField(label) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = modifier.fillMaxWidth(),
            singleLine = true,
            isError = errorMessage != null,
            placeholder = { placeholder?.let { Text(it) } },
        )
    }
    FieldError(errorMessage)
}

/**
 * 密码字段：密文显示 + 右侧眼睛图标切换明文（与 openai 表单同一套做法）。
 * [showPlaceholder] 用于编辑模式「未改动」状态：空输入框给一排圆点提示
 * 「这里存着原密码」。
 */
@Composable
private fun PasswordRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    showPlaceholder: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    LabeledField(label) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation =
                if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            placeholder = { if (showPlaceholder) Text("••••••••") },
            trailingIcon = {
                // Material 图标替代 emoji（🙈/👁）：Linux 无 Emoji 字体也能正常显示。
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clickable { visible = !visible }
                            .padding(horizontal = 8.dp),
                )
            },
        )
    }
}

/**
 * 只读路径字段 + 「浏览」按钮：路径只能经文件选择器填入（不允许手写），
 * 取消选择则保持原值。
 */
@Composable
private fun PathRow(
    label: String,
    value: String,
    onBrowse: () -> Unit,
) {
    LabeledField(label) {
        OutlinedTextField(
            value = value,
            onValueChange = { /* 只读：路径一律来自文件选择器 */ },
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = onBrowse) { Text(stringResource("ibm_mq_browse")) }
            },
        )
    }
}

/** 「复选框风格的开关行」：整行可点，视觉上是 M3 Switch。 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onChange(!checked) }
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
