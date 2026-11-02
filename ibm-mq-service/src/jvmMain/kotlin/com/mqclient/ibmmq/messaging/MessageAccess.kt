package com.mqclient.ibmmq.messaging

import com.ibm.mq.MQException
import com.ibm.mq.MQGetMessageOptions
import com.ibm.mq.MQMessage
import com.ibm.mq.MQQueue
import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import com.mqclient.ibmmq.command.MqrcTranslator
import com.mqclient.ibmmq.ibmMqLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 消息下载 / 删除的结果：成功（下载带 content / 详情带 detail）或失败（MQRC + 可读文案）。 */
data class MessageOperationResult(
    val success: Boolean,
    val content: ByteArray? = null,
    val detail: MessageDetail? = null,
    val reasonCode: Int? = null,
    val errorMessage: String? = null,
    /** i18n 错误键（展示端渲染时点翻译；null = 直接展示 errorMessage）。 */
    val errorKey: String? = null,
    val errorArgs: List<Any> = emptyList(),
) {
    companion object {
        fun success(content: ByteArray? = null): MessageOperationResult =
            MessageOperationResult(success = true, content = content)

        fun successDetail(detail: MessageDetail): MessageOperationResult =
            MessageOperationResult(success = true, content = detail.content, detail = detail)

        fun failure(reasonCode: Int?, message: String): MessageOperationResult =
            MessageOperationResult(success = false, reasonCode = reasonCode, errorMessage = message)

        fun failure(
            reasonCode: Int?,
            message: String,
            errorKey: String?,
            errorArgs: List<Any> = emptyList(),
        ): MessageOperationResult =
            MessageOperationResult(success = false, reasonCode = reasonCode, errorMessage = message,
                errorKey = errorKey, errorArgs = errorArgs)
    }
}

private const val MSG_NOT_FOUND = "Message not found (it may have been consumed or expired)"

/**
 * 单消息操作（detail / download / delete）的扫描时间预算（毫秒）。
 *
 * 这三个操作都是从头 browse 整条队列、逐条比对 messageId 才能定位目标——队列很深时
 * 这会变成一次长达数秒到数分钟的阻塞扫描，而且整个过程都握着连接的 mqiLock，会顺带
 * 冻结同连接的 PCF 扫描与浏览 Tab（一次卡死次次卡死的源头）。给一个预算，超时就主动
 * 收尾返回失败，把锁和句柄让出来；句柄本就是每次 accessQueue 新建的，finally 关闭后
 * 下次请求自然拿到新句柄。
 */
private const val OP_SCAN_BUDGET_MS = 4_000L

/** 扫描预算提示（用户可读）。 */
private const val OP_SCAN_TIMEOUT_MSG = "Queue too deep to locate the message in time (narrow the range or query on the server)"

/**
 * 三个单消息操作（download/detail/delete）共用的失败收尾：日志 + 结果转换一步完成。
 * MQException 带上 reason code 并查 [MqrcTranslator]，其余异常用 message。
 */
private fun operationFailure(op: String, queueName: String, e: Throwable): MessageOperationResult =
    when (e) {
        is MQException -> {
            ibmMqLog.error(
                "msg $op failed: queue=$queueName reason=MQRC ${e.reasonCode}: ${MqrcTranslator.translate(e.reasonCode)}",
            )
            MessageOperationResult.failure(e.reasonCode, "MQRC ${e.reasonCode}: ${MqrcTranslator.translate(e.reasonCode)}",
                errorKey = MqrcTranslator.keyOf(e.reasonCode))
        }
        else -> {
            ibmMqLog.error("msg $op failed: queue=$queueName error=${e.message ?: e::class.simpleName}")
            MessageOperationResult.failure(null, e.message ?: e::class.simpleName ?: "Unknown")
        }
    }

/**
 * 按 MessageId 下载消息体（浏览消息 Tab 的「下载」）：用**独立的 browse 句柄**
 * 从头扫到匹配条目，取出完整字节后关句柄——纯浏览，队列消息一个不动，也不影响
 * UI 列表正持有的 [MessageBrowseSession] 光标。
 */
