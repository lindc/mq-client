package com.mqclient.ibmmq.messaging

import com.ibm.mq.MQException
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

/** 单条消息的发送结果：成功带 hex MessageId 与时间戳，失败带 MQRC 原因码与可读文案。 */
data class SendResult(
    val success: Boolean,
    val messageId: String?,
    val sentAt: Long?,
    val reasonCode: Int?,
    val errorMessage: String?,
    /** i18n 错误键（展示端渲染时点翻译；null = 直接展示 errorMessage）。 */
    val errorKey: String? = null,
    val errorArgs: List<Any> = emptyList(),
) {
    companion object {
        fun success(messageId: String, sentAt: Long): SendResult =
            SendResult(success = true, messageId = messageId, sentAt = sentAt, reasonCode = null, errorMessage = null)

        fun failure(reasonCode: Int?, message: String): SendResult =
            SendResult(success = false, messageId = null, sentAt = null, reasonCode = reasonCode, errorMessage = message)

        fun failure(
            reasonCode: Int?,
            message: String,
            errorKey: String?,
            errorArgs: List<Any> = emptyList(),
        ): SendResult =
            SendResult(success = false, messageId = null, sentAt = null, reasonCode = reasonCode,
                errorMessage = message, errorKey = errorKey, errorArgs = errorArgs)
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
 * **唯一例外是协程取消**：对话框关掉 / 任务被取消时 `CancellationException` 原样上抛，
 * 不得被下面的宽 catch 转成一条「发送失败」—— 那既是假错误，也会让调用方的取消语义失效。
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
    correlationIdBytes: ByteArray? = null,
    characterSet: Int? = null,
    format: String? = null,
    replyToQMgrName: String? = null,
): SendResult = withContext(Dispatchers.IO) {
    // 追踪日志只记元数据（队列、大小、属性），绝不记消息体 content。
    ibmMqLog.debug(
        "send start: queue=$queueName size=${content.size}B persistence=$persistence priority=$priority " +
            "expiry=$expiryTenths hasCorrelId=${!correlationId.isNullOrBlank() || correlationIdBytes != null} " +
            "replyToQueue=${replyToQueue?.trim()?.takeIf { it.isNotEmpty() } ?: "-"}",
    )
    val start = System.currentTimeMillis()
    var queue: MQQueue? = null
    try {
        // 用户刚关掉对话框（或点击后被新动作顶掉）时不要再发出这条消息：
        // accessQueue / put 都是阻塞 MQI，一旦进去就只能在应用层等它跑完。
        currentCoroutineContext().ensureActive()
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
            // dmpmqmsg 导入链路的还原参数：24 字节 CorrelId（hex 解码产物）优先于字符串形态
            correlationIdBytes?.let { this.correlationId = it }
            characterSet?.let { this.characterSet = it }
            format?.trim()?.takeIf { it.isNotEmpty() }?.let { this.format = it }
            replyToQMgrName?.trim()?.takeIf { it.isNotEmpty() }?.let { this.replyToQueueManagerName = it }
            write(content)
        }
        queue.put(message)
        // 客户端未显式设置 MessageId 时自行生成唯一 id，put 后回填在 message.messageId。
        val result = SendResult.success(messageId = message.messageId.toMqIdHex(), sentAt = System.currentTimeMillis())
        ibmMqLog.info(
            "send completed: queue=$queueName messageId=${result.messageId} " +
                "size=${content.size}B duration=${System.currentTimeMillis() - start}ms",
        )
        result
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: MQException) {
        ibmMqLog.error(
            "send failed: queue=$queueName reason=MQRC ${e.reasonCode}: ${MqrcTranslator.translate(e.reasonCode)}",
        )
        SendResult.failure(
                e.reasonCode,
                "MQRC ${e.reasonCode}: ${MqrcTranslator.translate(e.reasonCode)}",
                errorKey = MqrcTranslator.keyOf(e.reasonCode),
            )
    } catch (e: Exception) {
        ibmMqLog.error("send failed: queue=$queueName error=${e.message ?: e::class.simpleName}")
        SendResult.failure(null, e.message ?: e::class.simpleName ?: "Unknown")
    } finally {
        runCatching { queue?.close() }
            .onFailure { ibmMqLog.warn("queue close failed after send: queue=$queueName error=${it.message}") }
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
