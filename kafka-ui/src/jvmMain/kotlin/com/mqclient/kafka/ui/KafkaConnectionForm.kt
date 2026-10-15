package com.mqclient.kafka.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionFormState
import com.mqclient.core.ConnectionFormUi
import com.mqclient.mqui.state.SaveAnywayDialog
import com.mqclient.mqui.state.SaveState
import com.mqclient.uicommon.LabeledField
import com.mqclient.uicommon.PathRow
import com.mqclient.uicommon.SettingsDropdownRow
import com.mqclient.uicommon.i18n.stringResource

/**
 * Kafka 的「新建 / 编辑连接」表单（`ConnectionFormUi` SPI 实现，注册见
 * `META-INF/services/com.mqclient.core.ConnectionFormUi`）。
 *
 * 字段、校验、错误展示、保存前测试连接、落盘全部归本面板 —— 宿主对话框只给
 * 容器和 footer 按钮（「完成」即保存，Testing 时经 busy 契约禁用）。布局规则
 * 与 ibm-mq 表单一致：全部字段上下布局（标签在上、输入框占满整行宽），超出一
 * 屏走内部滚动。
 *
 * 动态显示：认证区（SASL 机制 / 用户名 / 密码）只在 securityProtocol 为 SASL_*
 * 时显示；TLS 信任库区只在 SSL / SASL_SSL 时显示，truststore 路径只读展示、
 * 经文件选择器填入（与 IBM MQ 同款 [PathRow]）。没有独立的「测试连接」按钮 ——
 * 保存时自动测（见 [KafkaConnectionFormState.submit]）。
 */
class KafkaConnectionForm : ConnectionFormUi {

    override val serviceId = "kafka"

    @Composable
    override fun rememberFormState(defaultName: String): ConnectionFormState {
        val scope = rememberCoroutineScope()
        return remember { KafkaConnectionFormState(defaultName, scope) }
    }
}

/** 必填字段的标签：加 `*` 前缀。 */
@Composable
private fun requiredLabel(key: String): String = "* " + stringResource(key)

/**
 * 表单内容：全部布局与动态显示都在这里；状态与业务规则在
 * [KafkaConnectionFormState]。
 */
