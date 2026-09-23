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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import com.mqclient.uicommon.LocalSnackbarHostState
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val MIN_RATIO = 0.2f
private const val MAX_RATIO = 0.8f

/** 打开文件大小上限：10MB。 */
private const val MAX_OPEN_FILE_BYTES = 10 * 1024 * 1024

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

    // 复用 app-ui AppRoot 通过 CompositionLocal 下发的应用级 Snackbar 宿主状态。
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    // 「打开文件」：单选文件，不过滤后缀（FileKitType.File() 空扩展名清单）。
    val pickerLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(),
        dialogSettings = FileKitDialogSettings.createDefault(),
        onResult = { file -> onOpenFileResult(file, state, snackbarHostState, scope) },
    )
    // 「导出结果」：只给 suggestedName，不限定后缀（defaultExtension / allowedExtensions 留默认 null）。
    val saverLauncher = rememberFileSaverLauncher(
        dialogSettings = FileKitDialogSettings.createDefault(),
        onResult = { file -> onExportResult(file, state, snackbarHostState, scope) },
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 分隔条回调给的是像素增量；比例换算要面板总高，在这里换算最合适。
        val heightPx = constraints.maxHeight.toFloat()
        Column(modifier = Modifier.fillMaxSize()) {
                MqscToolbar(
                    isExecuting = state.isExecuting,
                    onExecute = state::execute,
                    onCancel = state::cancel,
                    onClear = state::clear,
                    onOpenFile = { pickerLauncher.launch() },
                    onExport = { saverLauncher.launch(suggestedName = "command-output", defaultExtension = null, allowedExtensions = null) },
                    hasOutput = state.results.isNotEmpty(),
                )
                Box(modifier = Modifier.fillMaxWidth().weight(splitRatio)) {
                    MqscEditor(
                        commands = state.commandLines,
                        textFieldValue = state.textFieldValue,
                        onValueChange = state::onValueChange,
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

/**
 * 「打开文件」结果处理：校验大小与 UTF-8 可解码性，通过后把内容追加到输入区末尾。
 * 所有 suspend 调用（readBytes）都在 [scope] 协程里执行，不在组合主线程。
 */
private fun onOpenFileResult(
    file: PlatformFile?,
    state: MqscConsoleViewModel,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    if (file == null) return
    scope.launch {
        runCatching {
            val bytes = file.readBytes()
            require(bytes.size <= MAX_OPEN_FILE_BYTES) {
                "文件过大（约 ${bytes.size / 1024}KB），上限 ${MAX_OPEN_FILE_BYTES / 1024}KB"
            }
            // 严格 UTF-8 解码：遇到非法字节抛 CharacterCodingException，便于提示「解码失败」。
            val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }.onSuccess { content ->
            state.appendText(content)
        }.onFailure { e ->
            val msg = if (e is CharacterCodingException) "文件不是合法的 UTF-8 文本，无法读取" else "无法读取文件：${e.message ?: e::class.simpleName}"
            snackbarHostState.showSnackbar(msg)
        }
    }
}

/**
 * 「导出结果」结果处理：把输出区内容写入用户选择的文件。
 * writeString 是 suspend 函数，必须在 [scope] 协程里调用。
 */
private fun onExportResult(
    file: PlatformFile?,
    state: MqscConsoleViewModel,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    if (file == null) return
    scope.launch {
        runCatching {
            file.writeString(formatOutput(state.results))
        }.onSuccess {
            snackbarHostState.showSnackbar("已导出到 ${file.file.name}")
        }.onFailure { e ->
            snackbarHostState.showSnackbar("导出失败：${e.message ?: e::class.simpleName}")
        }
    }
}

/** 把执行结果拼成可读的多行文本。 */
private fun formatOutput(results: List<com.mqclient.ibmmq.command.model.MqscResult>): String {
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
