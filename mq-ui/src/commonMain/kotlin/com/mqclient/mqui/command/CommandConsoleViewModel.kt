package com.mqclient.mqui.command

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.mqclient.mqui.command.model.CommandLine
import com.mqclient.mqui.command.model.CommandResult
import com.mqclient.mqui.command.model.CommandStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * 命令控制台的桥接层（UI ↔ [CommandExecutor]）。按项目约定不用 androidx ViewModel
 * （工程无该依赖）：`@Stable` 类持有 Compose 状态，协程 scope 由组合侧
 * （rememberCoroutineScope）注入 —— cancel 收掉自己的 Job，不影响 scope 上别的协程。
 *
 * 状态列语义：只反映**最近一次执行**；文本任何编辑都重置全部状态（注释行 / 空行
 * 不动），输出区结果保留。
 *
 * 执行编排（顺序逐条、跳过注释空行、取消在条目边界生效）在本类里完成；
 * 单条命令的展开 / 发送 / 格式化在实现方（[CommandExecutor.execute]）。
 */
@Stable
class CommandConsoleViewModel(
    private val config: CommandConsoleConfig,
    private val scope: CoroutineScope,
) {

    /** 编辑器内容（含光标 / 选中态），用 [TextFieldValue] 以支持「执行选中文本」。 */
    var textFieldValue: TextFieldValue by mutableStateOf(TextFieldValue(""))
        private set

    /** 每行的执行状态（下标 = 行号 - 1），与 [textFieldValue] 行数对齐。 */
    var statuses: List<CommandStatus> by mutableStateOf(emptyList())
        private set

    /** 最近一次执行的结果（按完成顺序追加）。 */
    var results: List<CommandResult> by mutableStateOf(emptyList())
        private set

    var isExecuting: Boolean by mutableStateOf(false)
        private set

    /** 待确认的危险命令文本；非空时 UI 弹确认对话框。 */
    var dangerousCommand: String? by mutableStateOf(null)
        private set

    /** 选中执行时待确认的危险命令来源文本与行偏移（见 [execute]）。 */
    private var pendingSource: String? = null
    private var pendingLineOffset: Int = 0

    private var job: Job? = null

    /** 编辑器文本 / 选中态变更：更新内容并整列重置状态。 */
    fun onValueChange(newValue: TextFieldValue) {
        textFieldValue = newValue
        statuses = List(newValue.text.lines().size) { CommandStatus.NOT_EXECUTED }
    }

    /**
     * 「打开文件」追加到输入区：用换行分隔、不覆盖，光标移到末尾。
     * 选中态与原有内容保留（仅末尾追加）。
     */
    fun appendText(content: String) {
        val current = textFieldValue.text
        val newText = if (current.isEmpty()) content else "$current\n$content"
        textFieldValue = TextFieldValue(text = newText, selection = TextRange(newText.length))
        statuses = List(newText.lines().size) { CommandStatus.NOT_EXECUTED }
    }

    /**
     * 执行入口：没有可执行命令时忽略；先过危险命令检查。
     * 若输入区有选中文本，只把选中部分作为执行源（[pendingLineOffset] 记下来用于
     * 状态列对齐），否则提交全文。选中态与内容都不清除。
     */
    fun execute() {
        if (isExecuting) return
        val tfv = textFieldValue
        // selection 在反向选择（从后往前拖）时 start > end，必须用 min/max 归一，
        // 否则 substring(start, end) 会抛 StringIndexOutOfBoundsException。
        val selStart = tfv.selection.min
        val selEnd = tfv.selection.max
        val (source, lineOffset) = if (selStart != selEnd) {
            tfv.text.substring(selStart, selEnd) to
                tfv.text.substring(0, selStart).count { it == '\n' }
        } else {
            tfv.text to 0
        }
        val parsed = config.commandParser.parse(source)
        if (parsed.all { it.status == CommandStatus.SKIPPED }) return
        val dangerous = parsed
            .firstOrNull { it.status != CommandStatus.SKIPPED && config.commandParser.isDangerous(it.text) }
            ?.text
        if (dangerous != null) {
            pendingSource = source
            pendingLineOffset = lineOffset
            dangerousCommand = dangerous
            return
        }
        start(parsed, lineOffset)
    }

    /** 危险命令确认框点了「确认执行」。 */
    fun confirmDangerous() {
        dangerousCommand = null
        val source = pendingSource ?: textFieldValue.text
        val offset = pendingLineOffset
        pendingSource = null
        start(config.commandParser.parse(source), offset)
    }

    fun dismissDangerous() {
        dangerousCommand = null
        pendingSource = null
    }

    /** 取消：当条命令跑完即停（网络调用不响应中断），后续条目不再执行。 */
    fun cancel() {
        job?.cancel()
        job = null
        isExecuting = false
    }

    /** 清空编辑器与结果。 */
    fun clear() {
        textFieldValue = TextFieldValue("")
        statuses = emptyList()
        results = emptyList()
    }

    private fun start(
        parsed: List<CommandLine>,
        lineOffset: Int,
    ) {
        job = scope.launch {
            isExecuting = true
            results = emptyList()
            statuses = List(textFieldValue.text.lines().size) { CommandStatus.NOT_EXECUTED }
            parsed.forEachIndexed { index, line ->
                if (line.status == CommandStatus.SKIPPED) return@forEachIndexed
                // 条目边界检查取消：cancel 后当条跑完即停，已执行条目照常上报。
                currentCoroutineContext().ensureActive()

                val lineNumber = lineOffset + index + 1
                setStatus(lineNumber, CommandStatus.EXECUTING)
                try {
                    val result = config.commandExecutor.execute(line.text, lineNumber)
                    results = results + result
                    setStatus(result.lineNumber, if (result.success) CommandStatus.SUCCESS else CommandStatus.FAILED)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val result = CommandResult(
                        lineNumber = lineNumber,
                        command = line.text,
                        success = false,
                        output = "",
                        duration = 0,
                        errorMessage = e.message ?: e::class.simpleName,
                    )
                    results = results + result
                    setStatus(lineNumber, CommandStatus.FAILED)
                }
            }
            isExecuting = false
        }
    }

    private fun setStatus(lineNumber: Int, status: CommandStatus) {
        val index = lineNumber - 1
        if (index !in statuses.indices) return
        statuses = statuses.toMutableList().apply { this[index] = status }
    }
}

/** 供 UI 从 [CommandLine] 列表角度读取（对齐状态列 / 行号列的渲染接口）。 */
val CommandConsoleViewModel.commandLines: List<CommandLine>
    get() = textFieldValue.text.lines().mapIndexed { index, line ->
        CommandLine(text = line, status = statuses.getOrElse(index) { CommandStatus.NOT_EXECUTED })
    }
