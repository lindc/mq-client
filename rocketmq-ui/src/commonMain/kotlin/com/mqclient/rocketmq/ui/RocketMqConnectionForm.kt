package com.mqclient.rocketmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionFormState
import com.mqclient.core.ConnectionFormUi
import com.mqclient.mqui.state.SaveAnywayDialog
import com.mqclient.mqui.state.SaveState
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.PathRow

/**
 * RocketMQ 的「新建 / 编辑连接」表单（`ConnectionFormUi` SPI 实现，注册见
 * `META-INF/services/com.mqclient.core.ConnectionFormUi`）。字段、校验、错误
 * 展示、保存前测试连接、落盘全部归本面板 —— 宿主对话框只给容器和 footer 按钮。
 *
 * 布局与 kafka / rabbitmq 表单一致：全部字段上下布局，超出一屏走内部滚动。
 * ACL 未开启时 AccessKey / SecretKey 留空即可。
 */
class RocketMqConnectionForm : ConnectionFormUi {

    override val serviceId = "rocketmq"

    @Composable
    override fun rememberFormState(defaultName: String): ConnectionFormState {
        val scope = rememberCoroutineScope()
        return remember { RocketMqConnectionFormState(defaultName, scope) }
    }
}

@Composable
private fun requiredLabel(key: String): String = "* " + stringResource(key)

