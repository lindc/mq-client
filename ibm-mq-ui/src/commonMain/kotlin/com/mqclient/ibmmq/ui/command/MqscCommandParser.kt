package com.mqclient.ibmmq.ui.command

import com.mqclient.ibmmq.command.MqscParser
import com.mqclient.mqui.command.api.CommandParser
import com.mqclient.mqui.command.model.CommandLine
import com.mqclient.mqui.command.model.CommandStatus

/**
 * mq-ui 通用命令控制台的 IBM MQ 适配：解析逻辑全部委托给 **service 层的
 * [MqscParser]**（注释 `*` / 空行标 SKIPPED 但不剔除，危险动词前缀判定不变）。
 *
 * 为什么这个适配器在 `ibm-mq-ui` 而不是 `ibm-mq-service`：它实现的是 **UI 契约**
 * （`CommandParser` 来自 `mq-ui`，而 `mq-ui` 以 `api(compose.ui)` 暴露 AnnotatedString），
 * 放在 service 模块等于让一个「不得依赖 Compose」的层编译期依赖 Compose。
 * 协议知识（MQSC 词法 / 危险命令规则）仍然在 service 的 [MqscParser] 里，
 * 这里只做接口形状的转换 —— 与 `MqscCommandExecutor` 同一处理。
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
