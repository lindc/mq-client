package com.mqclient.mqui.command.api

import com.mqclient.mqui.command.model.CommandLine

/**
 * 命令解析接口（各 MQ 模块实现）：多行文本 → 命令行列表 + 危险命令判定。
 */
interface CommandParser {

    /**
     * 解析多行文本为命令行列表。返回列表与输入文本行**按序一一对应**
     * （注释 / 空行标为 [com.mqclient.mqui.command.model.CommandStatus.SKIPPED]
     * 但不剔除），调用方按下标对齐编辑器状态列。
     */
    fun parse(text: String): List<CommandLine>

    /**
     * 是否为危险命令（可能修改 / 删除对象）。注释行由调用方先跳过。
     */
    fun isDangerous(command: String): Boolean
}
