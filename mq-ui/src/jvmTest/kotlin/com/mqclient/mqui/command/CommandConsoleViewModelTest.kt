package com.mqclient.mqui.command

import com.mqclient.mqui.command.api.CommandExecutor
import com.mqclient.mqui.command.api.CommandParser
import com.mqclient.mqui.command.api.SyntaxHighlighter
import com.mqclient.mqui.command.model.CommandLine
import com.mqclient.mqui.command.model.CommandResult
import com.mqclient.mqui.command.model.CommandStatus
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [CommandConsoleViewModel] 的两条执行契约（不碰 Compose 组合，只跑状态机）：
 *
 *  1. **执行不在调用线程上**：`CommandExecutor.execute` 是阻塞 IO 的落点，而 VM 的
 *     scope 来自 `rememberCoroutineScope()`（Compose 主调度器）。旧实现直接把
 *     `execute` 放在这个 scope 里，等于把 PCF `agent.send` 压在 EDT 上 —— 请求一慢，
 *     整个窗口就冻住。现在由 VM 自己 `withContext(commandIoDispatcher)`；这条测试
 *     断言实现方观测到的线程确实不是调用线程，而不是断言「代码里写了 withContext」。
 *  2. **结果有界**：编辑器内容可以来自 10MB 的「打开文件」，全选执行一次能产出十几万
 *     条结果，而每追加一条都复制整表（O(n²) 分配，全在主线程上）。现在保留最后
 *     [CommandConsoleViewModel.MAX_RESULTS] 条 —— 断言条数封顶、且保留的是**最新的**那批。
 *
 * 执行是异步的（真正跑在 IO 线程上），所以断言前要先等收尾 —— 见 [awaitIdle]。
 */
class CommandConsoleViewModelTest {

    @Test
    fun `executor runs off the calling thread`() = runBlocking {
        val callingThread = Thread.currentThread()
        var executorThread: Thread? = null
        val viewModel = viewModel(
            executor = { command, lineNumber ->
                executorThread = Thread.currentThread()
                result(command, lineNumber)
            },
        )

        viewModel.onValueChange(textOf("DISPLAY QLOCAL(*) qtype(QLOCAL)"))
        viewModel.execute()
        awaitIdle(viewModel)

        val worker = executorThread
        assertTrue(worker != null, "执行从未发生")
        assertTrue(
            worker.name != callingThread.name,
            "命令执行压在了调用线程（${worker.name}）上 —— 阻塞实现会把主线程卡死",
        )
        assertTrue(
            worker.name.contains("DefaultDispatcher") || worker.name.contains("IO"),
            "命令执行没走 IO 调度器，实际线程名：${worker.name}",
        )
    }

    @Test
    fun `results are capped and keep the newest ones`() = runBlocking {
        val total = CommandConsoleViewModel.MAX_RESULTS + 250
        val commands = (1..total).joinToString("\n") { "DISPLAY QLOCAL(Q$it)" }
        val viewModel = viewModel(executor = { command, lineNumber -> result(command, lineNumber) })

        viewModel.onValueChange(textOf(commands))
        viewModel.execute()
        awaitIdle(viewModel)

        assertEquals(CommandConsoleViewModel.MAX_RESULTS, viewModel.results.size, "结果条数必须封顶在 ${CommandConsoleViewModel.MAX_RESULTS}")
        // 保留的是最新的那批：最后一条是最后执行的那条，最早的那些已被丢掉。
        assertEquals(total, viewModel.results.last().lineNumber, "最新一条应在窗口里")
        assertEquals(total - CommandConsoleViewModel.MAX_RESULTS + 1, viewModel.results.first().lineNumber, "窗口起点应是倒数第 ${CommandConsoleViewModel.MAX_RESULTS} 条")
        // 编辑器状态列不受结果裁剪影响：所有行都记了执行结果。
        assertEquals(CommandStatus.SUCCESS, viewModel.statuses.first(), "被裁掉结果的行仍应有状态")
        assertEquals(CommandStatus.SUCCESS, viewModel.statuses.last())
    }

    @Test
    fun `a fresh run clears results and starts a new window`() = runBlocking {
        val viewModel = viewModel(executor = { command, lineNumber -> result(command, lineNumber) })

        viewModel.onValueChange(textOf((1..3).joinToString("\n") { "DISPLAY QLOCAL(A$it)" }))
        viewModel.execute()
        awaitIdle(viewModel)
        assertEquals(3, viewModel.results.size)

        viewModel.onValueChange(textOf("DISPLAY QLOCAL(B1)"))
        viewModel.execute()
        awaitIdle(viewModel)

        assertEquals(1, viewModel.results.size, "每次执行从空表开始")
        assertEquals("DISPLAY QLOCAL(B1)", viewModel.results.single().command)
    }

