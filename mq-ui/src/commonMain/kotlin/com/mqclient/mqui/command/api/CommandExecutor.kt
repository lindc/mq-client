package com.mqclient.mqui.command.api

import com.mqclient.mqui.command.model.CommandResult

/**
 * 命令执行接口（各 MQ 模块实现）。
 *
 * 实现方负责：命令展开（如缩写）、发送请求、把响应格式化为可读文本（放进
 * [CommandResult.output]）、错误码翻译（放进 errorCode / errorMessage）。
 *
 * 线程契约：调用方（[com.mqclient.mqui.command.CommandConsoleViewModel]）**已经把
 * 本方法切到后台 IO 调度器**执行，所以实现方无需再自行 `withContext`；但也**不得
 * 假设自己跑在主线程** —— 它不在。阻塞 IO 就写在本方法里，直接写。
 */
interface CommandExecutor {

    /**
     * 执行单条命令。
     *
     * @param command 命令文本（单行）。
     * @param lineNumber 该命令在编辑器里的行号（1 起始），实现方原样回填到
     *   [CommandResult.lineNumber] —— 调用方按它对齐状态列。
     */
    suspend fun execute(
        command: String,
        lineNumber: Int,
    ): CommandResult
}
