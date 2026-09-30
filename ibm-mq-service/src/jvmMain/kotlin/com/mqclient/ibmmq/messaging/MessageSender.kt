package com.mqclient.ibmmq.messaging

import com.ibm.mq.MQException
import com.ibm.mq.MQMessage
import com.ibm.mq.MQQueue
import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import com.mqclient.ibmmq.command.MqrcTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单条消息的发送结果：成功带 hex MessageId 与时间戳，失败带 MQRC 原因码与可读文案。 */
data class SendResult(
    val success: Boolean,
    val messageId: String?,
    val sentAt: Long?,
    val reasonCode: Int?,
    val errorMessage: String?,
) {
    companion object {
        fun success(messageId: String, sentAt: Long): SendResult =
            SendResult(success = true, messageId = messageId, sentAt = sentAt, reasonCode = null, errorMessage = null)

        fun failure(reasonCode: Int?, message: String): SendResult =
            SendResult(success = false, messageId = null, sentAt = null, reasonCode = reasonCode, errorMessage = message)
    }
}

/**
 * 往队列发送一条消息（消息处理对话框「发送消息」Tab）：以 `MQOO_OUTPUT` 打开队列，
 * put 后立即关句柄（每次发送独立开关，简单可靠；量大的连续发送可复用会话，后续优化）。
 *
 * MQI 是阻塞 IO，整体切到 [Dispatchers.IO]；[MQException] 转成 [SendResult.failure]
 * （reason code + [MqrcTranslator] 可读文案），其余异常转普通 failure，不向上抛 ——
 * 调用方（UI）按结果更新任务状态，不需要再包一层 try。
 *
 * @param expiryTenths 过期时间（MQMD 单位：十分之一秒）；[MQConstants.MQEI_UNLIMITED]
 *   表示不过期。秒 → 十分之一秒的换算在 UI 层做。
 */
suspend fun sendMessageToQueue(
    manager: MQQueueManager,
    queueName: String,
    content: ByteArray,
    correlationId: String? = null,
    replyToQueue: String? = null,
    persistence: Int = MQConstants.MQPER_PERSISTENT,
    priority: Int = 4,
    expiryTenths: Int = MQConstants.MQEI_UNLIMITED,
): SendResult = withContext(Dispatchers.IO) {
    var queue: MQQueue? = null
    try {
        queue = manager.accessQueue(
            queueName.trim(),
            MQConstants.MQOO_OUTPUT or MQConstants.MQOO_FAIL_IF_QUIESCING,
        )
        val message = MQMessage().apply {
            // CorrelationId / ReplyTo 由调用方给字符串：UTF-8 字节（不足 24 字节 QM 自动补齐）。
            correlationId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                this.correlationId = it.toByteArray(Charsets.UTF_8)
            }
            replyToQueue?.trim()?.takeIf { it.isNotEmpty() }?.let { replyToQueueName = it }
            this.persistence = persistence
            this.priority = priority
            expiry = expiryTenths
            write(content)
        }
        queue.put(message)
        // 客户端未显式设置 MessageId 时自行生成唯一 id，put 后回填在 message.messageId。
        SendResult.success(messageId = message.messageId.toMqIdHex(), sentAt = System.currentTimeMillis())
    } catch (e: MQException) {
        SendResult.failure(e.reasonCode, "MQRC ${e.reasonCode}: ${MqrcTranslator.translate(e.reasonCode)}")
    } catch (e: Exception) {
        SendResult.failure(null, e.message ?: e::class.simpleName ?: "Unknown")
    } finally {
        runCatching { queue?.close() }
    }
}

/** 24 字节 MQ 二进制 ID（MessageId / CorrelationId）→ 48 字符大写十六进制（MQ Explorer 同款）。 */
internal fun ByteArray.toMqIdHex(): String = joinToString("") { "%02X".format(it) }

/**
 * 发送相关的默认值与常量（UI 层对 ibm-mq-service 是 implementation 依赖，看不见
 * com.ibm.mq 的类，持久化 / 过期常量从这里取）。
 */
object SendDefaults {
    const val PERSISTENCE_PERSISTENT: Int = MQConstants.MQPER_PERSISTENT
    const val PERSISTENCE_NOT_PERSISTENT: Int = MQConstants.MQPER_NOT_PERSISTENT
    const val PRIORITY_DEFAULT: Int = 4

    /** 过期时间「不限」（MQMD 的十分之一秒单位）。 */
    const val EXPIRY_UNLIMITED_TENTHS: Int = MQConstants.MQEI_UNLIMITED
}
