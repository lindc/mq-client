package com.mqclient.ibmmq.command

import com.mqclient.core.CoreModule
import com.mqclient.core.event.EventNames
import com.mqclient.core.newUuidV7
import com.mqclient.ibmmq.MODULE_ID
import com.mqclient.ibmmq.command.model.MqscResult

/**
 * 写操作命令历史事件的分类与广播（`core.mq.command.executed`）。
 *
 * 调用点在命令执行咽喉点 [MqscExecutor.executeSingle] —— 控制台批量 / 单条适配器 /
 * 通道启停 / 监听器启停 / RESET CHANNEL 全部收敛于此，各调用点无需感知记录行为。
 * payload 自包含（app-core 的 MqCommandHistoryHandler 忠实落库 mq_command 表），
 * 发射侧任何异常都不外泄 —— 历史记录失败不能影响命令执行本身。
 */
internal object MqCommandHistoryEmitter {

    /**
     * 一条命令执行完毕：命中写操作则广播事件。
     *
     * [requestTime] 必须是**命令发起前**的时刻（executeSingle 在调 executeOne 之前取），
     * 其余字段全部来自 [result]（duration 由 executeOne 度量）。
     */
    fun onExecuted(connectionId: String, command: String, result: MqscResult, requestTime: Long) {
        if (!isWrite(command)) return
        val bus = CoreModule.eventBusOrNull ?: return
        runCatching {
            bus.emit(
                name = EventNames.MQ_COMMAND_EXECUTED,
                payload = mapOf(
                    "commandId" to newUuidV7(),
                    "connectionId" to connectionId,
                    // 记展开后的文本 —— 那是真正发给队列管理器的内容（与历史仓库同语义）。
                    "command" to command,
                    // 成功 = PCF 回显全文；失败 = 错误翻译说明；两者都有则拼接。不截断。
                    "response" to listOf(result.output, result.errorMessage)
                        .filter { !it.isNullOrBlank() }
                        .joinToString("\n")
                        .ifBlank { null },
                    "status" to if (result.success) "SUCCESS" else "FAILED",
                    "reasonCode" to result.errorCode?.toString(),
                    "requestTime" to requestTime,
                    "responseTime" to requestTime + result.duration,
                    "durationMs" to result.duration,
                ),
                source = MODULE_ID,
            )
        }
    }

    /**
     * 写操作判定（方案 B：咽喉点集中判定，唯一判定点）。MQSC 里 DISPLAY 是唯一的
     * 只读动词（PING / REFRESH / RESET 等按写操作记录）；命令在到达 executeSingle
     * 前已完成缩写展开（`dis q(*)` → `DISPLAY QLOCAL(*)`），所以按首动词比对是可靠的。
     */
    internal fun isWrite(command: String): Boolean {
        val verb = command.trim().split(WHITESPACE).firstOrNull()?.uppercase().orEmpty()
        return verb.isNotEmpty() && verb != "DISPLAY"
    }

    private val WHITESPACE = Regex("\\s+")
}
