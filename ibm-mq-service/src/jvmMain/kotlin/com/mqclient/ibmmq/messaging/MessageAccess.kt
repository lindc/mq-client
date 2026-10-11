package com.mqclient.ibmmq.messaging

import com.ibm.mq.MQException
import com.ibm.mq.MQGetMessageOptions
import com.ibm.mq.MQMessage
import com.ibm.mq.MQQueue
import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import com.mqclient.ibmmq.command.MqrcTranslator
import com.mqclient.ibmmq.ibmMqLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 消息下载 / 删除的结果：成功（下载带 content / 详情带 detail）或失败（MQRC + 可读文案）。 */
data class MessageOperationResult(
    val success: Boolean,
    val content: ByteArray? = null,
    val detail: MessageDetail? = null,
    val reasonCode: Int? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun success(content: ByteArray? = null): MessageOperationResult =
            MessageOperationResult(success = true, content = content)

        fun successDetail(detail: MessageDetail): MessageOperationResult =
            MessageOperationResult(success = true, content = detail.content, detail = detail)

        fun failure(reasonCode: Int?, message: String): MessageOperationResult =
            MessageOperationResult(success = false, reasonCode = reasonCode, errorMessage = message)
    }
}

private const val MSG_NOT_FOUND = "未找到该消息（可能已被消费或过期）"

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
            MessageOperationResult.failure(e.reasonCode, "MQRC ${e.reasonCode}: ${MqrcTranslator.translate(e.reasonCode)}")
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
            gmo.options =
                (if (first) MQConstants.MQGMO_BROWSE_FIRST else MQConstants.MQGMO_BROWSE_NEXT) or
                    MQConstants.MQGMO_FAIL_IF_QUIESCING
            val message = MQMessage()
            try {
                queue.get(message, gmo)
            } catch (e: MQException) {
                if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
                    ibmMqLog.warn("msg download not found: queue=$queueName messageId=$targetHex")
                    return@withContext MessageOperationResult.failure(null, MSG_NOT_FOUND)
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
        MessageOperationResult.failure(null, MSG_NOT_FOUND)
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
            gmo.options =
                (if (first) MQConstants.MQGMO_BROWSE_FIRST else MQConstants.MQGMO_BROWSE_NEXT) or
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
            gmo.options =
                (if (first) MQConstants.MQGMO_BROWSE_FIRST else MQConstants.MQGMO_BROWSE_NEXT) or
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
    } catch (e: MQException) {
        operationFailure("delete", queueName, e)
    } catch (e: Exception) {
        operationFailure("delete", queueName, e)
    } finally {
        runCatching { queue?.close() }
            .onFailure { ibmMqLog.warn("msg delete queue close failed: queue=$queueName error=${it.message}") }
    }
}
