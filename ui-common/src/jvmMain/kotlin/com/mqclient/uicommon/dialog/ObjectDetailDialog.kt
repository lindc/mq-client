package com.mqclient.uicommon.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.stringResource
import dev.nucleusframework.application.DecoratedDialog
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.window.windowDragArea

/**
 * 对象详情对话框：外观与设置对话框（SettingsDialog）一致——
 * nucleus 自绘标题栏（[windowDragArea] 拖拽 + ✕ 关闭按钮）、底部操作区、
 * 窗口边缘原生 resize（`resizable = true`），min 尺寸经 [LocalNucleusWindow]
 * 的 `setMinimumSize` 落地；max 仅钳制**初始**尺寸（框架无 setMaximumSize，
 * 与 SettingsDialog 只设 min 的行为一致）。
 *
 * 默认初始尺寸为屏幕的 2/3（宽高各 2/3，[rememberTwoThirdsScreenSize]）；
 * 调用方可用 [initialWidth] / [initialHeight] 显式覆盖。
 *
 * 必须用 nucleus 的 `DecoratedDialog` 而非 compose 的 `DialogWindow`：
 * 项目的窗口管线是 nucleus/Tao 自绘框架（主线程非 AWT EDT），compose 的
 * DialogWindow 会在非 EDT 上创建 SkiaLayer 而抛
 * `IllegalStateException: Method should be called from AWT event dispatch thread`（实测）。
 * ESC 也走关闭（对话框无系统标题栏，键盘出口得自己接——同 SettingsDialog）。
 */
@Composable
fun ObjectDetailDialog(
    title: String,
    onDismiss: () -> Unit,
    initialWidth: Dp? = null, // null → 屏幕宽的 2/3
    initialHeight: Dp? = null, // null → 屏幕高的 2/3
    minWidth: Dp = 400.dp,
    minHeight: Dp = 300.dp,
    maxWidth: Dp = Dp.Infinity,
    maxHeight: Dp = Dp.Infinity,
    content: @Composable () -> Unit,
) {
    // 初始尺寸与 min/max 求交集：调用方配置失配时不至于开局越界。
    val twoThirdsScreen = rememberTwoThirdsScreenSize()
    val startWidth = (initialWidth ?: twoThirdsScreen.width).coerceIn(minWidth, maxWidth)
    val startHeight = (initialHeight ?: twoThirdsScreen.height).coerceIn(minHeight, maxHeight)

    DecoratedDialog(
        onCloseRequest = onDismiss,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                onDismiss()
                true
            } else {
                false
            }
        },
        state = rememberDialogState(size = DpSize(startWidth, startHeight)),
        title = title,
        resizable = true,
    ) {
        // min 尺寸落地（框架无 setMaximumSize，max 只钳制初始尺寸）。
        val nucleusWindow = LocalNucleusWindow.current
        LaunchedEffect(nucleusWindow) {
            nucleusWindow.setMinimumSize(DpSize(minWidth, minHeight))
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. 标题栏（拖拽区 + 标题 + ✕），样式同 SettingsDialog.HeaderBar
                Row(
                    modifier = Modifier
                        .windowDragArea()
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    PressableButton(
                        onClick = onDismiss,
                        standardMinWidth = false, // ✕ 是图标按钮，不套「4 字宽」标准。
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider()

                // 2. 内容区（业务模块填充）
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    content()
                }

                // 3. 底部关闭按钮（样式同 SettingsDialog.FooterActions）
                HorizontalDivider()
                Row(
                    modifier = Modifier
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
                }
            }
        }
    }
}
