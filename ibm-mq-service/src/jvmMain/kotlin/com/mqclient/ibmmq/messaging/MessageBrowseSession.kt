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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * 并发：会话的每次 `get` 都要持所属连接的 [mqiLock]（`IbmMQConnectionContext.mqiLock`）
 * —— 它与 PCF 扫描 / 消息发送共用同一把锁。浏览句柄本身是独立队列句柄，不会踩
 * 会话光标；但共用的 `MQQueueManager` 上的 MQI 请求/应答是串行的，与扫描并发会在
 * 同一条通道上交错（实测表现为随机 MQRC / 扫到的对象数抖动）。
 */
class MessageBrowseSession private constructor(
    private val queue: MQQueue,
    /** 仅用于日志追踪的队列名（open 时从调用方取，MQQueue.name 需要额外属性访问）。 */
    private val queueName: String,
    /** 所属连接的 MQI 互斥锁（与 PCF 扫描 / 发送共用，见类 KDoc）。 */
    private val mqiLock: Mutex,
) {

    /** 下一次 get 是否用 BROWSE_FIRST（首调或 [reset] 后为 true，其余 BROWSE_NEXT）。 */
    private var firstBrowse = true

    /**
     * 浏览下一条（内部按 [count] 循环逐条 get，串行）。队列读空（MQRC 2033）时
     * 正常返回已拿到的部分——调用方以「返回条数 < [count]」判定浏览耗尽。
     *
     * 锁在**最外层**：整页浏览对扫描而言是一个原子操作，否则半页浏览与一轮 PCF
     * 查询会交替压在同一条 MQ 通道上。
     */
    suspend fun browseNext(count: Int): List<MessageSummary> = mqiLock.withLock { browsePage(count) }

    private suspend fun browsePage(count: Int): List<MessageSummary> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        ibmMqLog.debug("browse next: queue=$queueName count=$count")
        try {
            val result = ArrayList<MessageSummary>(count)
            val options = MQGetMessageOptions()
            repeat(count) {
                // 对话框关掉 / 切队列时，重复的阻塞 get 必须停在这一圈：
                // queue.get 不看协程状态，循环开头是唯一能察觉取消的位置。
                currentCoroutineContext().ensureActive()
                val useFirst = firstBrowse
                // MQGMO_NO_WAIT：每条 get 立即返回（绝不阻塞等消息），保证循环头部的
                // ensureActive 能及时察觉取消/超时，不会被一次无限等待的 get 卡死。
                options.options =
                    (if (useFirst) MQConstants.MQGMO_BROWSE_FIRST else MQConstants.MQGMO_BROWSE_NEXT) or
                        MQConstants.MQGMO_NO_WAIT or
                        MQConstants.MQGMO_FAIL_IF_QUIESCING
                val message = MQMessage()
                try {
                    queue.get(message, options)
                } catch (e: MQException) {
                    if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
                        ibmMqLog.debug(
                            "browse end of queue: queue=$queueName fetched=${result.size} " +
                                "duration=${System.currentTimeMillis() - start}ms",
                        )
                        return@withContext result
                    }
                    throw e
                }
                firstBrowse = false
                result += message.toSummary()
            }
            ibmMqLog.debug(
                "browse done: queue=$queueName fetched=${result.size} " +
                    "duration=${System.currentTimeMillis() - start}ms",
            )
            result
        } catch (cancellation: CancellationException) {
            // 取消不是「浏览失败」：吞成 error 日志并转成异常只会让 UI 弹假错误，
            // 而且会把调用方的取消语义抹掉。
            throw cancellation
        } catch (e: MQException) {
            ibmMqLog.error(
                "browse failed: queue=$queueName reason=MQRC ${e.reasonCode}: ${MqrcTranslator.translate(e.reasonCode)}",
            )
            throw e
        } catch (e: Exception) {
            ibmMqLog.error("browse failed: queue=$queueName error=${e.message ?: e::class.simpleName}")
            throw e
        }
    }

    /** 光标重置：下一次 [browseNext] 从队首重新开始（刷新用）。 */
    fun reset() {
        ibmMqLog.debug("browse cursor reset: queue=$queueName")
        firstBrowse = true
    }

    /** 关闭队列句柄；重复调用安全。 */
    fun close() {
        if (closed) return
        closed = true
        ibmMqLog.info("browse session closed: queue=$queueName")
        runCatching { queue.close() }
            .onFailure { ibmMqLog.warn("browse queue close failed: queue=$queueName error=${it.message}") }
    }

    private var closed = false

    companion object {

        /**
         * 在既有的 [MQQueueManager] 上打开浏览会话（复用连接的 TCP 通道，不另建连接）
         * 并与该连接共用 [mqiLock]（后续 [browseNext] 每页都持它）。
         *
         * **调用方必须已持有 [mqiLock]**（`IbmMQService.openBrowseSession` 在锁内调用，
         * 顺便把 `mqConnection` 的读取也罩住）——本函数自己不再取锁，否则直接死锁。
         * 连接已断 / QMGR 不可用时报 MQException 原样上抛，由 UI 转「加载失败」。
         */
        suspend fun open(
            manager: MQQueueManager,
            queueName: String,
            mqiLock: Mutex,
        ): MessageBrowseSession = withContext(Dispatchers.IO) {
            val openOptions =
                MQConstants.MQOO_BROWSE or MQConstants.MQOO_FAIL_IF_QUIESCING
            val start = System.currentTimeMillis()
            val resolvedName = queueName.trim()
            val queue = manager.accessQueue(resolvedName, openOptions)
            // 句柄已到手但还没交给会话时被取消：自己关掉，不然这个队列句柄没人管。
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancellation: CancellationException) {
                runCatching { queue.close() }
                throw cancellation
            }
            val session = MessageBrowseSession(queue, resolvedName, mqiLock)
            ibmMqLog.debug("browse session opened: queue=$queueName duration=${System.currentTimeMillis() - start}ms")
            session
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
internal fun java.util.Calendar?.toRawPutDateParts(): Pair<String, String> {
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
