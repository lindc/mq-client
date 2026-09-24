package com.mqclient.ibmmq.command

import com.mqclient.mqui.command.api.CommandParser
import com.mqclient.mqui.command.model.CommandLine
import com.mqclient.mqui.command.model.CommandStatus

/**
 * mq-ui 通用命令控制台的 IBM MQ 适配：解析逻辑全部委托给既有的
 * [MqscParser]（注释 `*` / 空行标 SKIPPED 但不剔除，危险动词前缀判定不变）。
 */
class MqscCommandParser : CommandParser {

    override fun parse(text: String): List<CommandLine> =
        MqscParser.parse(text).map { cmd ->
            CommandLine(
                text = cmd.text,
                status = when {
                    cmd.isBlank || cmd.isComment -> CommandStatus.SKIPPED
                    else -> CommandStatus.NOT_EXECUTED
                },
            )
        }

    override fun isDangerous(command: String): Boolean = MqscParser.isDangerous(command)
}
