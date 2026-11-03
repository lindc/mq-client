package com.mqclient.ibmmq.command.model

/**
 * 一条解析后的 MQSC 命令（`MqscParser.parse` 的产物）。
 *
 * 注释（`*` 开头）与空行**不参与执行**，只占行号 —— 编辑器状态列按行号对齐，
 * 行号必须和用户看到的文本行一一对应。
 */
data class MqscCommand(
    /** 1 起始的行号，与编辑器文本行对齐。 */
    val lineNumber: Int,
    /** 原始命令文本（未 trim，保留用户输入）。 */
    val text: String,
    /** 是否为注释（trim 后以 `*` 开头）。 */
    val isComment: Boolean = false,
    /** 是否为空行（trim 后为空）。 */
    val isBlank: Boolean = false,
)
