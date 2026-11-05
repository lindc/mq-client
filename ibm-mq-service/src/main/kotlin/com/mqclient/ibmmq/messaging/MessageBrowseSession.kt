package com.mqclient.ibmmq.messaging

import com.ibm.mq.MQException
import com.ibm.mq.MQGetMessageOptions
import com.ibm.mq.MQMessage
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
 * 队列消息浏览会话：共享 [MQQueueManager]（连接级，TCP 通道由连接管理器持有），
 * 但**每次 [browseNext] 都独立 `accessQueue` 打开 MQQueue 并在 `finally` 关闭** ——
 * 句柄不跨线程、不跨请求复用，单次 MQI 异常不会污染后续任何操作。
 *
 * ## 为什么不再长期持有队列句柄
 *
 * 旧实现 open 时建一个 `MQQueue` 并跨所有 [browseNext] 复用（为了保住 browse
 * 光标）。但 MQI 句柄一旦遇到异常（MQRC 非 2033）就可能进入坏状态，之后每一页
 * 都复用同一个坏句柄 —— 与 RabbitMQ 那轮「channel 污染拖垮全局」同构。现在每次
 * 操作新建、finally 关闭，发送 / PCF / 单消息操作也都是这个口径。
 *
 * ## 光标语义的等价实现
 *
 * MQQueue 的 browse 光标是**句柄级**的，换句柄即丢失。为保持「加载更多」翻页，
 * 会话维护 [browsedCount]（已交付给 UI 的条数）：每次新建句柄后从队首
 * `BROWSE_FIRST` + `BROWSE_NEXT` 跳过这些条目再继续读（一律 `MQGMO_NO_WAIT`，
 * 单次立即返回）。代价是每页多一次 O(browsedCount) 的跳过扫描，但换来故障隔离。
 *
 * 并发：每次 `get` 都持所属连接的 [mqiLock]（与 PCF 扫描 / 发送共用）—— 共享
 * `MQQueueManager` 上的 MQI 请求/应答是串行的。
 */
