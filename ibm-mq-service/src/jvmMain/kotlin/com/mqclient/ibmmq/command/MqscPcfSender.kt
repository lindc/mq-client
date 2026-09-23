package com.mqclient.ibmmq.command

import com.ibm.mq.constants.MQConstants
import com.ibm.mq.pcf.PCFException
import com.ibm.mq.pcf.PCFMessage
import com.ibm.mq.pcf.PCFMessageAgent
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.ibmMqLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [PcfExecutor] 的生产实现：经 PCF `MQCMD_ESCAPE` 把 MQSC 命令送到队列管理器。
 *
 * 请求格式（IBM 文档 9.2 / 10.0 一致，**两个必需参数**）：
 *  - `EscapeType`（`MQIACF_ESCAPE_TYPE`，MQCFIN，唯一合法值 `MQET_MQSC`）；
 *  - `EscapeText`（`MQCACF_ESCAPE_TEXT`，**单个字符串** MQCFST）。
 * 早期流传的「字符串列表、首元素 MQSC」写法是老格式 —— 少一个参数，命令服务器
 * 会回 `MQRCCF_PARM_COUNT_TOO_SMALL (3019)`（曾因此全军覆没，见 diagnose）。
 *
 * 与 `IbmMQService.queryQmgrProperties` 同一模式 —— [PCFMessageAgent] 包在既有
 * [com.ibm.mq.MQQueueManager] 上，复用连接的 TCP 通道，不另建连接；用完即断
 * agent（manager 归 IbmMQConnectionManager 管，这里绝不碰）。
 *
 * 失败语义：`PCFMessageAgent.send` 在响应完成码非 OK 时抛 [PCFException]
 * （`reasonCode` 即命令服务器返回的原因码），由调用方翻译并展示。
 */
class MqscPcfSender : PcfExecutor {

    override suspend fun executeMqsc(connectionId: String, command: String): List<String> =
        withContext(Dispatchers.IO) {
            val manager = IbmMQModule.connectionOrNull(connectionId)?.mqConnection
                ?: throw IllegalStateException("连接 $connectionId 未建立，无法执行 MQSC 命令")
            val agent = PCFMessageAgent(manager)
            try {
                val request = escapeRequest(command)
                ibmMqLog.debug("mqsc request: $command")
                val responses = agent.send(request)
                responses.forEach { response ->
                    ibmMqLog.debug("mqsc response: reason=${response.reason} params=${response.parameterCount}")
                }
                responses.flatMap { response -> escapeText(response) }
            } catch (e: PCFException) {
                ibmMqLog.error(
                    "mqsc failed: command=$command reasonCode=${e.reasonCode} " +
                        "completionCode=${e.completionCode} message=${e.message}",
                )
                throw e
            } finally {
                runCatching { agent.disconnect() }
            }
        }

    /** 构造 escape 请求：EscapeType(MQET_MQSC) + EscapeText(单字符串)。 */
    private fun escapeRequest(command: String): PCFMessage = PCFMessage(MQConstants.MQCMD_ESCAPE).apply {
        addParameter(MQConstants.MQIACF_ESCAPE_TYPE, MQConstants.MQET_MQSC)
        addParameter(MQConstants.MQCACF_ESCAPE_TEXT, command)
    }

    /**
     * 取出响应里的 MQSC 回显行（`AMQ8409: ...` 等）。旧版客户端实现里该参数
     * 可能以列表或单串到达，两种形状都兜住。
     */
    private fun escapeText(response: PCFMessage): List<String> =
        when (val value = response.getParameterValue(MQConstants.MQCACF_ESCAPE_TEXT)) {
            is Array<*> -> value.map { it?.toString().orEmpty() }
            is String -> listOf(value)
            is ByteArray -> listOf(value.decodeToString().trimEnd('\u0000'))
            else -> emptyList()
        }

    /**
     * 诊断：**绕过 escape**，直接发原生 PCF `MQCMD_INQUIRE_Q`。
     * 它成功 → 环境与 agent 通路正常，问题只在 escape 请求构造；
     * 它也失败 → 命令服务器 / 权限 / 通道层面的问题（看返回的 reason 与
     * QMGR 的 AMQERR01.LOG，常见 AMQ8079 命令服务器未运行）。
     */
    suspend fun diagnose(connectionId: String, queueName: String = "DEV.QUEUE.1"): String =
        withContext(Dispatchers.IO) {
            val manager = IbmMQModule.connectionOrNull(connectionId)?.mqConnection
                ?: return@withContext "诊断失败：连接 $connectionId 未建立"
            val agent = PCFMessageAgent(manager)
            try {
                val request = PCFMessage(MQConstants.MQCMD_INQUIRE_Q).apply {
                    addParameter(MQConstants.MQCA_Q_NAME, queueName)
                }
                val responses = agent.send(request)
                buildString {
                    appendLine("原生 PCF MQCMD_INQUIRE_Q('$queueName')：${responses.size} 条响应")
                    responses.forEach { response ->
                        appendLine("  reason=${response.reason} params=${response.parameterCount}")
                    }
                    appendLine("→ 原生 PCF 正常，escape 请求构造问题（应已修复）；若失败请查命令服务器与权限。")
                }
            } catch (e: PCFException) {
                buildString {
                    appendLine("原生 PCF MQCMD_INQUIRE_Q 也失败：reasonCode=${e.reasonCode} " +
                        "completionCode=${e.completionCode}")
                    appendLine("→ 问题在环境层面：命令服务器未运行 / 无 SYSTEM.ADMIN.COMMAND.QUEUE 权限。")
                    appendLine("   排查：QMGR 上 `DISPLAY QMGR CMDSERV`、队列深度、AMQERR01.LOG。")
                }
            } finally {
                runCatching { agent.disconnect() }
            }
        }
}
