package com.mqclient.mqui.command

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import com.mqclient.core.concurrent.runCatchingCancellable
import com.mqclient.mqui.command.file.readCommandText
import com.mqclient.mqui.command.file.writeResultText
import com.mqclient.mqui.command.model.CommandResult
import com.mqclient.uicommon.LocalSnackbarHostState
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.charset.CharacterCodingException

private const val MIN_RATIO = 0.2f
private const val MAX_RATIO = 0.8f

/**
 * 命令控制台主面板（对外唯一入口）：工具栏 / 编辑器（上）/ 可拖动分隔条 /
 * 输出区（下）/ 状态栏。编辑器与输出区的高度比例 [splitRatio] 拖动可调
 * （0.2 - 0.8），跨会话不保留（重开 Tab 回到对半）。
 *
 * 面板不感知任何具体 MQ：解析 / 高亮 / 执行全部来自 [config] 的注入实现。
 */
@Composable
fun CommandConsolePanel(
    config: CommandConsoleConfig,
    state: CommandConsoleViewModel,
    modifier: Modifier = Modifier,
) {
    var splitRatio by remember { mutableStateOf(0.5f) }

    // 复用 app-ui AppRoot 通过 CompositionLocal 下发的应用级 Snackbar 宿主状态。
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    // 「打开文件」：单选文件，按 config.fileExtension 过滤。
    val pickerLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf(config.fileExtension)),
        dialogSettings = FileKitDialogSettings.createDefault(),
        onResult = { file -> onOpenFileResult(file, state, snackbarHostState, scope) },
    )
    // 「导出结果」：只给 suggestedName，不限定后缀。
    val saverLauncher = rememberFileSaverLauncher(
        dialogSettings = FileKitDialogSettings.createDefault(),
        onResult = { file -> onExportResult(file, state, snackbarHostState, scope) },
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 分隔条回调给的是像素增量；比例换算要面板总高，在这里换算最合适。
        val heightPx = constraints.maxHeight.toFloat()
        Column(modifier = Modifier.fillMaxSize()) {
            CommandToolbar(
                isExecuting = state.isExecuting,
                onExecute = state::execute,
                onCancel = state::cancel,
                onClear = state::clear,
                onOpenFile = { pickerLauncher.launch() },
                onExport = {
                    saverLauncher.launch(
                        suggestedName = "command-output",
                        defaultExtension = null,
                        allowedExtensions = null,
                    )
                },
                hasOutput = state.results.isNotEmpty(),
            )
            Box(modifier = Modifier.fillMaxWidth().weight(splitRatio)) {
                CommandEditor(
                    commands = state.commandLines,
                    textFieldValue = state.textFieldValue,
                    onValueChange = state::onValueChange,
                    syntaxHighlighter = config.syntaxHighlighter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            DraggableDivider(
                onDrag = { deltaPx ->
                    splitRatio = (splitRatio + deltaPx / heightPx).coerceIn(MIN_RATIO, MAX_RATIO)
                },
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f - splitRatio)) {
                CommandOutputView(
                    results = state.results,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            CommandStatusBar(
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

/**
 * 「打开文件」结果处理：校验大小与 UTF-8 可解码性，通过后把内容追加到输入区末尾。
 * 所有 suspend 调用（readCommandText）都在 [scope] 协程里执行，不在组合主线程。
 */
private fun onOpenFileResult(
    file: PlatformFile?,
    state: CommandConsoleViewModel,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    if (file == null) return
    scope.launch {
        runCatchingCancellable {
            file.readCommandText()
        }.onSuccess { content ->
            state.appendText(content)
        }.onFailure { e ->
            val msg = when (e) {
                is CharacterCodingException -> stringResource("console_file_not_utf8")
                is IllegalArgumentException -> stringResource("console_file_too_large").withArg(e.message ?: "")
                else -> stringResource("console_file_read_failed").withArg(e.message ?: e::class.simpleName ?: "")
            }
            snackbarHostState.showSnackbar(msg)
        }
    }
}

/**
 * 「导出结果」结果处理：把输出区内容写入用户选择的文件。
 * writeResultText 是 suspend 函数，必须在 [scope] 协程里调用。
 */
private fun onExportResult(
    file: PlatformFile?,
    state: CommandConsoleViewModel,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    if (file == null) return
    scope.launch {
        runCatchingCancellable {
            file.writeResultText(formatOutput(state.results))
        }.onSuccess {
            snackbarHostState.showSnackbar(stringResource("console_exported").withArg(file.name))
        }.onFailure { e ->
            snackbarHostState.showSnackbar(
                stringResource("console_export_failed").withArg(e.message ?: e::class.simpleName ?: ""),
            )
        }
    }
}

/** 把执行结果拼成可读的多行文本。 */
private fun formatOutput(results: List<CommandResult>): String {
    if (results.isEmpty()) return ""
    return results.joinToString("\n\n") { r ->
        buildString {
            append("命令 ${r.lineNumber}: ${r.command}")
            append("\n")
            append(if (r.output.isBlank()) (if (r.success) "(执行成功，无输出)" else "(无输出)") else r.output)
            append("\n")
            append(if (r.success) "SUCCESS" else "FAILED")
            append("  ${r.duration}ms")
            if (!r.success) {
                append("  [${r.errorCode ?: "?"}] ${r.errorMessage ?: ""}".trimEnd())
            }
        }
    }
}
