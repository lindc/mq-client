package com.mqclient.core.db

import com.mqclient.core.CoreModule
import com.mqclient.core.event.EventNames
import com.mqclient.core.newUuidV7
import java.util.concurrent.ExecutionException

/**
 * 写操作审计的公共发射器（Kafka / RabbitMQ 用；IBM MQ 走自己的
 * MqCommandHistoryEmitter，payload 字段与本类完全兼容同一落库 handler）。
 *
 * 伪命令规范：全大写操作词打头（`CREATE QUEUE worker.tasks [durable]`、
 * `RESET OFFSETS GROUP workers (strategy=EARLIEST)` 等），由各模块 service 层在
 * 调用写操作前拼装；发送消息（消息生产）不是管理操作，不记录（与 IBM MQ 的
 * MqscExecutor 咽喉点口径一致）。
 *
 * 与 IBM 的 emitter 同一约定：发射侧任何异常都不外泄（runCatching）—— 历史记录
 * 失败不能影响业务操作本身；事件由 app-core 的 MqCommandHistoryHandler 忠实落库
 * mq_command 表。mqType 不进事件 / 表：查询 UI 按连接 JOIN mq_connection.type 过滤。
 */
object CommandAudit {

    /**
     * 一条写操作执行完毕（成功或失败）后广播审计事件。
     *
     * [command] 是拼装好的伪命令；[response] 成功时为简要结果注记或 null，失败时
     * 为 [describeError] 的可读错误；[requestTime] 必须是操作发起前的时刻。
     */
    fun emit(
        connectionId: String,
        command: String,
        status: String,
        response: String?,
        requestTime: Long,
        responseTime: Long,
        /** 底层协议的数字原因码（如 RocketMQ 17/29）；无数字码的模块不传。 */
        reasonCode: String? = null,
    ) {
        val bus = CoreModule.eventBusOrNull ?: return
        runCatching {
            bus.emit(
                name = EventNames.MQ_COMMAND_EXECUTED,
                payload = mapOf(
                    "commandId" to newUuidV7(),
                    "connectionId" to connectionId,
                    "command" to command,
                    "response" to response,
                    "status" to status,
                    reasonCode?.let { "reasonCode" to it } ?: ("reasonCode" to null),
                    "requestTime" to requestTime,
                    "responseTime" to responseTime,
                    "durationMs" to responseTime - requestTime,
                ),
            )
        }
    }

    /** 底层异常 → 可读 response 文本：解 ExecutionException 包装（与 UI failureText 同逻辑）。 */
    fun describeError(error: Throwable): String =
        (error as? ExecutionException)?.cause?.message
            ?: error.message
            ?: error::class.simpleName
            ?: "unknown"
}