@Composable
internal fun RocketMqConnectionFormContent(state: RocketMqConnectionFormState) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
    ) {
        SectionHeader(stringResource("rocketmq_ui_section_basic"))
        TextRow(
            label = requiredLabel("conn_name_label"),
            value = state.name,
            onChange = { state.name = it },
        )
        Spacer(Modifier.height(12.dp))
        TextRow(
            label = requiredLabel("rocketmq_ui_field_namesrv"),
            value = state.namesrvAddr,
            onChange = { state.namesrvAddr = it; state.clearErrors() },
            placeholder = "10.0.0.1:9876;10.0.0.2:9876",
            errorMessage = state.errors.namesrvAddr,
            hint = stringResource("rocketmq_ui_hint_namesrv"),
        )

        SectionDivider()
        SectionHeader(stringResource("rocketmq_ui_section_acl"))
        TextRow(
            label = stringResource("rocketmq_ui_field_access_key"),
            value = state.accessKey,
            onChange = { state.accessKey = it },
            placeholder = stringResource("rocketmq_ui_field_access_key_placeholder"),
        )
        Spacer(Modifier.height(12.dp))
        PasswordRow(
            label = stringResource("rocketmq_ui_field_secret_key"),
            value = state.secretKey,
            onChange = { state.secretKey = it; state.secretKeyChanged = true },
            showPlaceholder = state.originalSecretKey.isNotEmpty(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource("rocketmq_ui_hint_acl"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionDivider()
        SectionHeader(stringResource("rocketmq_ui_section_tls"))
        CheckboxRow(
            label = stringResource("rocketmq_ui_tls_enable"),
            checked = state.tlsEnabled,
            onChange = { state.tlsEnabled = it; state.clearErrors() },
        )
        if (state.tlsEnabled) {
            Spacer(Modifier.height(12.dp))
            CheckboxRow(
                label = stringResource("rocketmq_ui_tls_verify_server"),
                checked = state.tlsVerifyServer,
                onChange = { state.tlsVerifyServer = it; state.clearErrors() },
            )
            if (!state.tlsVerifyServer) {
                Text(
                    text = stringResource("rocketmq_ui_tls_verify_warning"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            PathRow(
                label = stringResource("rocketmq_ui_tls_field_truststore_path"),
                value = state.trustStorePath,
                onBrowse = state::pickTrustStore,
                onClear = state.trustStorePath.ifEmpty { null }?.let { state::clearTrustStore },
            )
            Text(
                text = stringResource("rocketmq_ui_tls_hint_truststore"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            PasswordRow(
                label = stringResource("rocketmq_ui_tls_field_truststore_password"),
                value = state.trustStorePassword,
                onChange = { state.trustStorePassword = it; state.trustStorePasswordChanged = true },
                showPlaceholder = state.originalTrustStorePassword.isNotEmpty(),
                enabled = state.trustStorePath.isNotEmpty(),
                disabledPlaceholder = stringResource("rocketmq_ui_tls_field_truststore_disabled_placeholder"),
            )
            FieldError(state.errors.tlsTrustStore)
            Spacer(Modifier.height(12.dp))
            PathRow(
                label = stringResource("rocketmq_ui_tls_field_keystore_path"),
                value = state.keyStorePath,
                onBrowse = state::pickKeyStore,
                onClear = state.keyStorePath.ifEmpty { null }?.let { state::clearKeyStore },
            )
            Text(
                text = stringResource("rocketmq_ui_tls_hint_keystore"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            PasswordRow(
                label = stringResource("rocketmq_ui_tls_field_keystore_password"),
                value = state.keyStorePassword,
                onChange = { state.keyStorePassword = it; state.keyStorePasswordChanged = true },
                showPlaceholder = state.originalKeyStorePassword.isNotEmpty(),
                enabled = state.keyStorePath.isNotEmpty(),
                disabledPlaceholder = stringResource("rocketmq_ui_tls_field_keystore_disabled_placeholder"),
            )
            FieldError(state.errors.tlsKeyStore)
        }

        SectionDivider()
        SectionHeader(stringResource("rocketmq_ui_section_advanced"))
        TextRow(
            label = stringResource("rocketmq_ui_field_connection_timeout"),
            value = state.connectionTimeout,
            onChange = { state.connectionTimeout = it; state.clearErrors() },
            errorMessage = state.errors.connectionTimeout,
            suffix = "s",
        )
        Spacer(Modifier.height(12.dp))
        TextRow(
            label = stringResource("rocketmq_ui_field_object_scan_interval"),
            value = state.objectScanIntervalMinutes,
            onChange = { state.objectScanIntervalMinutes = it; state.clearErrors() },
            errorMessage = state.errors.objectScanInterval,
            suffix = "min",
        )
        Spacer(Modifier.height(12.dp))
        TextRow(
            label = stringResource("rocketmq_ui_field_status_scan_interval"),
            value = state.statusScanIntervalSeconds,
            onChange = { state.statusScanIntervalSeconds = it; state.clearErrors() },
            errorMessage = state.errors.statusScanInterval,
            suffix = "s",
        )

        if (state.saveState == SaveState.Testing) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource("rocketmq_ui_test_running"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

    (state.saveState as? SaveState.Failed)?.let { failed ->
        SaveAnywayDialog(
            message = failed.message,
            onSave = state::confirmSave,
            onCancel = state::dismissFailed,
        )
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun TextRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    errorMessage: String? = null,
    hint: String? = null,
    suffix: String? = null,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            placeholder = placeholder?.let { p -> { Text(p) } },
            isError = errorMessage != null,
            suffix = suffix?.let { s -> { Text(s) } },
            modifier = Modifier.fillMaxWidth(),
        )
        FieldError(errorMessage)
        FieldHint(hint)
    }
}

@Composable
private fun PasswordRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    showPlaceholder: Boolean,
    enabled: Boolean = true,
    disabledPlaceholder: String? = null,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            enabled = enabled,
            placeholder = {
                Text(
                    when {
                        !enabled && disabledPlaceholder != null -> disabledPlaceholder
                        showPlaceholder -> stringResource("rocketmq_ui_field_secret_placeholder")
                        else -> stringResource("rocketmq_ui_field_secret_key_placeholder")
                    }
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 带标签的开关行：整行可点（点标签即可切换，不用瞄准小方块）。 */
@Composable
private fun CheckboxRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

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

@Composable
private fun FieldHint(message: String?) {
    if (message != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
