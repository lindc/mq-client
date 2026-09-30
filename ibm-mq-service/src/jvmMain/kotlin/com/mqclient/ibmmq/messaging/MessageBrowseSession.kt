package com.mqclient.ibmmq.messaging

import com.ibm.mq.MQException
import com.ibm.mq.MQGetMessageOptions
import com.ibm.mq.MQMessage
import com.ibm.mq.MQQueue
import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import com.mqclient.ibmmq.command.MqrcTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 浏览链路异常 → 用户可读文案：[MQException] 带上 reason code 并查
 * [MqrcTranslator] 翻译表，其余异常用 message。放在 service 侧是因为 UI 模块
 * 对 ibm-mq-service 是 implementation 依赖，看不见 com.ibm.mq 的类。
 */
fun browseErrorText(e: Throwable): String = when (e) {
    is MQException -> "MQRC ${e.reasonCode}: ${MqrcTranslator.translate(e.reasonCode)}"
    else -> e.message ?: e::class.simpleName ?: "Unknown"
}

/**
 * 队列消息浏览会话：以 `MQOO_BROWSE` 打开一次队列，**browse 光标跨调用保持**，
 * 每次 [browseNext] 只走新消息（对比无状态方案的「跳过 startIndex 条」，深队列
 * 翻页不会越来越慢）。纯浏览不消费消息——CURDEPTH 不受影响，队列数据不动。
 *
 * 生命周期跟 UI 对话框走：打开对话框建会话，关闭时 [close]（释放队列句柄）。
 * MQI 调用是阻塞 IO，所有公开方法都挂起并切到 [Dispatchers.IO]。
 */
class MessageBrowseSession private constructor(
    private val queue: MQQueue,
) {

    /** 下一次 get 是否用 BROWSE_FIRST（首调或 [reset] 后为 true，其余 BROWSE_NEXT）。 */
    private var firstBrowse = true

    /**
     * 浏览下一条（内部按 [count] 循环逐条 get，串行）。队列读空（MQRC 2033）时
     * 正常返回已拿到的部分——调用方以「返回条数 < [count]」判定浏览耗尽。
     */
    suspend fun browseNext(count: Int): List<MessageSummary> = withContext(Dispatchers.IO) {
        val result = ArrayList<MessageSummary>(count)
        val options = MQGetMessageOptions()
        repeat(count) {
            val useFirst = firstBrowse
            options.options =
                (if (useFirst) MQConstants.MQGMO_BROWSE_FIRST else MQConstants.MQGMO_BROWSE_NEXT) or
                    MQConstants.MQGMO_FAIL_IF_QUIESCING
            val message = MQMessage()
            try {
                queue.get(message, options)
            } catch (e: MQException) {
                if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) return@withContext result
                throw e
            }
            firstBrowse = false
            result += message.toSummary()
        }
        result
    }

    /** 光标重置：下一次 [browseNext] 从队首重新开始（刷新用）。 */
    fun reset() {
        firstBrowse = true
    }

    /** 关闭队列句柄；重复调用安全。 */
    fun close() {
        if (closed) return
        closed = true
        runCatching { queue.close() }
    }

    private var closed = false

    companion object {

        /**
         * 在既有的 [MQQueueManager] 上打开浏览会话（复用连接的 TCP 通道，不另建连接）。
         * 连接已断 / QMGR 不可用时报 MQException 原样上抛，由 UI 转「加载失败」。
         */
        suspend fun open(manager: MQQueueManager, queueName: String): MessageBrowseSession =
            withContext(Dispatchers.IO) {
                val openOptions =
                    MQConstants.MQOO_BROWSE or MQConstants.MQOO_FAIL_IF_QUIESCING
                MessageBrowseSession(manager.accessQueue(queueName.trim(), openOptions))
            }
    }
}

/** MQMessage → 列表摘要：字节 ID 转大写 hex，其余取 MQMD 对应字段。 */
private fun MQMessage.toSummary(): MessageSummary {
    // MQ 客户端不暴露 PutDate/PutTime 原始串，只给 putDateTime（GMT Calendar）——
    // 这里反推回模型的原始格式（HHMMSSss 的 ss 是百分秒）。
    val (rawDate, rawTime) = putDateTime.toRawPutDateParts()
    return MessageSummary(
        messageId = messageId.toMqIdHex(),
        correlationId = correlationId.toMqIdHex(),
        putDate = rawDate,
        putTime = rawTime,
        dataLength = dataLength,
        persistence = persistence,
        priority = priority,
        msgType = messageType,
    )
}

/** GMT Calendar → (YYYYMMDD, HHMMSSss) 原始串；putDateTime 缺失（罕见）返回空串。 */
private fun java.util.Calendar?.toRawPutDateParts(): Pair<String, String> {
    if (this == null) return "" to ""
    val date = "%04d%02d%02d".format(
        get(java.util.Calendar.YEAR),
        get(java.util.Calendar.MONTH) + 1,
        get(java.util.Calendar.DAY_OF_MONTH),
    )
    val time = "%02d%02d%02d%02d".format(
        get(java.util.Calendar.HOUR_OF_DAY),
        get(java.util.Calendar.MINUTE),
        get(java.util.Calendar.SECOND),
        get(java.util.Calendar.MILLISECOND) / 10,
    )
    return date to time
}