@Composable
internal fun KafkaConnectionFormContent(state: KafkaConnectionFormState) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
    ) {
        SectionHeader(stringResource("kafka_ui_section_basic"))
        TextRow(
            label = requiredLabel("conn_name_label"),
            value = state.name,
            onChange = { state.name = it },
        )
        Spacer(Modifier.height(12.dp))
        TextRow(
            label = requiredLabel("kafka_ui_field_bootstrap_servers"),
            value = state.bootstrapServers,
            onChange = { state.bootstrapServers = it; state.clearErrors() },
            placeholder = "localhost:9092",
            errorMessage = state.errors.bootstrapServers,
            hint = stringResource("kafka_ui_hint_bootstrap_servers"),
        )
        Spacer(Modifier.height(12.dp))
        SettingsDropdownRow(
            label = stringResource("kafka_ui_field_security_protocol"),
            currentLabel = protocolLabel(state.securityProtocol),
            options = KafkaConnectionFormState.PROTOCOL_OPTIONS,
            optionLabel = ::protocolLabel,
            onPick = { state.securityProtocol = it; state.clearErrors() },
        )

        // 认证区：只有 SASL_* 协议才有用户名 / 密码 / 机制可言。
        if (state.isSasl) {
            SectionDivider()
            SectionHeader(stringResource("kafka_ui_section_auth"))
            SettingsDropdownRow(
                label = stringResource("kafka_ui_field_sasl_mechanism"),
                currentLabel = mechanismLabel(state.saslMechanism),
                options = KafkaConnectionFormState.SASL_MECHANISM_OPTIONS,
                optionLabel = ::mechanismLabel,
                onPick = { state.saslMechanism = it },
            )
            Spacer(Modifier.height(12.dp))
            TextRow(
                label = stringResource("kafka_ui_field_username"),
                value = state.username,
                onChange = { state.username = it },
            )
            Spacer(Modifier.height(12.dp))
            PasswordRow(
                label = stringResource("kafka_ui_field_password"),
                value = state.password,
                onChange = { state.password = it; state.passwordChanged = true },
                showPlaceholder = state.originalPassword.isNotEmpty(),
            )
        }

        // 信任库区：TLS 协议（SSL / SASL_SSL）才显示。自签 CA 时需指定 truststore，
        // 留空走 JVM 默认信任库。类型不单设字段：.p12/.pfx 按 PKCS12，其余按 JKS。
        // 路径只读 + 「浏览」按钮，与 IBM MQ 表单同一套（ui-common PathRow）。
        if (state.isTls) {
            SectionDivider()
            SectionHeader(stringResource("kafka_ui_section_tls"))
            PathRow(
                label = stringResource("kafka_ui_field_truststore_path"),
                value = state.truststorePath,
                onBrowse = state::pickTrustStore,
            )
            FieldHint(stringResource("kafka_ui_hint_truststore_path"))
            Spacer(Modifier.height(12.dp))
            PasswordRow(
                label = stringResource("kafka_ui_field_truststore_password"),
                value = state.truststorePassword,
                onChange = { state.truststorePassword = it; state.truststorePasswordChanged = true },
                showPlaceholder = state.originalTruststorePassword.isNotEmpty(),
            )
        }

        SectionDivider()
        SectionHeader(stringResource("kafka_ui_section_advanced"))
        TextRow(
            label = stringResource("kafka_ui_field_connection_timeout"),
            value = state.connectionTimeout,
            onChange = { state.connectionTimeout = it; state.clearErrors() },
            errorMessage = state.errors.connectionTimeout,
            suffix = "s",
        )
        TextRow(
            label = stringResource("kafka_ui_field_object_scan_interval"),
            value = state.objectScanIntervalMinutes,
            onChange = { state.objectScanIntervalMinutes = it; state.clearErrors() },
            errorMessage = state.errors.objectScanInterval,
            suffix = "min",
        )
        TextRow(
            label = stringResource("kafka_ui_field_lag_scan_interval"),
            value = state.lagScanIntervalSeconds,
            onChange = { state.lagScanIntervalSeconds = it; state.clearErrors() },
            errorMessage = state.errors.lagScanInterval,
            suffix = "s",
        )

        // 测试进行中的提示（保存按钮已由宿主按 busy 禁用）。
        if (state.saveState == SaveState.Testing) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource("kafka_ui_test_running"),
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

    // 测试失败后的「是否仍要保存」确认框（mq-ui 公共组件，与 IBM MQ 同一交互）：
    // 「保存」按测试时的配置快照落盘；「取消」只关本框，主对话框与表单原样保留。
    (state.saveState as? SaveState.Failed)?.let { failed ->
        SaveAnywayDialog(
            message = failed.message,
            onSave = state::confirmSave,
            onCancel = state::dismissFailed,
        )
    }
}

private fun protocolLabel(protocol: String): String = stringResource(
    when (protocol) {
        "PLAINTEXT" -> "kafka_ui_protocol_plaintext"
        "SSL" -> "kafka_ui_protocol_ssl"
        "SASL_PLAINTEXT" -> "kafka_ui_protocol_sasl_plaintext"
        else -> "kafka_ui_protocol_sasl_ssl"
    },
)

private fun mechanismLabel(mechanism: String): String = stringResource(
    when (mechanism) {
        "PLAIN" -> "kafka_ui_mechanism_plain"
        "SCRAM-SHA-256" -> "kafka_ui_mechanism_scram256"
        else -> "kafka_ui_mechanism_scram512"
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

/** 字段下方的中性提示小字（输入约束 / 字段用途说明）；无内容时不占位。 */
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

/** 普通文本字段（标签在上、输入框占满整行宽）+ 字段下方错误提示。 */
@Composable
private fun TextRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    errorMessage: String? = null,
    suffix: String? = null,
    hint: String? = null,
) {
    LabeledField(label) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = modifier.fillMaxWidth(),
            singleLine = true,
            isError = errorMessage != null,
            placeholder = { placeholder?.let { Text(it) } },
            suffix = { suffix?.let { Text(it) } },
        )
    }
    FieldError(errorMessage)
    FieldHint(hint)
}

/**
 * 密码字段：密文显示 + 右侧眼睛图标切换明文（与 ibm-mq 表单同一套做法）。
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
