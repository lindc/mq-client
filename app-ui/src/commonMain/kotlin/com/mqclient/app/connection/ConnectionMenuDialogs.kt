package com.mqclient.app.connection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import dev.nucleusframework.window.windowDragArea
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStore
import com.mqclient.core.CoreModule
import com.mqclient.core.ModuleRegistry
import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.DangerButton
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.dialog.computeContentWidth
import com.mqclient.uicommon.dialog.rememberStandardDialogSize
import com.mqclient.uicommon.SecondaryButton
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
    onConnectionChanged: () -> Unit,
) {
    state.deleteTarget?.let { connection ->
        DeleteConfirmDialog(
            connection = connection,
            onConfirm = {
                state.deleteTarget = null
                ConnectionStore.delete(connection.id).onFailure { failure ->
                    CoreModule.reportError(
                        message = failure.userMessage(),
                        source = "app-ui",
                        error = failure,
                    )
                }
            },
            onDismiss = { state.deleteTarget = null },
        )
    }
    // 修改连接：复用「修改连接」对话框（与新建对话框各自独立 footer），预填并走更新。
    state.editTarget?.let { connection ->
        EditConnectionDialog(
            connection = connection,
            onDismiss = { state.editTarget = null },
            onConnectionUpdated = onConnectionChanged,
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
                // 重试重新走「先连接再查属性」的完整编排（挂起函数）；对话框本身可能
                // 随状态清空而离开组合，动作必须跑在应用级 scope 上。
                CoreModule.scope.launch { actions.showQmgrProperties(failure.connectionId) }
            },
            onDismiss = { state.connectFailure = null },
        )
    }
    state.connectError?.let { failure ->
        ConnectFailedDialog(
            failure = failure,
            onRetry = {
                state.connectError = null
                // 普通连接失败只重试连接本身，不进「先连接再查属性」的编排。
                CoreModule.scope.launch { actions.connect(failure.connectionId) }
            },
            onDismiss = { state.connectError = null },
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
            DangerButton(onClick = onConfirm) {
                Text(stringResource("common_delete"))
            }
        },
        dismissButton = {
            SecondaryButton(
                onClick = onDismiss,
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
            PrimaryButton(onClick = onRetry) { Text(stringResource("common_retry")) }
        },
        dismissButton = {
            PrimaryButton(
                onClick = onDismiss,
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
            PrimaryButton(onClick = onDismiss) { Text(stringResource("common_close")) }
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
        // 初始尺寸走公共规范（CONTENT，屏幕 85%），与 MQ 对象属性对话框一致。
        state = rememberDialogState(size = rememberStandardDialogSize()),
        title = stringResource("qmgr_properties_title").format(connection.name),
        resizable = true,
        // ESC 关闭（对话框无系统标题栏，键盘出口得自己接——同 SettingsDialog）。
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                onDismiss()
                true
            } else {
                false
            }
        },
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 自绘标题栏（同 EditConnectionDialog）：标题文字 + ✕，整条是窗口拖拽区。
                Row(
                    modifier =
                        Modifier
                            .windowDragArea()
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("qmgr_properties_title").format(connection.name),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                HorizontalDivider()
                Column(modifier = Modifier.fillMaxSize().weight(1f)) {
                    if (propertiesUi != null) {
                        // 内容区宽度默认走 ObjectDetailDialog 同一规范：min(W, max(0.8W, 900dp))
                        // 并水平居中。模块可通过 [ConnectionPropertiesUi.capContentWidth] 声明
                        // 满窗铺满（IBM MQ 的 Explorer 式「左导航 + 右表格」布局需要贴边）。
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val contentWidth = if (propertiesUi.capContentWidth) {
                                computeContentWidth(maxWidth)
                            } else {
                                maxWidth
                            }
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Box(modifier = Modifier.width(contentWidth).fillMaxHeight()) {
                                    propertiesUi.Content(connection)
                                }
                            }
                        }
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
                    PrimaryButton(
                        onClick = onDismiss,
                    ) { Text(stringResource("common_close")) }
                }
            }
        }
    }
}
