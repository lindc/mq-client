package com.mqclient.rabbitmq.ui

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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionFormState
import com.mqclient.core.ConnectionFormUi
import com.mqclient.mqui.state.SaveAnywayDialog
import com.mqclient.mqui.state.SaveState
import com.mqclient.uicommon.LabeledField
import com.mqclient.uicommon.i18n.stringResource

/**
 * RabbitMQ 的「新建 / 编辑连接」表单（`ConnectionFormUi` SPI 实现，注册见
 * `META-INF/services/com.mqclient.core.ConnectionFormUi`）。
 *
 * 字段、校验、错误展示、保存前测试连接、落盘全部归本面板 —— 宿主对话框只给
 * 容器和 footer 按钮（「完成」即保存，Testing 时经 busy 契约禁用）。布局规则
 * 与 kafka 表单一致：全部字段上下布局（标签在上、输入框占满整行宽），超出一
 * 屏走内部滚动。
 *
 * RabbitMQ 特有两端口：AMQP（5672，消息收发）与管理 API（15672，本阶段全部
 * 功能的通道）；TLS 开关同时影响两者（https / amqps 语义）。
 */
class RabbitMqConnectionForm : ConnectionFormUi {

    override val serviceId = "rabbitmq"

    @Composable
    override fun rememberFormState(defaultName: String): ConnectionFormState {
        val scope = rememberCoroutineScope()
        return remember { RabbitMqConnectionFormState(defaultName, scope) }
    }
}

/** 必填字段的标签：加 `*` 前缀。 */
@Composable
private fun requiredLabel(key: String): String = "* " + stringResource(key)

/**
 * 表单内容：全部布局与动态显示都在这里；状态与业务规则在
 * [RabbitMqConnectionFormState]。
 */
@Composable
internal fun RabbitMqConnectionFormContent(state: RabbitMqConnectionFormState) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
    ) {
        SectionHeader(stringResource("rabbitmq_ui_section_basic"))
        TextRow(
            label = requiredLabel("conn_name_label"),
            value = state.name,
            onChange = { state.name = it },
        )
        Spacer(Modifier.height(12.dp))
        TextRow(
            label = requiredLabel("rabbitmq_ui_field_host"),
            value = state.host,
            onChange = { state.host = it; state.clearErrors() },
            placeholder = "localhost",
            errorMessage = state.errors.host,
        )
        Spacer(Modifier.height(12.dp))
        TextRow(
            label = stringResource("rabbitmq_ui_field_amqp_port"),
            value = state.amqpPort,
            onChange = { state.amqpPort = it.filter(Char::isDigit); state.clearErrors() },
            errorMessage = state.errors.amqpPort,
            hint = stringResource("rabbitmq_ui_hint_amqp_port"),
        )
        Spacer(Modifier.height(12.dp))
        TextRow(
            label = requiredLabel("rabbitmq_ui_field_management_port"),
            value = state.managementPort,
            onChange = { state.managementPort = it.filter(Char::isDigit); state.clearErrors() },
            errorMessage = state.errors.managementPort,
            hint = stringResource("rabbitmq_ui_hint_management_port"),
        )
        Spacer(Modifier.height(12.dp))
        TextRow(
            label = requiredLabel("rabbitmq_ui_field_vhost"),
            value = state.vhost,
            onChange = { state.vhost = it; state.clearErrors() },
            errorMessage = state.errors.vhost,
            hint = stringResource("rabbitmq_ui_hint_vhost"),
        )
        Spacer(Modifier.height(12.dp))
        BooleanRow(
            label = stringResource("rabbitmq_ui_field_use_tls"),
            checked = state.useTls,
            onChange = { state.useTls = it },
            hint = stringResource("rabbitmq_ui_hint_use_tls"),
        )

        SectionDivider()
        SectionHeader(stringResource("rabbitmq_ui_section_auth"))
        TextRow(
            label = stringResource("rabbitmq_ui_field_username"),
            value = state.username,
            onChange = { state.username = it },
        )
        Spacer(Modifier.height(12.dp))
        PasswordRow(
            label = stringResource("rabbitmq_ui_field_password"),
            value = state.password,
            onChange = { state.password = it; state.passwordChanged = true },
            showPlaceholder = state.originalPassword.isNotEmpty(),
        )

        SectionDivider()
        SectionHeader(stringResource("rabbitmq_ui_section_advanced"))
        TextRow(
            label = stringResource("rabbitmq_ui_field_connection_timeout"),
            value = state.connectionTimeout,
            onChange = { state.connectionTimeout = it; state.clearErrors() },
            errorMessage = state.errors.connectionTimeout,
            suffix = "s",
        )
        TextRow(
            label = stringResource("rabbitmq_ui_field_object_scan_interval"),
            value = state.objectScanIntervalMinutes,
            onChange = { state.objectScanIntervalMinutes = it; state.clearErrors() },
            errorMessage = state.errors.objectScanInterval,
            suffix = "min",
        )
        TextRow(
            label = stringResource("rabbitmq_ui_field_status_scan_interval"),
            value = state.statusScanIntervalSeconds,
            onChange = { state.statusScanIntervalSeconds = it; state.clearErrors() },
            errorMessage = state.errors.statusScanInterval,
            suffix = "s",
        )

        // 测试进行中的提示（保存按钮已由宿主按 busy 禁用）。
        if (state.saveState == SaveState.Testing) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource("rabbitmq_ui_test_running"),
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

    // 测试失败后的「是否仍要保存」确认框（mq-ui 公共组件，与 Kafka 同一交互）：
    // 「保存」按测试时的配置快照落盘；「取消」只关本框，主对话框与表单原样保留。
    (state.saveState as? SaveState.Failed)?.let { failed ->
        SaveAnywayDialog(
            message = failed.message,
            onSave = state::confirmSave,
            onCancel = state::dismissFailed,
        )
    }
}

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

/** 布尔开关行：Checkbox + 标签同行（下方可带中性提示）。 */
@Composable
private fun BooleanRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    hint: String? = null,
) {
    LabeledField(label) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onChange)
        }
    }
    FieldHint(hint)
}

/**
 * 密码字段：密文显示 + 右侧眼睛图标切换明文（与 kafka / ibm-mq 表单同一套做法）。
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
