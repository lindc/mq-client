package com.mqclient.ibmmq.command

import com.mqclient.core.ServiceException

import com.ibm.mq.constants.MQConstants
import com.ibm.mq.pcf.PCFException
import com.ibm.mq.pcf.PCFMessage
import com.ibm.mq.pcf.PCFMessageAgent
import com.mqclient.core.concurrent.BlockingCallTimeoutException
import com.mqclient.core.concurrent.BlockingCalls
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.ibmMqLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
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
 *
 * 并发：整条命令（含 agent 的创建 / 断开）持该连接的 `mqiLock` —— PCF agent 与扫描
 * 复用同一条 MQ 通道，不串行时 MQSC 请求会与扫描的 INQUIRE 应答在同一条通道上交错
 * （实测表现为随机的 MQRC 2009 / 3010）。代价是慢扫期间 MQSC 命令会排队等锁。
 */
class MqscPcfSender : PcfExecutor {

    private companion object {
        /**
         * MQSC 命令的执行预算。
         *
         * 与扫描同口径（60s）：命令服务器正常情况下一条命令是毫秒级，60s 没能回
         * 就说明连接已经坏了。区别在于扫描到点后拒绝发起下一条 INQUIRE，而这里
         * 只能放弃等待 —— `agent.send` 是纯阻塞调用，没有协作式取消点。
         */
        const val MQSC_BUDGET_MS = 60_000L

        /** 留给「最后一次在飞的 send」自己结束的宽限（agent 默认 waitInterval 30s）。 */
        const val MQSC_GRACE_MS = 40_000L

        /** 日志里截断命令的长度（MQSC 可能很长，且含用户数据）。 */
        const val MAX_LOGGED_COMMAND = 200
    }

    override suspend fun executeMqsc(connectionId: String, command: String): List<String> {
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: throw ServiceException(
                "ibm_mq_err_not_connected",
                args = listOf(connectionId),
                fallbackMessage = "Connection %1\$s is not established",
            )
        return ctx.mqiLock.withLock {
            // 命令服务器卡住时 agent.send 不返回，而它握着该连接的 mqiLock ——
            // 之后的浏览 / 发送 / 深度查询 / 心跳会全部无限期排队。扫描有
            // ScanBudget + BlockingCalls，浏览 / 下载有 OP_SCAN_BUDGET_MS，
            // 唯独 MQSC 曾什么超时都没有。这里用同一套兜底。
            try {
                BlockingCalls.withDeadline(
                    timeoutMs = MQSC_BUDGET_MS + MQSC_GRACE_MS,
                    label = "MQSC command (connection=$connectionId)",
                ) {
                    // 在 worker 线程上把挂起的锁内实现桥接成同步体（同 PcfObjectScanner.withAgent）。
                    runBlocking { executeMqscLocked(connectionId, command) }
                }
            } catch (timeout: BlockingCallTimeoutException) {
                ibmMqLog.error(
                    "mqsc command stuck: connection=$connectionId " +
                        "waited=${(MQSC_BUDGET_MS + MQSC_GRACE_MS) / 1000}s command=${command.take(MAX_LOGGED_COMMAND)}",
                )
                throw ServiceException(
                    "ibm_mq_err_mqsc_timeout",
                    // 命令控制台直接展示 errorMessage（不查 i18n 键），所以这段文案
                    // 要自己说清楚「是连接坏了」而不只是「超时」。
                    fallbackMessage = "MQSC command timed out after " +
                        "${(MQSC_BUDGET_MS + MQSC_GRACE_MS) / 1000}s — the connection may be broken; " +
                        "reconnect and retry",
                    cause = timeout,
                )
            }
        }
    }

    /** [executeMqsc] 的锁内实现：调用方必须已持有该连接的 `mqiLock`。 */
    private suspend fun executeMqscLocked(connectionId: String, command: String): List<String> =
        withContext(Dispatchers.IO) {
            val manager = IbmMQModule.connectionOrNull(connectionId)?.mqConnection
                ?: throw ServiceException(
                "ibm_mq_err_not_connected",
                args = listOf(connectionId),
                fallbackMessage = "Connection %1\$s is not established",
            )
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
    suspend fun diagnose(connectionId: String, queueName: String = "DEV.QUEUE.1"): String {
        val ctx = IbmMQModule.connectionOrNull(connectionId)
            ?: return "Diagnosis failed: connection $connectionId is not established"
        return ctx.mqiLock.withLock {
            // 诊断路径同样握 mqiLock，同样要给上限（它是排障工具，不能反过来把连接拖死）。
            try {
                BlockingCalls.withDeadline(
                    timeoutMs = MQSC_BUDGET_MS + MQSC_GRACE_MS,
                    label = "MQSC diagnosis (connection=$connectionId)",
                ) {
                    runBlocking { diagnoseLocked(connectionId, queueName) }
                }
            } catch (timeout: BlockingCallTimeoutException) {
                "Diagnosis timed out after ${(MQSC_BUDGET_MS + MQSC_GRACE_MS) / 1000}s — " +
                    "the connection looks broken (no MQI reply at all)."
            }
        }
    }

    /** [diagnose] 的锁内实现：调用方必须已持有该连接的 `mqiLock`。 */
    private suspend fun diagnoseLocked(connectionId: String, queueName: String): String =
        withContext(Dispatchers.IO) {
            val manager = IbmMQModule.connectionOrNull(connectionId)?.mqConnection
                ?: return@withContext "Diagnosis failed: connection $connectionId is not established"
            val agent = PCFMessageAgent(manager)
            try {
                val request = PCFMessage(MQConstants.MQCMD_INQUIRE_Q).apply {
                    addParameter(MQConstants.MQCA_Q_NAME, queueName)
                }
                val responses = agent.send(request)
                buildString {
                    appendLine("Native PCF MQCMD_INQUIRE_Q('$queueName'): ${responses.size} response(s)")
                    responses.forEach { response ->
                        appendLine("  reason=${response.reason} params=${response.parameterCount}")
                    }
                    appendLine("Native PCF works — the problem was in escape request construction (should be fixed). If it fails again, check the command server and permissions.")
                }
            } catch (e: PCFException) {
                buildString {
                    appendLine("Native PCF MQCMD_INQUIRE_Q failed as well: reasonCode=${e.reasonCode} " +
                        "completionCode=${e.completionCode}")
                    appendLine("Environment-level problem: command server not running, or no access to SYSTEM.ADMIN.COMMAND.QUEUE.")
                    appendLine("   Check on the QMGR: `DISPLAY QMGR CMDSERV`, queue depth, AMQERR01.LOG.")
                }
            } finally {
                runCatching { agent.disconnect() }
            }
        }
}
