package com.mqclient.ibmmq.ui.command

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private const val MIN_RATIO = 0.2f
private const val MAX_RATIO = 0.8f

/**
 * MQSC 命令控制台主面板：工具栏 / 编辑器（上）/ 可拖动分隔条 / 输出区（下）/
 * 状态栏。编辑器与输出区的高度比例 [splitRatio] 拖动可调（0.2 - 0.8），
 * 跨会话不保留（重开 Tab 回到对半）。
 */
@Composable
fun MqscConsolePanel(
    state: MqscConsoleViewModel,
    modifier: Modifier = Modifier,
) {
    var splitRatio by remember { mutableStateOf(0.5f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 分隔条回调给的是像素增量；比例换算要面板总高，在这里换算最合适。
        val heightPx = constraints.maxHeight.toFloat()
        Column(modifier = Modifier.fillMaxSize()) {
            MqscToolbar(
                isExecuting = state.isExecuting,
                onExecute = state::execute,
                onCancel = state::cancel,
                onClear = state::clear,
            )
            Box(modifier = Modifier.fillMaxWidth().weight(splitRatio)) {
                MqscEditor(
                    commands = state.commandLines,
                    onTextChange = state::onTextChange,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            DraggableDivider(
                onDrag = { deltaPx ->
                    splitRatio = (splitRatio + deltaPx / heightPx).coerceIn(MIN_RATIO, MAX_RATIO)
                },
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f - splitRatio)) {
                MqscOutputView(
                    results = state.results,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            MqscStatusBar(
                commandCount = state.commandLines.count { it.text.isNotBlank() && !it.text.trim().startsWith("*") },
                successCount = state.results.count { it.success },
                failedCount = state.results.count { !it.success },
                totalDuration = state.results.sumOf { it.duration },
                isExecuting = state.isExecuting,
            )
        }
    }

    // 危险命令确认：挂在面板根部，确认后才真正进入执行链。
    state.dangerousCommand?.let { command ->
        DangerousCommandDialog(
            command = command,
            onConfirm = state::confirmDangerous,
            onDismiss = state::dismissDangerous,
        )
    }
}