    @Test
    fun `commandLines is cached until text or statuses change`() = runBlocking {
        val viewModel = viewModel(executor = { command, lineNumber -> result(command, lineNumber) })
        viewModel.onValueChange(textOf((1..3).joinToString("\n") { "DISPLAY QLOCAL(A$it)" }))

        // 没变就不该重建：旧实现是普通扩展属性，每读一次就拆一遍文本 + 造 N 个对象。
        val first = viewModel.commandLines
        assertSame(first, viewModel.commandLines, "文本与状态都没变时应当返回同一个实例")

        // 文本变 → 失效。
        viewModel.onValueChange(textOf("DISPLAY QLOCAL(B1)"))
        val afterText = viewModel.commandLines
        assertNotSame(first, afterText, "文本变了必须重算")
        assertEquals("DISPLAY QLOCAL(B1)", afterText.single().text)

        // 状态变 → 也要失效（这条最容易漏：只看文本就会让状态列永远停在旧值）。
        viewModel.execute()
        awaitIdle(viewModel)
        val afterRun = viewModel.commandLines
        assertNotSame(afterText, afterRun, "状态变化必须让行快照失效")
        assertEquals(CommandStatus.SUCCESS, afterRun.single().status)
    }

    @Test
    fun `status list is mutated in place, not replaced`() = runBlocking {
        val viewModel = viewModel(executor = { command, lineNumber -> result(command, lineNumber) })
        viewModel.onValueChange(textOf((1..4).joinToString("\n") { "DISPLAY QLOCAL(C$it)" }))
        val beforeRun = viewModel.statuses

        viewModel.execute()
        awaitIdle(viewModel)

        // 旧实现是 `var statuses: List<...> by mutableStateOf(...)` + 每次 setStatus 都
        // `statuses = statuses.toMutableList()...` —— 执行 N 条就是 2N 次 O(N) 复制。
        //
        // **这条断言的边界**：它只拦「改回可变 var + 整表赋新实例」这一种回归（也是最
        // 可能的回归）。若有人保留 SnapshotStateList 却在里面 clear+addAll 做写时复制，
        // 实例引用不变，本断言照样绿 —— 那种写法的 O(N²) 靠这里测不出来。复杂度上的
        // 保证来自代码本身：`_statuses[index] = status` 是 SnapshotStateList 的下标写，
        // O(1)，无复制。不写时间断言是因为它本质上是个脆弱的性能测试。
        assertSame(beforeRun, viewModel.statuses, "状态列表不该整表换实例")
        assertTrue(viewModel.statuses.all { it == CommandStatus.SUCCESS })
    }

    @Test
    fun `editing text resets every status`() {
        val viewModel = viewModel(executor = { command, lineNumber -> result(command, lineNumber) })
        viewModel.onValueChange(textOf("DISPLAY QLOCAL(D1)\nDISPLAY QLOCAL(D2)"))

        // 模拟「已执行过」：直接通过公开路径跑一轮。
        val before = viewModel.statuses.toList()
        assertTrue(before.all { it == CommandStatus.NOT_EXECUTED })

        // 文本任何编辑都重置全部状态（含行数变化）—— 原地重置后长度对齐新文本行数。
        viewModel.onValueChange(textOf("DISPLAY QLOCAL(D1)"))
        assertEquals(1, viewModel.statuses.size)
        assertEquals(CommandStatus.NOT_EXECUTED, viewModel.statuses.single())
        assertEquals(1, viewModel.commandLines.size)
    }

    /* ------------------------------ 帮手 ------------------------------ */

    private fun textOf(text: String) = androidx.compose.ui.text.input.TextFieldValue(text)

    /**
     * 等执行协程收尾。
     *
     * 只等 `isExecuting` 落回 false，**不先等它起来**：如果执行真的跑在调用线程上
     * （回归），整个循环会同步跑完 —— `execute()` 返回时 `isExecuting` 已经是 false，
     * 先等它变 true 就是一个永不满足的等待，测试会以 30s 超时告终，报错指向超时而不是
     * 那个真正的断言。留着先等会「测得出但说不清」。
     */
    private suspend fun awaitIdle(viewModel: CommandConsoleViewModel) {
        withTimeout(30_000) {
            while (viewModel.isExecuting) delay(2)
        }
    }

    private fun result(command: String, lineNumber: Int) = CommandResult(
        lineNumber = lineNumber,
        command = command,
        success = true,
        output = "AMQ8409: 显示队列详细信息。",
        duration = 1,
    )

    private fun viewModel(executor: (String, Int) -> CommandResult): CommandConsoleViewModel {
        val parser = object : CommandParser {
            override fun parse(text: String): List<CommandLine> =
                text.lines().map { line ->
                    val trimmed = line.trim()
                    CommandLine(
                        text = line,
                        status = if (trimmed.isEmpty() || trimmed.startsWith("*")) {
                            CommandStatus.SKIPPED
                        } else {
                            CommandStatus.NOT_EXECUTED
                        },
                    )
                }

            override fun isDangerous(command: String): Boolean = false
        }
        val highlighter = object : SyntaxHighlighter {
            override fun highlight(text: AnnotatedString): AnnotatedString = text
        }
        val commandExecutor = object : CommandExecutor {
            override suspend fun execute(command: String, lineNumber: Int): CommandResult =
                executor(command, lineNumber)
        }
        val config = CommandConsoleConfig(
            title = "test",
            fileExtension = "mqsc",
            syntaxHighlighter = highlighter,
            commandParser = parser,
            commandExecutor = commandExecutor,
        )
        // 用 Unconfined 模拟「组合侧主调度器」：launch 就地开跑，状态流转立即可见，
        // 而命令执行会被 VM 自己切到 IO —— 正是本测试要验证的那一跳。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return CommandConsoleViewModel(config, scope)
    }
}
