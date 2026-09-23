package com.mqclient.ibmmq.ui.command.model

/**
 * 编辑器一行（文本 + 执行状态）。状态只对「最近一次执行」有意义，编辑文本即重置。
 */
data class CommandLine(
    val text: String,
    val status: CommandStatus = CommandStatus.NOT_EXECUTED,
)

enum class CommandStatus {
    /** 未执行。 */
    NOT_EXECUTED,

    /** 执行中。 */
    EXECUTING,

    /** 执行成功。 */
    SUCCESS,

    /** 执行失败。 */
    FAILED,

    /** 跳过（注释 / 空行；当前版本不标，占位语义完整）。 */
    SKIPPED,
}
