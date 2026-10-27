package com.mqclient.mqui.command

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
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
import kotlinx.coroutines.withContext

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

    /**
     * 每行的执行状态（下标 = 行号 - 1），与 [textFieldValue] 行数对齐。
     *
     * 用 [mutableStateListOf] 而不是不可变 List：状态是**逐条**变的（每条命令先
     * EXECUTING、再 SUCCESS/FAILED），旧实现每次 `statuses = statuses.toMutableList()...`
     * 都要复制整表 —— 一次执行 N 条命令就是 2N 次 O(N) 复制，即 O(N²)，全部发生在
     * UI 调度器上。一个 10MB 的命令文件全选执行（最坏十几万行）会卡死窗口。
     *
     * 原地改一格是 O(1)，且只让读这一格的地方失效，不惊动整列。
     * 对外只暴露只读视图 `List<CommandStatus>`（getter 返回同一个实例，写只能走本类）。
     */
    private val _statuses = mutableStateListOf<CommandStatus>()

    val statuses: List<CommandStatus> get() = _statuses

    /**
     * 编辑器渲染用的行列表（行文本 + 状态），**派生状态缓存**。
     *
     * 旧实现是 `textFieldValue.text.lines().mapIndexed {...}` 的普通扩展属性：每读一次
     * 就拆一次字符串 + 造 N 个 [CommandLine]。而 `CommandConsolePanel` 每次重组要读
     * **两遍**（一次给编辑器、一次给状态栏的命令计数），且任何与文本无关的重组
     * （拖分隔条、切换 Tab、snackbar）都会再全量重算 —— 250k 行文件每次按键 50 万个对象。
     *
     * [derivedStateOf] 把依赖限定为 [textFieldValue] 与 [_statuses]：两者不变时直接返回
     * 同一个 List 实例（既是缓存，也让下游拿到稳定引用）；变一次只算一次，同一帧内的
     * 两次读取共用一个结果。
     */
    private val linesState = derivedStateOf {
        val statusesNow = _statuses
        textFieldValue.text.lines().mapIndexed { index, line ->
            CommandLine(
                text = line,
                status = statusesNow.getOrElse(index) { CommandStatus.NOT_EXECUTED },
            )
        }
    }

    /** 供 UI 按行读取（对齐状态列 / 行号列的渲染接口）。缓存语义见 [linesState]。 */
    val commandLines: List<CommandLine> get() = linesState.value

    /**
     * 最近一次执行的结果（按完成顺序追加），**最多 [MAX_RESULTS] 条** —— 超出丢弃最早的。
     *
     * 上限的意义不在「长时间运行」：本列表在每次 [start] 开头就清空，跨多次执行不会
     * 累积。无界的是**单次执行**：编辑器里的内容可以来自「打开文件」（上限 10MB，
     * MQSC 命令平均几十字节，即最坏十几万行），只要用户全选执行一次，就会收十几万个
     * 带完整 PCF 回显文本的 [CommandResult]。而每收一条都要 `results = results + r`
     * 复制整表 —— 那是 O(n²) 的分配，全部发生在 UI 调度器上，几万条就足以让窗口卡死。
     *
     * 顺带的可见变化：状态栏的成功/失败计数与「导出结果」只统计 **保留的** 条目。
     * 这是有意为之 —— 显示的就是统计的，不会出现「计数 800 而输出区只有 500 条」。
     */
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

    internal companion object {
        /** 输出区保留的最大结果条数（超出丢弃最早的）。见 [results]。 */
        const val MAX_RESULTS = 500
    }

    /** 编辑器文本 / 选中态变更：更新内容并整列重置状态。 */
    fun onValueChange(newValue: TextFieldValue) {
        textFieldValue = newValue
        resetStatuses(newValue.text.lines().size)
    }

    /**
     * 「打开文件」追加到输入区：用换行分隔、不覆盖，光标移到末尾。
     * 选中态与原有内容保留（仅末尾追加）。
     */
    fun appendText(content: String) {
        val current = textFieldValue.text
        val newText = if (current.isEmpty()) content else "$current\n$content"
        textFieldValue = TextFieldValue(text = newText, selection = TextRange(newText.length))
        resetStatuses(newText.lines().size)
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
        _statuses.clear()
        results = emptyList()
    }

    private fun start(
        parsed: List<CommandLine>,
        lineOffset: Int,
    ) {
        job = scope.launch {
            isExecuting = true
            results = emptyList()
            resetStatuses(textFieldValue.text.lines().size)
            // 有界缓冲：与 [MAX_RESULTS] 同寿。用 ArrayDeque 而不是 list + removeAt(0)
            // —— 后者每次淘汰都要整体前移，在满窗情况下是每条 O(n)。
            val buffer = ArrayDeque<CommandResult>()
            parsed.forEachIndexed { index, line ->
                if (line.status == CommandStatus.SKIPPED) return@forEachIndexed
                // 条目边界检查取消：cancel 后当条跑完即停，已执行条目照常上报。
                currentCoroutineContext().ensureActive()

                val lineNumber = lineOffset + index + 1
                setStatus(lineNumber, CommandStatus.EXECUTING)
                try {
                    // 执行必在 IO 上：实现方是阻塞 IO（PCF agent.send / 落库 / 文件），
                    // 而本协程跑在组合侧的 scope（Compose 主调度器）。这条保证放在桥接
                    // 层而不是交给每个 CommandExecutor —— 接口对所有人开放，漏一个就是
                    // 整窗冻结。withContext 同时把取消语义接过来：cancel() 后这里原样
                    // 上抛 CancellationException，不会被下面的 catch 吃掉。
                    val result = withContext(commandIoDispatcher) {
                        config.commandExecutor.execute(line.text, lineNumber)
                    }
                    appendResult(buffer, result)
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
                    appendResult(buffer, result)
                    setStatus(lineNumber, CommandStatus.FAILED)
                }
            }
            isExecuting = false
        }
    }

    /**
     * 追加一条结果并发布：保留最后 [MAX_RESULTS] 条。
     *
     * 每次都要发布一个新 List 实例 —— Compose 靠引用变化触发重组，直接改 buffer
     * 不会让输出区刷新。`toList()` 的代价是 O([MAX_RESULTS])，有界。
     */
    private fun appendResult(buffer: ArrayDeque<CommandResult>, result: CommandResult) {
        buffer.addLast(result)
        if (buffer.size > MAX_RESULTS) buffer.removeFirst()
        results = buffer.toList()
    }

    private fun setStatus(lineNumber: Int, status: CommandStatus) {
        val index = lineNumber - 1
        if (index !in _statuses.indices) return
        // 同值不写：省一次无效失效（EXECUTING → EXECUTING 在批量里不常见，但状态列
        // 每格都被观察，多写一次就多一轮重组）。
        if (_statuses[index] == status) return
        _statuses[index] = status
    }

    /**
     * 整列重置为 [lineCount] 个 NOT_EXECUTED。
     *
     * 文本编辑会走到这里（一次 O(N)，与改前等价）。用 [Snapshot.withMutableSnapshot]
     * 把「清空 + 重建」包成**一次原子变更**：否则中间那个空列状态会被读到 —— 状态列
     * 会闪一帧空白，而且 N 次写入各自推进一次全局快照，比一次应用慢得多。
     */
    private fun resetStatuses(lineCount: Int) {
        Snapshot.withMutableSnapshot {
            _statuses.clear()
            repeat(lineCount) { _statuses.add(CommandStatus.NOT_EXECUTED) }
        }
    }
}
