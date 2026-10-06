package com.mqclient.core.db

import com.mqclient.core.CoreModule
import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getLong
import com.mqclient.core.event.getString

/**
 * 处理 [EventNames.MQ_COMMAND_EXECUTED]：把写操作命令记录写进 mq_command 表。
 *
 * 分工：事件的发出方是各 MQ 模块的命令执行咽喉点（IBM MQ = MqscExecutor.executeSingle
 * 经 MqCommandHistoryEmitter，DISPLAY 等只读命令不会发出本事件），payload 自包含，
 * 本类只做一件事 —— 忠实落库。任何失败都走错误通道上报，绝不上抛（EventBus 会就地
 * 吃掉 handler 异常，但显式 report 能让用户看见）；写入失败不影响命令本身的执行结果。
 */
class MqCommandHistoryHandler(private val repository: MqCommandHistoryRepository) {

    suspend fun handle(event: AppEvent) {
        if (event.name != EventNames.MQ_COMMAND_EXECUTED) return

        // 必填字段缺一即放弃（事件由代码组装，缺字段属于编程错误，不值得落一条残缺记录）。
        val id = event.getString("commandId") ?: return
        val connectionId = event.getString("connectionId") ?: return
        val command = event.getString("command") ?: return
        val status = event.getString("status") ?: return
        val requestTime = event.getLong("requestTime") ?: return

        val record = MqCommandRecord(
            id = id,
            connectionId = connectionId,
            command = command,
            response = event.getString("response"),
            status = status,
            reasonCode = event.getString("reasonCode"),
            requestTime = requestTime,
            responseTime = event.getLong("responseTime"),
            durationMs = event.getLong("durationMs"),
        )

        runCatching { repository.save(record) }
            .onFailure { error ->
                CoreModule.reportError(
                    message = "命令历史写入失败（connectionId=$connectionId，command=${command.take(40)}）",
                    source = "core.db",
                    error = error,
                )
            }
    }
}
