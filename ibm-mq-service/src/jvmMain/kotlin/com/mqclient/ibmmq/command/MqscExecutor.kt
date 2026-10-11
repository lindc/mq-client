package com.mqclient.ibmmq.command

import com.ibm.mq.pcf.PCFException
import com.mqclient.ibmmq.command.model.MqscCommand
import com.mqclient.ibmmq.command.model.MqscHistoryItem
import com.mqclient.ibmmq.command.model.MqscResult
import com.mqclient.ibmmq.ibmMqLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * MQSC 命令执行编排：逐条顺序执行（MQSC 控制台语义 —— 后一条常依赖前一条），
 * 注释与空行跳过；每条前后回调给 UI 更新状态列，结果与历史各走各的出口。
 *
 * 取消：跑在调用方的协程里，逐条边界检查 [ensureActive] —— cancel 后当条命令
 * 跑完即停（PCF 调用本身不响应中断），已执行的条目照常上报。
 */
class MqscExecutor(
    private val pcfExecutor: PcfExecutor,
    private val historyRepository: MqscHistoryRepository,
) {

    companion object {
        /** 日志中命令文本的最大长度（防止超长 DISPLAY 输出刷屏）。 */
        private const val MAX_LOG_COMMAND_LENGTH = 300
    }

    /**
     * 顺序执行 [commands]（跳过注释 / 空行）。
     *
     * @param onCommandStart 命令开始执行（行号）—— UI 把状态列标成 EXECUTING。
     * @param onResult 单条完成 —— UI 追加输出区条目并更新状态列。
     */
    suspend fun execute(
        connectionId: String,
        commands: List<MqscCommand>,
        onCommandStart: (Int) -> Unit,
        onResult: (MqscResult) -> Unit,
    ) {
        commands.forEach { cmd ->
            if (cmd.isComment || cmd.isBlank) return@forEach
            currentCoroutineContext().ensureActive()

            onCommandStart(cmd.lineNumber)
            // 缩写展开（dis q(*) → DISPLAY QLOCAL(*)）：结果与历史都记展开后的
            // 文本 —— 那是真正发给队列管理器的内容，回显对齐 runmqsc 的习惯。
            val expanded = MqscCommandExpander.expand(cmd.text)
            if (expanded != cmd.text) {
                ibmMqLog.debug("mqsc expanded: line=${cmd.lineNumber} '${cmd.text.trim()}' -> '${expanded.trim()}'")
            }
            val result = executeSingle(connectionId, expanded, cmd.lineNumber)
            onResult(result)
        }
    }

    /**
     * 执行单条（已展开的）命令并落历史。批量编排（[execute]）与 mq-ui 通用控制台的
     * 适配层（MqscCommandExecutor）共用这一个出口，保证格式化 / 错误翻译 / 历史
     * 记录行为一致。
     *
     * 历史两路出口都在这里：内存历史（[MqscHistoryRepository]，含 DISPLAY，供控制台
     * 回看）与写操作审计事件（[MqCommandHistoryEmitter]，DISPLAY 不记，落 mq_command
     * 表）。两路都 runCatching 包裹 —— 记录失败绝不影响命令本身的结果。
     *
     * @param command 已展开的命令文本（调用方负责缩写展开）。
     */
    suspend fun executeSingle(connectionId: String, command: String, lineNumber: Int): MqscResult {
        // 命令文本可能很长，日志里截断；不含密码类字段（MQSC 命令本身极少携带，
        // 若未来有需再按字段脱敏）。
        ibmMqLog.debug(
            "mqsc execute: connection=$connectionId line=$lineNumber command='${command.trim().take(MAX_LOG_COMMAND_LENGTH)}'",
        )
        val requestTime = System.currentTimeMillis()
        val result = executeOne(connectionId, MqscCommand(lineNumber = lineNumber, text = command))
        ibmMqLog.debug(
            "mqsc executed: line=$lineNumber success=${result.success} duration=${result.duration}ms",
        )
        runCatching {
            historyRepository.add(
                MqscHistoryItem(
                    id = 0,
                    connectionId = connectionId,
                    command = command,
                    success = result.success,
                    createdTime = System.currentTimeMillis(),
                ),
            )
        }
        runCatching {
            MqCommandHistoryEmitter.onExecuted(connectionId, command, result, requestTime)
        }
        return result
    }

    private suspend fun executeOne(connectionId: String, cmd: MqscCommand): MqscResult {
        val startTime = System.currentTimeMillis()
        return try {
            val lines = pcfExecutor.executeMqsc(connectionId, cmd.text.trim())
            MqscResult(
                lineNumber = cmd.lineNumber,
                command = cmd.text,
                success = true,
                output = lines.joinToString("\n"),
                duration = System.currentTimeMillis() - startTime,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: PCFException) {
            // 完整留痕：命令服务器只给 reason，全量信息是排障（对照 AMQERR01.LOG）的起点。
            ibmMqLog.warn(
                "mqsc PCFException: line=${cmd.lineNumber} command=${cmd.text} " +
                    "reasonCode=${e.reasonCode} completionCode=${e.completionCode} message=${e.message}",
            )
            MqscResult(
                lineNumber = cmd.lineNumber,
                command = cmd.text,
                success = false,
                output = "",
                duration = System.currentTimeMillis() - startTime,
                errorCode = e.reasonCode,
                errorMessage = MqrcTranslator.translate(e.reasonCode),
            )
        } catch (e: Exception) {
            // 非 PCF 异常（网络断开、空指针等）：同样转成失败结果，但要留 ERROR 痕迹。
            ibmMqLog.error("mqsc failed: line=${cmd.lineNumber} command='${cmd.text.trim().take(MAX_LOG_COMMAND_LENGTH)}' error=${e.message ?: e::class.simpleName}")
            MqscResult(
                lineNumber = cmd.lineNumber,
                command = cmd.text,
                success = false,
                output = "",
                duration = System.currentTimeMillis() - startTime,
                errorMessage = e.message ?: e::class.simpleName ?: "unknown",
            )
        }
    }
}
