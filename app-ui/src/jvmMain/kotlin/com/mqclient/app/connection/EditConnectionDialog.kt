package com.mqclient.app.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionFormState
import com.mqclient.core.ModuleRegistry
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.stringResource
import dev.nucleusframework.application.DecoratedDialog
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.window.windowDragArea

/**
 * 「修改连接」对话框外壳（与 `NewConnectionDialog` 各自独立，不共享 footer / 向导）：
 *
 *  - 标题固定为 i18n 的「修改连接」（`menu_edit_connection`）；
 *  - 面板内容**复用创建连接第二步的同一套渲染** —— 按 `connection.serviceId` 从
 *    SPI（`ModuleRegistry.forms`）取对应模块的 `ConnectionFormUi`，`rememberFormState`
 *    拿到状态后调 [ConnectionFormState.loadFrom] 预填，再渲染 `Content()`。创建与
 *    修改共用同一个 Composable，不复制一份 IBM MQ 表单；
 *  - footer 是编辑专用的：左侧「关闭」（替换创建流程的「上一步」）、右侧「保存」
 *    （替换「完成」）。保存走表单自己的 [ConnectionFormState.submit]（IBM MQ 内部按
 *    `editingId` 分支到 `ConnectionStore.update`），校验 / 测试连接 / 错误展示全部复用。
 *
 * 模式区分：本对话框只服务于编辑（调用方必传非 null 的 [connection]），由 `loadFrom`
 * 而非 `reset` 初始化；表单层再用 `editingId` 决定落盘是 create 还是 update。
 * 找不到对应模块的表单（理论不会发生）时兜底弹一个「关闭」框，避免点了菜单毫无反应。
 */
@Composable
internal fun EditConnectionDialog(
    connection: ConnectionInfo,
    onDismiss: () -> Unit,
    onConnectionUpdated: () -> Unit,
) {
    val form = ModuleRegistry.forms.firstOrNull { it.serviceId == connection.serviceId }

    // 兜底：连接类型没有注册表单（不应发生），安静给个出口。
    if (form == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource("menu_edit_connection")) },
            text = { Text(connection.name) },
            confirmButton = {
                PressableButton(onClick = onDismiss) { Text(stringResource("common_close")) }
            },
        )
        return
    }

    val formState = form.rememberFormState(connection.name)
    // 编辑模式预填：与创建流程的 reset 对称，但走 loadFrom 载入已有配置。
    LaunchedEffect(Unit) { formState.loadFrom(connection) }

    DecoratedDialog(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = DpSize(780.dp, 560.dp)),
        title = stringResource("menu_edit_connection"),
        resizable = true,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                onDismiss()
                true
            } else {
                false
            }
        },
    ) {
        val nucleusWindow = LocalNucleusWindow.current
        LaunchedEffect(nucleusWindow) { nucleusWindow.setMinimumSize(DpSize(720.dp, 480.dp)) }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 自己的标题栏：标题文字 + ✕，整条是窗口拖拽区（与 NewConnectionDialog 同款）。
                Row(
                    modifier =
                        Modifier
                            .windowDragArea()
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("menu_edit_connection"),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    PressableButton(
                        onClick = onDismiss,
                        standardMinWidth = false, // ✕ 是图标按钮，不套「4 字宽」标准。
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp)) }
                }
                HorizontalDivider()
                // 面板内容：复用创建第二步的同一份 Composable（Content）。
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    formState.Content()
                }
                HorizontalDivider()
                // 编辑专用 footer：关闭（左）+ 保存（右）。
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PressableButton(
                        onClick = onDismiss,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { Text(stringResource("common_close")) }
                    Spacer(Modifier.width(8.dp))
                    PressableButton(onClick = onFinish(formState, onConnectionUpdated, onDismiss), enabled = !formState.busy) {
                        Text(stringResource("common_save"))
                    }
                }
            }
        }
    }
}

/** 「保存」：异步表单（保存前测试连接）经 onSaved 关窗；同步表单 submit 返回 true 时关窗。 */
private fun onFinish(
    formState: ConnectionFormState,
    onConnectionUpdated: () -> Unit,
    onDismiss: () -> Unit,
): () -> Unit = {
    formState.onSaved = { onConnectionUpdated(); onDismiss() }
    if (formState.submit()) {
        onConnectionUpdated()
        onDismiss()
    }
}