class MessageBrowseSession private constructor(
    private val manager: MQQueueManager,
    /** 仅用于日志追踪的队列名。 */
    private val queueName: String,
    /** 所属连接的 MQI 互斥锁（与 PCF 扫描 / 发送共用，见类 KDoc）。 */
    private val mqiLock: Mutex,
) {

    /** 已交付给 UI 的条数；独立句柄无光标，靠它恢复「加载更多」位置。 */
    private var browsedCount = 0

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
        ibmMqLog.info("[MQ-Perf][IBM MQ] Service 开始处理拉取请求: queue=$queueName, count=$count")
        // 每次操作独立 open MQQueue；finally 关闭，异常也不会留下坏句柄。
        val queue = manager.accessQueue(
            queueName,
            MQConstants.MQOO_BROWSE or MQConstants.MQOO_FAIL_IF_QUIESCING,
        )
        try {
            val result = ArrayList<MessageSummary>(count)
            var ioMs = 0L
            var parseMs = 0L
            val options = MQGetMessageOptions()

            // 独立句柄没有 cursor：从队首跳过已浏览的 browsedCount 条，
            // 让本页从上次结束的位置继续（NO_WAIT，逐条立即返回）。
            var skipped = 0
            while (skipped < browsedCount) {
                // 对话框关掉 / 切队列时，重复的阻塞 get 必须停在这一圈。
                currentCoroutineContext().ensureActive()
                options.options =
                    (if (skipped == 0) MQConstants.MQGMO_BROWSE_FIRST else MQConstants.MQGMO_BROWSE_NEXT) or
                        MQConstants.MQGMO_NO_WAIT or MQConstants.MQGMO_FAIL_IF_QUIESCING
                val skippedMessage = MQMessage()
                var t = System.currentTimeMillis()
                try {
                    queue.get(skippedMessage, options)
                } catch (e: MQException) {
                    ioMs += System.currentTimeMillis() - t
                    if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
                        // 队列变短（消息被消费/清理）：已浏览位置收敛到实际存在的条目。
                        browsedCount = skipped
                        ibmMqLog.info(
                            "[MQ-Perf][IBM MQ] browse done (队尾/条目减少): queue=$queueName, ioMs=$ioMs, " +
                                "messages=0, totalMs=${System.currentTimeMillis() - start}ms",
                        )
                        return@withContext emptyList()
                    }
                    throw e
                }
                ioMs += System.currentTimeMillis() - t
                skipped++
            }

            repeat(count) { index ->
                currentCoroutineContext().ensureActive()
                // 未跳过（browsedCount==0）时首条必须 BROWSE_FIRST 定位光标；
                // 跳过过则光标已指向下一条，一律 BROWSE_NEXT。
                val useFirst = browsedCount == 0 && index == 0
                options.options =
                    (if (useFirst) MQConstants.MQGMO_BROWSE_FIRST else MQConstants.MQGMO_BROWSE_NEXT) or
                        MQConstants.MQGMO_NO_WAIT or MQConstants.MQGMO_FAIL_IF_QUIESCING
                val message = MQMessage()
                var t = System.currentTimeMillis()
                try {
                    queue.get(message, options)
                } catch (e: MQException) {
                    ioMs += System.currentTimeMillis() - t
                    if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
                        browsedCount += result.size
                        ibmMqLog.info(
                            "[MQ-Perf][IBM MQ] browse done (队尾): queue=$queueName, ioMs=$ioMs, " +
                                "parseMs=$parseMs, messages=${result.size}, totalMs=${System.currentTimeMillis() - start}ms",
                        )
                        return@withContext result
                    }
                    throw e
                }
                ioMs += System.currentTimeMillis() - t
                // 数据解析：MQMessage -> MessageSummary（DTO -> Domain）。
                t = System.currentTimeMillis()
                result += message.toSummary()
                parseMs += System.currentTimeMillis() - t
            }
            browsedCount += result.size
            ibmMqLog.info(
                "[MQ-Perf][IBM MQ] browse done: queue=$queueName, ioMs=$ioMs, parseMs=$parseMs, " +
                    "messages=${result.size}, totalMs=${System.currentTimeMillis() - start}ms",
            )
            result
        } catch (cancellation: CancellationException) {
            // 取消不是「浏览失败」：吞成 error 日志并转成异常只会让 UI 弹假错误，
            // 而且会把调用方的取消语义抹掉。
            throw cancellation
        } catch (e: MQException) {
            ibmMqLog.error(
                "[MQ-Perf][IBM MQ] 拉取失败, 异常类型=MQException, 错误码=${e.reasonCode}, " +
                    "详情=MQRC ${e.reasonCode}: ${MqrcTranslator.translate(e.reasonCode)}, 已耗时=${System.currentTimeMillis() - start}ms",
            )
            throw e
        } catch (e: Exception) {
            ibmMqLog.error(
                "[MQ-Perf][IBM MQ] 拉取失败, 异常类型=${e::class.simpleName}, " +
                    "详情=${e.message ?: "unknown"}, 已耗时=${System.currentTimeMillis() - start}ms",
            )
            throw e
        } finally {
            // 无论成功 / 2033 / MQException / 取消，句柄都在这里释放。
            runCatching { queue.close() }
                .onFailure { ibmMqLog.warn("browse queue close failed: queue=$queueName error=${it.message}") }
        }
    }

    /** 光标重置：下一次 [browseNext] 从队首重新开始（刷新用）。 */
    fun reset() {
        ibmMqLog.debug("browse cursor reset: queue=$queueName")
        browsedCount = 0
    }

    /** 关闭会话；队列句柄已改为每操作新建即关，这里只标记，重复调用安全。 */
    fun close() {
        if (closed) return
        closed = true
        ibmMqLog.info("browse session closed: queue=$queueName")
    }

    private var closed = false

    companion object {

        /**
         * 在既有的 [MQQueueManager] 上建立浏览会话（复用连接的 TCP 通道，不另建连接）
         * 并与该连接共用 [mqiLock]（后续 [browseNext] 每页都持它）。
         *
         * **调用方必须已持有 [mqiLock]**（`IbmMQService.openBrowseSession` 在锁内调用，
         * 顺便把 `mqConnection` 的读取也罩住）——本函数自己不再取锁，否则直接死锁。
         * 这里只做一次 passive 校验（独立句柄，用完即关），**不把句柄留给会话**。
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
            try {
                currentCoroutineContext().ensureActive()
            } finally {
                runCatching { queue.close() }
            }
            val session = MessageBrowseSession(manager, resolvedName, mqiLock)
            ibmMqLog.info(
                "[MQ-Perf][IBM MQ] 获取/初始化客户端实例耗时=${System.currentTimeMillis() - start}ms " +
                    "(browse 会话建立, queue=$queueName)",
            )
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
