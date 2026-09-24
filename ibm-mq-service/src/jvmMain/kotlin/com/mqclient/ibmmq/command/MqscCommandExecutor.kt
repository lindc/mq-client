package com.mqclient.ibmmq.command

import com.mqclient.ibmmq.ibmMqLog
import com.mqclient.mqui.command.api.CommandExecutor
import com.mqclient.mqui.command.model.CommandResult

/**
 * mq-ui 通用命令控制台的 IBM MQ 适配：单条 MQSC 命令 → [MqscExecutor]。
 * 展开 / 发送 / PCF 格式化 / MQRC 翻译 / 历史记录全部复用 [MqscExecutor.executeSingle]
 * 的既有逻辑，这里只做接口形状的转换。
 *
 * 一个实例绑定一条连接（connectionId）；由 ibm-mq-ui 按 Tab 组装进
 * [com.mqclient.mqui.command.CommandConsoleConfig]。
 */
class MqscCommandExecutor(
    private val connectionId: String,
    private val mqscExecutor: MqscExecutor,
) : CommandExecutor {

    override suspend fun execute(command: String, lineNumber: Int): CommandResult {
        // 缩写展开在适配层做（executeSingle 约定收到已展开文本）——
        // expand 幂等，executeSingle 内部链路不再展开。
        val expanded = MqscCommandExpander.expand(command)
        if (expanded != command) {
            ibmMqLog.debug("mqsc expanded: line=$lineNumber '${command.trim()}' -> '${expanded.trim()}'")
        }
        val result = mqscExecutor.executeSingle(connectionId, expanded, lineNumber)
        return CommandResult(
            lineNumber = result.lineNumber,
            command = command,
            expandedCommand = expanded.takeIf { it != command },
            success = result.success,
            output = result.output,
            duration = result.duration,
            errorCode = result.errorCode,
            errorMessage = result.errorMessage,
        )
    }
}
