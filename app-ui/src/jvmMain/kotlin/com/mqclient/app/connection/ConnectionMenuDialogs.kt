package com.mqclient.app.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStore
import com.mqclient.core.CoreModule
import com.mqclient.core.ModuleRegistry
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.stringResource
import dev.nucleusframework.application.DecoratedDialog
import kotlinx.coroutines.launch

/**
 * 右键菜单引出的全部对话框。只认 [ConnectionMenuState] 里的状态，谁置的状态
 * 谁清（关闭回调置回 null）—— 状态与 UI 之间没有第二条通路。
 */
@Composable
fun ConnectionMenuDialogs(
    state: ConnectionMenuState,
    actions: ConnectionActionsImpl,
) {
    val scope = rememberCoroutineScope()
    state.deleteTarget?.let { connection ->
        DeleteConfirmDialog(
            connection = connection,
            onConfirm = {
                state.deleteTarget = null
                ConnectionStore.delete(connection.id).onFailure { failure ->
                    CoreModule.reportError(
                        message = failure.message ?: failure::class.simpleName ?: "unknown",
                        source = "app-ui",
                        error = failure,
                    )
                }
            },
            onDismiss = { state.deleteTarget = null },
        )
    }
    state.connectingForProperties?.let { connection ->
        ConnectingDialog(connectionName = connection.name, onDismiss = { state.connectingForProperties = null })
    }
    state.connectFailure?.let { failure ->
        ConnectFailedDialog(
            failure = failure,
            onRetry = {
                state.connectFailure = null
                // 重试重新走「先连接再查属性」的完整编排（挂起函数，进协程）。
                scope.launch { actions.showQmgrProperties(failure.connectionId) }
            },
            onDismiss = { state.connectFailure = null },
        )
    }
    state.testResult?.let { result ->
        TestResultDialog(result = result, onDismiss = { state.testResult = null })
    }
    state.propertiesTarget?.let { connection ->
        ConnectionPropertiesDialog(connection = connection, onDismiss = { state.propertiesTarget = null })
    }
}

/** 删除二次确认（约束：红色菜单项 + 二次确认；确认文案点名连接，防止误删）。 */
@Composable
private fun DeleteConfirmDialog(connection: ConnectionInfo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("delete_confirm_title")) },
        text = { Text(stringResource("delete_confirm_text").format(connection.name)) },
        confirmButton = {
            PressableButton(onClick = onConfirm) {
                Text(stringResource("common_delete"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_cancel")) }
        },
    )
}

/** 「正在连接」提示：属性对话框打开前的过渡；点外部可关（关闭只是关提示，连接继续）。 */
@Composable
private fun ConnectingDialog(connectionName: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(28.dp))
                Spacer(Modifier.width(16.dp))
                Text(stringResource("connect_dialog_text").format(connectionName))
            }
        },
        confirmButton = {},
    )
}

/** 先连接流程的失败对话框：错误详情 + 重试（重试重新走整个「先连接再查属性」流程）。 */
@Composable
private fun ConnectFailedDialog(failure: ConnectFailure, onRetry: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("connect_failed_title")) },
        text = {
            Column {
                Text(stringResource("connect_failed_text").format(failure.connectionName))
                Spacer(Modifier.padding(vertical = 4.dp))
                Text(
                    text = failure.message ?: stringResource("connect_failed_no_detail"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            PressableButton(onClick = onRetry) { Text(stringResource("common_retry")) }
        },
        dismissButton = {
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_close")) }
        },
    )
}

/** 测试连接结果：成功 / 失败 + 详情 + 耗时（来源：`.connection.test.completed` 事件）。 */
@Composable
private fun TestResultDialog(result: TestResultData, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("test_result_title")) },
        text = {
            Column {
                Text(
                    text = if (result.success) stringResource("test_result_success") else stringResource("test_result_failed"),
                    color =
                        if (result.success) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                )
                if (result.message.isNotBlank()) {
                    Spacer(Modifier.padding(vertical = 4.dp))
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource("test_result_duration").format(result.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            PressableButton(onClick = onDismiss) { Text(stringResource("common_close")) }
        },
    )
}

/**
 * 队列管理器属性对话框（宿主容器）：内容归模块（[ModuleRegistry.propertiesUis]
 * 按 `serviceId` 找，找不到的连接不该走到这里 —— 菜单项已被过滤，占位只是兜底）。
 */
@Composable
internal fun ConnectionPropertiesDialog(connection: ConnectionInfo, onDismiss: () -> Unit) {
    val propertiesUi = remember(connection.serviceId) {
        ModuleRegistry.propertiesUis.firstOrNull { it.serviceId == connection.serviceId }
    }
    DecoratedDialog(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = DpSize(560.dp, 640.dp)),
        title = stringResource("qmgr_properties_title").format(connection.name),
        resizable = true,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                HorizontalDivider()
                Column(modifier = Modifier.fillMaxSize().weight(1f)) {
                    if (propertiesUi != null) {
                        propertiesUi.Content(connection)
                    } else {
                        Text(
                            text = stringResource("qmgr_properties_missing_ui"),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    PressableButton(
                        onClick = onDismiss,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { Text(stringResource("common_close")) }
                }
            }
        }
    }
}
