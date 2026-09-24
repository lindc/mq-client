package com.mqclient.mqui.command.model

/**
 * 一条命令的执行结果 —— 输出区条目与编辑器状态列共用的数据源。
 *
 * `output` 是**已格式化**的文本：格式化（如 PCF 响应 → 可读文本）由 service 层
 * 的 [com.mqclient.mqui.command.api.CommandExecutor] 实现方完成，UI 层直接显示。
 */
data class CommandResult(
    /** 对应的命令行号（1 起始，与编辑器文本行对齐）。 */
    val lineNumber: Int,
    /** 原始命令文本（用户输入的原样）。 */
    val command: String,
    /** 展开后的命令（缩写被展开时有值，如 `dis q(*)` → `DISPLAY QLOCAL(*)`）。 */
    val expandedCommand: String? = null,
    /** 是否执行成功。 */
    val success: Boolean,
    /** 已格式化的输出文本（失败时可能为空）。 */
    val output: String,
    /** 执行耗时（毫秒）。 */
    val duration: Long,
    /** 错误码（失败时有值；含义由各模块自定义，如 IBM MQ 的 MQRC / PCF 原因码）。 */
    val errorCode: Int? = null,
    /** 错误码的可读说明（失败时有值）。 */
    val errorMessage: String? = null,
)