suspend fun downloadMessageBody(
    manager: MQQueueManager,
    queueName: String,
    targetHex: String,
): MessageOperationResult = withContext(Dispatchers.IO) {
    val start = System.currentTimeMillis()
    ibmMqLog.debug("msg download start: queue=$queueName messageId=$targetHex")
    var queue: MQQueue? = null
    try {
        queue = manager.accessQueue(
            queueName.trim(),
            MQConstants.MQOO_BROWSE or MQConstants.MQOO_FAIL_IF_QUIESCING,
        )
        val gmo = MQGetMessageOptions()
        var first = true
        while (true) {
            // MQI 的 queue.get 是**纯阻塞**调用（不检查取消、也不响应 interrupt），
            // 所以「用户关掉对话框」只能靠循环开头这一处察觉：不检查的话，取消之后
            // 这个 while 会继续把剩余队列一路扫完（深队列上就是几十秒的空转 + 占着连接）。
            currentCoroutineContext().ensureActive()
            // 扫描预算：队列过深时主动收尾，避免一次操作把整条连接（mqiLock）拖死。
            if (System.currentTimeMillis() - start > OP_SCAN_BUDGET_MS) {
                ibmMqLog.warn(
                    "msg download scan aborted (deep queue): queue=$queueName " +
                        "messageId=$targetHex elapsed=${System.currentTimeMillis() - start}ms",
                )
                return@withContext MessageOperationResult.failure(null, OP_SCAN_TIMEOUT_MSG,
                    errorKey = "ibm_mq_err_locate_timeout")
            }
            // MQGMO_NO_WAIT 显式声明：queue.get 绝不阻塞等消息（默认无 MQGMO_WAIT 也是
            // 这个语义，但写清楚更稳）。这样每条 get 立即返回，循环头部的 ensureActive
            // 才能及时察觉取消/超时——否则一次无限等待的 get 会让 withTimeout 也杀不掉它。
            gmo.options =
                (if (first) MQConstants.MQGMO_BROWSE_FIRST else MQConstants.MQGMO_BROWSE_NEXT) or
                    MQConstants.MQGMO_NO_WAIT or
                    MQConstants.MQGMO_FAIL_IF_QUIESCING
            val message = MQMessage()
            try {
                queue.get(message, gmo)
            } catch (e: MQException) {
                if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
                    ibmMqLog.warn("msg download not found: queue=$queueName messageId=$targetHex")
                    return@withContext MessageOperationResult.failure(null, MSG_NOT_FOUND,
                    errorKey = "ibm_mq_err_message_not_found")
                }
                throw e
            }
            first = false
            if (message.messageId.toMqIdHex() == targetHex) {
                val bytes = ByteArray(message.dataLength)
                if (bytes.isNotEmpty()) message.readFully(bytes)
                ibmMqLog.info(
                    "msg download completed: queue=$queueName messageId=$targetHex " +
                        "size=${bytes.size}B duration=${System.currentTimeMillis() - start}ms",
                )
                return@withContext MessageOperationResult.success(bytes)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        MessageOperationResult.failure(null, MSG_NOT_FOUND, errorKey = "ibm_mq_err_message_not_found")
    } catch (cancellation: CancellationException) {
        // 必须排在下面两个宽 catch 之前：取消不是「下载失败」，吞掉会变成一条指向
        // 用户的假错误（且让调用方的取消语义失效）。
        throw cancellation
    } catch (e: MQException) {
        operationFailure("download", queueName, e)
    } catch (e: Exception) {
        operationFailure("download", queueName, e)
    } finally {
        runCatching { queue?.close() }
            .onFailure { ibmMqLog.warn("msg download queue close failed: queue=$queueName error=${it.message}") }
    }
}

/**
 * 按 MessageId 取消息详情（详情对话框）：独立 browse 句柄扫到匹配条目后读完整
 * MQMD + 消息体，组装 [MessageDetail]——纯浏览，队列消息一个不动，也不影响 UI
 * 列表正持有的 [MessageBrowseSession] 光标。
 */
suspend fun getMessageDetail(
    manager: MQQueueManager,
    queueName: String,
    targetHex: String,
): MessageOperationResult = withContext(Dispatchers.IO) {
    val start = System.currentTimeMillis()
    ibmMqLog.debug("msg detail start: queue=$queueName messageId=$targetHex")
    var queue: MQQueue? = null
    try {
        queue = manager.accessQueue(
            queueName.trim(),
            MQConstants.MQOO_BROWSE or MQConstants.MQOO_FAIL_IF_QUIESCING,
        )
        val gmo = MQGetMessageOptions()
        var first = true
        while (true) {
            // 同 download：阻塞的 MQI get 不感知取消，循环开头是唯一的终止点。
            currentCoroutineContext().ensureActive()
            // 扫描预算：队列过深时主动收尾，避免一次操作把整条连接（mqiLock）拖死。
            if (System.currentTimeMillis() - start > OP_SCAN_BUDGET_MS) {
                ibmMqLog.warn(
                    "msg detail scan aborted (deep queue): queue=$queueName " +
                        "messageId=$targetHex elapsed=${System.currentTimeMillis() - start}ms",
                )
                return@withContext MessageOperationResult.failure(null, OP_SCAN_TIMEOUT_MSG,
                    errorKey = "ibm_mq_err_locate_timeout")
            }
            // MQGMO_NO_WAIT 显式声明：queue.get 绝不阻塞等消息，确保循环头部的 ensureActive
            // 能及时察觉取消/超时（否则无限等待的 get 会让 withTimeout 也杀不掉它）。
            gmo.options =
                (if (first) MQConstants.MQGMO_BROWSE_FIRST else MQConstants.MQGMO_BROWSE_NEXT) or
                    MQConstants.MQGMO_NO_WAIT or
                    MQConstants.MQGMO_FAIL_IF_QUIESCING
            val message = MQMessage()
            try {
                queue.get(message, gmo)
            } catch (e: MQException) {
                if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
                    ibmMqLog.warn("msg detail not found: queue=$queueName messageId=$targetHex")
                    return@withContext MessageOperationResult.failure(null, MSG_NOT_FOUND)
                }
                throw e
            }
            first = false
            if (message.messageId.toMqIdHex() == targetHex) {
                val detail = message.toMessageDetail(queueName)
                ibmMqLog.debug(
                    "msg detail completed: queue=$queueName messageId=$targetHex " +
                        "size=${detail.content?.size ?: 0}B duration=${System.currentTimeMillis() - start}ms",
                )
                return@withContext MessageOperationResult.successDetail(detail)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        MessageOperationResult.failure(null, MSG_NOT_FOUND)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: MQException) {
        operationFailure("detail", queueName, e)
    } catch (e: Exception) {
        operationFailure("detail", queueName, e)
    } finally {
        runCatching { queue?.close() }
            .onFailure { ibmMqLog.warn("msg detail queue close failed: queue=$queueName error=${it.message}") }
    }
}

/**
 * 按 MessageId 删除（消费）一条消息（浏览消息 Tab 的「删除」）：以
 * `MQOO_BROWSE | MQOO_INPUT_AS_Q_DEF` 打开队列，browse（非破坏）定位到匹配条目后
 * 用 `MQGMO_MSG_UNDER_CURSOR` 做破坏性 get——**只消费光标下的这一条**，前后消息
 * 都不动。找不到（已被消费 / 过期）返回 failure，由 UI 提示。
 */
suspend fun deleteMessageById(
    manager: MQQueueManager,
    queueName: String,
    targetHex: String,
): MessageOperationResult = withContext(Dispatchers.IO) {
    val start = System.currentTimeMillis()
    ibmMqLog.debug("msg delete start: queue=$queueName messageId=$targetHex")
    var queue: MQQueue? = null
    try {
        queue = manager.accessQueue(
            queueName.trim(),
            MQConstants.MQOO_BROWSE or MQConstants.MQOO_INPUT_AS_Q_DEF or MQConstants.MQOO_FAIL_IF_QUIESCING,
        )
        val gmo = MQGetMessageOptions()
        var first = true
        while (true) {
            // 删除同样是「browse 定位 → 破坏性 get」的阻塞循环，取消终止点同上。
            // 这一处若被取消吞没，最坏的后果不是多跑几圈而是**误删**——取消后继续
            // browse 到匹配条目时，那条破坏性 get 依旧会执行。
            currentCoroutineContext().ensureActive()
            // 扫描预算：定位阶段同样不能无限扫，超时就放弃（不会执行后面的破坏性 get）。
            if (System.currentTimeMillis() - start > OP_SCAN_BUDGET_MS) {
                ibmMqLog.warn(
                    "msg delete scan aborted (deep queue): queue=$queueName " +
                        "messageId=$targetHex elapsed=${System.currentTimeMillis() - start}ms",
                )
                return@withContext MessageOperationResult.failure(null, OP_SCAN_TIMEOUT_MSG)
            }
            // MQGMO_NO_WAIT：定位阶段不阻塞等消息，保证 ensureActive 能及时察觉取消。
            gmo.options =
                (if (first) MQConstants.MQGMO_BROWSE_FIRST else MQConstants.MQGMO_BROWSE_NEXT) or
                    MQConstants.MQGMO_NO_WAIT or
                    MQConstants.MQGMO_FAIL_IF_QUIESCING
            val message = MQMessage()
            try {
                queue.get(message, gmo)
            } catch (e: MQException) {
                if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
                    ibmMqLog.warn("msg delete not found: queue=$queueName messageId=$targetHex")
                    return@withContext MessageOperationResult.failure(null, MSG_NOT_FOUND)
                }
                throw e
            }
            first = false
            if (message.messageId.toMqIdHex() == targetHex) {
                // 光标已在该条上：MQGMO_MSG_UNDER_CURSOR 破坏性 get，仅消费这一条。
                val getOptions = MQGetMessageOptions()
                getOptions.options =
                    MQConstants.MQGMO_MSG_UNDER_CURSOR or MQConstants.MQGMO_FAIL_IF_QUIESCING
                queue.get(MQMessage(), getOptions)
                ibmMqLog.info(
                    "msg delete completed: queue=$queueName messageId=$targetHex " +
                        "duration=${System.currentTimeMillis() - start}ms",
                )
                return@withContext MessageOperationResult.success()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        MessageOperationResult.failure(null, MSG_NOT_FOUND)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: MQException) {
        operationFailure("delete", queueName, e)
    } catch (e: Exception) {
        operationFailure("delete", queueName, e)
    } finally {
        runCatching { queue?.close() }
            .onFailure { ibmMqLog.warn("msg delete queue close failed: queue=$queueName error=${it.message}") }
    }
}
