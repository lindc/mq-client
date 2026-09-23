package com.mqclient.ibmmq.ui.command

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.ibmmq.command.MqscExecutor
import com.mqclient.ibmmq.command.MqscParser
import com.mqclient.ibmmq.command.model.MqscResult
import com.mqclient.ibmmq.ui.command.model.CommandLine
import com.mqclient.ibmmq.ui.command.model.CommandStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * MQSC 控制台的桥接层（UI ↔ [MqscExecutor]）。按项目约定不用 androidx ViewModel
 * （工程无该依赖），与 ai-ui 的 AiPanelState 同一模式：`@Stable` 类持有 Compose 状态，
 * 协程 scope 由组合侧（rememberCoroutineScope）注入 —— cancel 收掉自己的 Job，
 * 不影响 scope 上别的协程。
 *
 * 状态列语义：只反映**最近一次执行**；文本任何编辑都重置全部状态（注释行 / 空行
 * 不动），输出区结果保留。
 */
@Stable
class MqscConsoleViewModel internal constructor(
    private val connectionId: String,
    private val executor: MqscExecutor,
    private val scope: CoroutineScope,
) {

    /** 编辑器全文（多行）。 */
    var text: String by mutableStateOf("")
        private set

    /** 每行的执行状态（下标 = 行号 - 1），与 [text] 行数对齐。 */
    var statuses: List<CommandStatus> by mutableStateOf(emptyList())
        private set

    /** 最近一次执行的结果（按完成顺序追加）。 */
    var results: List<MqscResult> by mutableStateOf(emptyList())
        private set

    var isExecuting: Boolean by mutableStateOf(false)
        private set

    /** 待确认的危险命令文本；非空时 UI 弹确认对话框。 */
    var dangerousCommand: String? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /** 编辑器文本变更：更新文本并整列重置状态。 */
    fun onTextChange(newText: String) {
        text = newText
        statuses = List(newText.lines().size) { CommandStatus.NOT_EXECUTED }
    }

    /** 执行入口：没有可执行命令时忽略；先过危险命令检查。 */
    fun execute() {
        if (isExecuting) return
        val parsed = MqscParser.parse(text)
        if (parsed.all { it.isComment || it.isBlank }) return
        val dangerous = parsed
            .firstOrNull { !it.isComment && MqscParser.isDangerous(it.text) }
            ?.text
        if (dangerous != null) {
            dangerousCommand = dangerous
            return
        }
        start(parsed)
    }

    /** 危险命令确认框点了「确认执行」。 */
    fun confirmDangerous() {
        dangerousCommand = null
        start(MqscParser.parse(text))
    }

    fun dismissDangerous() {
        dangerousCommand = null
    }

    /** 取消：当条命令跑完即停（PCF 调用不响应中断），后续条目不再执行。 */
    fun cancel() {
        job?.cancel()
        job = null
        isExecuting = false
    }

    /** 清空编辑器与结果。 */
    fun clear() {
        text = ""
        statuses = emptyList()
        results = emptyList()
    }

    private fun start(parsed: List<com.mqclient.ibmmq.command.model.MqscCommand>) {
        job = scope.launch {
            isExecuting = true
            results = emptyList()
            statuses = List(text.lines().size) { CommandStatus.NOT_EXECUTED }
            executor.execute(
                connectionId = connectionId,
                commands = parsed,
                onCommandStart = { line -> setStatus(line, CommandStatus.EXECUTING) },
                onResult = { result ->
                    results = results + result
                    setStatus(result.lineNumber, if (result.success) CommandStatus.SUCCESS else CommandStatus.FAILED)
                },
            )
            isExecuting = false
        }
    }

    private fun setStatus(lineNumber: Int, status: CommandStatus) {
        val index = lineNumber - 1
        if (index !in statuses.indices) return
        statuses = statuses.toMutableList().apply { this[index] = status }
    }
}

/** 供 UI 从 [CommandLine] 列表角度读取（对齐 spec 的渲染接口）。 */
val MqscConsoleViewModel.commandLines: List<CommandLine>
    get() = text.lines().mapIndexed { index, line ->
        CommandLine(text = line, status = statuses.getOrElse(index) { CommandStatus.NOT_EXECUTED })
    }
