package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.mqclient.starrocks.StarRocksConstants
import com.mqclient.uicommon.LabeledField
import com.mqclient.uicommon.i18n.stringResource

/**
 * StarRocks 的「新建 / 编辑连接」表单（[ConnectionFormUi] SPI 实现）。
 *
 * 注册见 `META-INF/services/com.mqclient.core.ConnectionFormUi`；宿主
 * `NewConnectionDialog` 第二步按 `serviceId` 找它。交互与四套 MQ 表单一致：
 * **面板没有自己的保存/测试按钮**，宿主 footer 的「完成」经
 * [com.mqclient.mqui.state.AbstractConnectionFormState.submit] 驱动
 * 测试 → 落盘 → 失败确认框；状态由 `remember { }` 持有的
 * [StarRocksConnectionFormState] 管理（字段是 `mutableStateOf`）。
 */
class StarRocksConnectionForm : ConnectionFormUi {

    /** 与 `StarRocksServiceAdapter.id` / `StarRocksAdapterUI.serviceId` 一致。 */
    override val serviceId: String = StarRocksConstants.PROTOCOL_ID

    @Composable
    override fun rememberFormState(defaultName: String): ConnectionFormState {
        val scope = rememberCoroutineScope()
        return remember { StarRocksConnectionFormState(defaultName, scope) }
    }
}

/**
 * 表单本体：全宽字段用 ui-common `LabeledField`；**主机 / 端口这一行单独用
 * [HostPortRow]**——不能复用 `LabeledField`：它的根 `Column` 硬编码
 * `Modifier.fillMaxWidth()`，在 Row 里两列都会要求占满整行，主机把空间吃光、
 * 端口被挤到边缘（截图里的布局崩塌）。这里按列分配宽度，输入框的
 * `fillMaxWidth` 只相对各自的 `Column`，互不冲突。
 *
 * 超出一屏走内部滚动；所有标签与状态文案走 i18n（`olap.starrocks.form.*`）。
 */
@Composable
internal fun StarRocksConnectionFormContent(state: StarRocksConnectionFormState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 连接名：初始为空，用 placeholder 提示（不硬编码「连接 N」）。
        FormField(
            label = stringResource("olap.starrocks.form.name"),
            value = state.name,
            onValueChange = { state.name = it; state.clearErrors() },
            placeholder = stringResource("olap.starrocks.form.name_placeholder"),
        )

        // 主机 / 端口并排：宽度由两列自己分配（见 [HostPortRow]）。
        HostPortRow(state)

        FormField(
            label = stringResource("olap.starrocks.form.user"),
            value = state.user,
            onValueChange = { state.user = it; state.clearErrors() },
            errorKey = state.errors.user,
        )

        // 密码：掩码 + 眼睛图标切换可见性（可见性用 remember { mutableStateOf }）。
        PasswordField(state)

        FormField(
            label = stringResource("olap.starrocks.form.database"),
            value = state.database,
            onValueChange = { state.database = it; state.clearErrors() },
            errorKey = state.errors.database,
        )

        // 测试进行中（宿主「完成」已按 busy 禁用）—— MQ 系同款面板内提示。
        if (state.saveState == SaveState.Testing) {
            Text(
                text = stringResource("olap.starrocks.form.test_running"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 落盘失败原因（校验错误在各自字段下面；错误从不出面板边界）。
        state.saveError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 测试失败后的「是否仍要保存」确认框（mq-ui 公共组件，与 IBM MQ / Kafka
    // 同一交互）：「保存」按测试时的配置快照落盘，「取消」只关本框，
    // 主对话框与表单原样保留，用户可继续改配置。
    (state.saveState as? SaveState.Failed)?.let { failed ->
        SaveAnywayDialog(
            message = failed.message,
            onSave = state::confirmSave,
            onCancel = state::dismissFailed,
        )
    }
}

/**
 * 主机 / 端口并排行（布局修复版）。
 *
 * - [Row] 占满整行、[Alignment.Bottom] 让两个输入框底边对齐（有 supportingText
 *   校验提示时也不会高低错位）；
 * - 主机列 `weight(1f)` 吃剩余宽度，端口列固定 `120.dp`（够放 5 位端口）；
 * - 中间 `Spacer(16.dp)`；两列各自 `Text` 标签 + `OutlinedTextField`。
 * - **不使用 `LabeledField`**：它的根 `Column(fillMaxWidth)` 会在 Row 里和
 *   weight 冲突；每个 `OutlinedTextField` 的 `fillMaxWidth` 只相对自己的列。
 */
@Composable
private fun HostPortRow(state: StarRocksConnectionFormState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            FieldLabel(stringResource("olap.starrocks.form.host"))
            OutlinedTextField(
                value = state.host,
                onValueChange = { state.host = it; state.clearErrors() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.errors.host != null,
                placeholder = { Text(stringResource("olap.starrocks.form.host_placeholder")) },
                supportingText = state.errors.host?.let { key -> { Text(stringResource(key)) } },
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.width(120.dp)) {
            FieldLabel(stringResource("olap.starrocks.form.port"))
            OutlinedTextField(
                value = state.port,
                onValueChange = state::onPortChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.errors.port != null,
                placeholder = { Text(stringResource("olap.starrocks.form.port_placeholder")) },
                supportingText = state.errors.port?.let { key -> { Text(stringResource(key)) } },
            )
        }
    }
}

/** 与 ui-common `LabeledField` 一致的标签样式（并排行自行渲染标签，避免根 Column 抢宽度）。 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
}

/** 全宽文本字段：标签走 i18n，错误 key 在展示时点翻译。 */
@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    errorKey: String? = null,
) {
    LabeledField(label) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = errorKey != null,
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = errorKey?.let { { Text(stringResource(it)) } },
        )
    }
}

/** 密码字段：掩码显示 + 右侧眼睛图标切换明文。 */
@Composable
private fun PasswordField(state: StarRocksConnectionFormState) {
    var visible by remember { mutableStateOf(false) }
    LabeledField(stringResource("olap.starrocks.form.password")) {
        OutlinedTextField(
            value = state.password,
            onValueChange = { state.password = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            if (visible) "olap.starrocks.form.hide_password"
                            else "olap.starrocks.form.show_password",
                        ),
                    )
                }
            },
        )
    }
}
