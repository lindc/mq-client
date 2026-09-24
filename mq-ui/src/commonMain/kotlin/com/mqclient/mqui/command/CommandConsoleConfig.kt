package com.mqclient.mqui.command

import com.mqclient.mqui.command.api.CommandExecutor
import com.mqclient.mqui.command.api.CommandParser
import com.mqclient.mqui.command.api.SyntaxHighlighter

/**
 * 命令控制台的注入配置：各 MQ 模块组装自己的解析 / 高亮 / 执行实现，
 * [CommandConsolePanel] 只依赖本配置，不感知任何具体 MQ 客户端。
 */
data class CommandConsoleConfig(
    /** Panel 标题（宿主侧展示用；面板本身不渲染）。 */
    val title: String,
    /** 命令文件的扩展名（不含点），如 `mqsc`，用于「打开文件」对话框过滤。 */
    val fileExtension: String,
    val syntaxHighlighter: SyntaxHighlighter,
    val commandParser: CommandParser,
    val commandExecutor: CommandExecutor,
)
