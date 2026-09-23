package com.mqclient.ibmmq.command

import com.mqclient.ibmmq.command.model.MqscCommand

/** MQSC 命令解析：多行文本 → 命令列表 + 危险命令判定。 */
object MqscParser {

    /**
     * 解析多行文本为命令列表。注释（`*` 开头）与空行标记出来但不剔除 ——
     * 调用方（编辑器状态列）需要完整行号对齐；执行侧自行跳过。
     */
    fun parse(text: String): List<MqscCommand> =
        text.lines().mapIndexed { index, line ->
            val trimmed = line.trim()
            MqscCommand(
                lineNumber = index + 1,
                text = line,
                isComment = trimmed.startsWith("*"),
                isBlank = trimmed.isEmpty(),
            )
        }

    private val DANGEROUS_VERBS = listOf("DELETE", "CLEAR", "ALTER", "STOP", "RESET")

    /**
     * 是否为危险命令（可能修改 / 删除对象）：按动词前缀判定。
     * 注释行不算（调用方先跳过 `isComment`）。
     */
    fun isDangerous(command: String): Boolean =
        DANGEROUS_VERBS.any { command.trim().uppercase().startsWith(it) }
}
