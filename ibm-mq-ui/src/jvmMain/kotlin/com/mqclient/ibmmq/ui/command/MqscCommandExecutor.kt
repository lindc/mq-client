package com.mqclient.ibmmq.ui.command

import com.mqclient.core.log.Log
import com.mqclient.ibmmq.command.MqscCommandExpander
import com.mqclient.ibmmq.command.MqscExecutor
import com.mqclient.mqui.command.api.CommandExecutor
import com.mqclient.mqui.command.model.CommandResult

private val log = Log.withTag("ibm-mq-ui")

/**
 * mq-ui 通用命令控制台的 IBM MQ 适配：单条 MQSC 命令 → [MqscExecutor]。
 * 展开 / 发送 / PCF 格式化 / MQRC 翻译 / 历史记录全部复用
 * [MqscExecutor.executeSingle] 的既有逻辑，这里只做接口形状的转换。
 *
 * 一个实例绑定一条连接（connectionId）；由 [IbmMqCommandConsolePanel] 组装进
 * `CommandConsoleConfig`。
 *
 * 为什么在 `ibm-mq-ui` 而不是 `ibm-mq-service`：它实现的是 UI 契约（`CommandExecutor`
 * 来自 mq-ui，而 mq-ui 以 `api(compose.ui)` 暴露 Compose 类型）。真正的执行链路
 * （[MqscExecutor] / `MqscPcfSender`）仍在 service 层，本类只是形状转换。
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
            log.debug("mqsc expanded: line=$lineNumber '${command.trim()}' -> '${expanded.trim()}'")
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
