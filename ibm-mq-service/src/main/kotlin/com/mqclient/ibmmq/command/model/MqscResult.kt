package com.mqclient.ibmmq.command.model

/**
 * 一条 MQSC 命令的执行结果 —— 输出区条目与编辑器状态列共用的数据源。
 */
data class MqscResult(
    /** 对应的命令行号（1 起始）。 */
    val lineNumber: Int,
    /** 原始命令文本。 */
    val command: String,
    /** 是否执行成功（PCF 完成码 OK）。 */
    val success: Boolean,
    /** 格式化后的输出文本（成功时为 MQSC 回显行，失败时可能为空）。 */
    val output: String,
    /** 执行耗时（毫秒）。 */
    val duration: Long,
    /** MQRC / PCF 原因码（失败时有值）。 */
    val errorCode: Int? = null,
    /** 原因码的可读说明（[MqrcTranslator.translate] 的产物）。 */
    val errorMessage: String? = null,
)
