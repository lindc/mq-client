package com.mqclient.ibmmq.messaging

import com.ibm.mq.MQException
import com.ibm.mq.MQGetMessageOptions
import com.ibm.mq.MQMessage
import com.ibm.mq.MQQueue
import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import com.mqclient.core.ServiceException
import com.mqclient.ibmmq.command.MqrcTranslator
import com.mqclient.ibmmq.ibmMqLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * 并**常驻一个 `MQQueue` browse 句柄**保住服务端光标 —— 「加载更多」是真正的
 * `BROWSE_NEXT`，每页 O(取走条数)，不是从头重扫。
 *
 * ## 为什么可以常驻（历史上曾改成一页一句柄）
 *
 * 曾担心「MQI 句柄一旦遇到异常就进入坏状态，之后每页都复用同一个坏句柄」（与
 * RabbitMQ 的 channel 污染同构）。但那轮修复的代价是把翻页变成了 O(已加载)：
 * 第 N 页要从队首扫 `(N-1)×页大小` 条才能恢复位置，且整页握着连接的 `mqiLock`，
 * 深队列下浏览会把 PCF 心跳 / 发送 / MQSC 一起挡住。
 *
 * 现在的做法是把「故障隔离」做在**失效后重建**上，而不是做在「每次都不用旧句柄」上：
 * 任何非 2033 的 [MQException] 都立即 `close()` 掉当前句柄、置空，下一次 [browseNext]
 * 重新打开并把光标**按 MessageId 定位**回去（见下）。所以「坏句柄不会被复用第二
 * 次」依旧成立，而正常路径不再付 O(n)。
 *
 * ## 光标重建：按 MessageId 定位，一次往返
 *
 * 重开句柄后若已经有交付记录，用 [BrowseCursor.lastLiveDeliveredId] 拿最后一条
 * **仍在队列里**的已交付消息，以 `MQGMO_BROWSE_FIRST + MQMO_MATCH_MSG_ID` 直接定位到
 * 它（读出来丢弃，它已经交付过），随后 `BROWSE_NEXT` 继续。这是服务端索引查找，
 * 与队列深度无关。
 *
 * 只有定位也失败时（消息被别的应用消费掉、或队列不支持该匹配）才退回
 * 「从队首 BROWSE_FIRST + 按 Id 跳过」的 O(已交付) 扫描 —— 正确性不变，只是慢。
 * 删除消息会让游标位置失效，处理方式相同：那个 Id 已不在队列里，锚点自动退到
 * 前一条（见 [BrowseCursor.onMessageRemoved]）。
 *
 * 并发：整页浏览持所属连接的 [mqiLock]（与 PCF 扫描 / 发送共用）—— 共享
 * `MQQueueManager` 上的 MQI 请求/应答是串行的。句柄本身也只在这把锁里访问。
 */
class MessageBrowseSession private constructor(
    private val manager: MQQueueManager,
    /** 仅用于日志追踪的队列名。 */
    private val queueName: String,
    /** 所属连接的 MQI 互斥锁（与 PCF 扫描 / 发送共用，见类 KDoc）。 */
    private val mqiLock: Mutex,
    /** `open()` 时已经建好的 browse 句柄（建立会话就验证过队列可打开，不浪费这次往返）。 */
    initialQueue: MQQueue?,
) {

    /**
     * 身份化游标（见 [BrowseCursor]）：既做「从队首扫时跳过已交付」的降级路径，
     * 也提供句柄重建时的定位锚点（最后一条仍在队列里的已交付消息）。
     */
    private val cursor = BrowseCursor()

    /**
     * 常驻的 browse 句柄。
     *
     * **只在持 [mqiLock] 时读写**（[browsePage] 与 [close] 的后台任务都在锁内），
     * 因此不需要额外的并发保护。`@Volatile` 仅用于让 [close] 能安全地观察当前引用。
     */
    @Volatile
    private var browseQueue: MQQueue? = initialQueue

    /** 下一次 `get` 用 `BROWSE_FIRST`（队首）还是 `BROWSE_NEXT`（沿光标）。 */
    private var atHead = true

    /**
     * 需要按 MessageId 重新定位光标（句柄重建 / 删除过消息）。
     *
     * 与 [atHead] 分工：`atHead` 是「下一次 get 用哪个选项」，它是**结果**；
     * 本标志是「现在还不知道位置，得先定位」的**意图**。
     */
    private var resyncRequested = false

    /** [reset] 请求回到队首（刷新）；真正的重置在 [browsePage] 的锁内发生。 */
    @Volatile
    private var restartRequested = false

    /**
     * 浏览下一条（内部按 [count] 循环逐条 get，串行）。队列读空（MQRC 2033）时
     * 正常返回已拿到的部分——调用方以「返回条数 < [count]」判定浏览耗尽。
     *
     * 锁在**最外层**：整页浏览对扫描而言是一个原子操作，否则半页浏览与一轮 PCF
     * 查询会交替压在同一条 MQ 通道上。
     */
    suspend fun browseNext(count: Int): List<MessageSummary> = mqiLock.withLock { browsePage(count) }

    /**
     * 用户消费（删除）了一条已经展示的消息：游标账上减一。
     *
     * 不把这一步放进 [browseNext] 自动做，是因为删除走的是另一条链路（按 MessageId
     * 破坏性 get），会话无从得知。UI 删除成功后调用本方法。
     */
    fun onMessageRemoved(messageId: String) {
        cursor.onMessageRemoved(messageId)
        // 删除会改变队列内容，浏览光标在服务端的位置不再可信（尤其删的正是光标
        // 所在的那条）。下一次取数重新定位到「还存在的最后一条已交付消息」。
        resyncRequested = true
    }

    private suspend fun browsePage(count: Int): List<MessageSummary> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        ibmMqLog.info("[MQ-Perf][IBM MQ] Service 开始处理拉取请求: queue=$queueName, count=$count")
        checkOpen()
        if (restartRequested) {
            // 刷新：回到队首。不必重开句柄 —— BROWSE_FIRST 本身就会重置光标。
            restartRequested = false
            cursor.reset()
            atHead = true
            resyncRequested = false
        }
        val queue = ensureQueue()
        var ioMs = 0L
        var parseMs = 0L
        try {
            // 需要重新定位（新句柄 / 刚删过消息）：用最后一条仍在队列里的已交付
            // 消息做锚点，O(1) 把光标放回去。定位失败才退回队首扫描。
            if (resyncRequested && cursor.lastLiveDeliveredId() != null) {
                val repositionStart = System.currentTimeMillis()
                if (repositionAtLastDelivered(queue)) atHead = false
                ioMs += System.currentTimeMillis() - repositionStart
            }
            resyncRequested = false

            val result = ArrayList<MessageSummary>(count)
            // 本页已交付的 Id：**整页成功返回时**才记账。
            //
            // 逐条记账是错的：循环中抛出 / 取消时 result 会被丢掉，而记过的 Id 会被
            // 下一页当成「已经给过 UI」而跳过 —— 用户永远看不到那几条。
            val delivered = ArrayList<String>(count)
            val options = MQGetMessageOptions()
            // 从队首扫时（新会话 / 定位失败）要跳过已交付的；光标已定位时这个
            // 跳过集合也留着 —— 它对光标之后的条目通常不命中，代价只是一次哈希查找。
            val scan = cursor.startScan()
            while (result.size < count) {
                // 对话框关掉 / 切队列时，重复的阻塞 get 必须停在这一圈。
                currentCoroutineContext().ensureActive()
                options.options =
                    (if (atHead) MQConstants.MQGMO_BROWSE_FIRST else MQConstants.MQGMO_BROWSE_NEXT) or
                        MQConstants.MQGMO_NO_WAIT or MQConstants.MQGMO_FAIL_IF_QUIESCING
                val message = MQMessage()
                var t = System.currentTimeMillis()
                try {
                    queue.get(message, options)
                } catch (e: MQException) {
                    ioMs += System.currentTimeMillis() - t
                    // 队尾：正常结束，不是错误。
                    if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) break
                    throw e
                }
                ioMs += System.currentTimeMillis() - t
                atHead = false
                val messageId = message.messageId.toMqIdHex()
                if (scan.shouldSkip(messageId)) continue
                // 数据解析：MQMessage -> MessageSummary（DTO -> Domain）。
                t = System.currentTimeMillis()
                result += message.toSummary()
                delivered += messageId
                parseMs += System.currentTimeMillis() - t
            }
            // 走到这里说明这一页要交给调用方了（包括 2033 提前收尾的部分结果）。
            delivered.forEach(cursor::recordDelivered)
            ibmMqLog.info(
                "[MQ-Perf][IBM MQ] browse done: queue=$queueName, ioMs=$ioMs, parseMs=$parseMs, " +
                    "messages=${result.size}, tracked=${cursor.trackedCount()}, " +
                    "totalMs=${System.currentTimeMillis() - start}ms",
            )
            result
        } catch (cancellation: CancellationException) {
            // 取消不是「浏览失败」：吞成 error 日志并转成异常只会让 UI 弹假错误，
            // 而且会把调用方的取消语义抹掉。
            //
            // 但光标已经停在「本页已读、却没能交给 UI」的位置之后了。若不处理，
            // 下一页会从这里继续，那几条就永远不会展示 —— 所以标记重新定位，
            // 让下一次取数回到「最后一条真正交付过的消息」。
            resyncRequested = true
            throw cancellation
        } catch (e: MQException) {
            ibmMqLog.error(
                "[MQ-Perf][IBM MQ] 拉取失败, 异常类型=MQException, 错误码=${e.reasonCode}, " +
                    "详情=MQRC ${e.reasonCode}: ${MqrcTranslator.translate(e.reasonCode)}, 已耗时=${System.currentTimeMillis() - start}ms",
            )
            // 非 2033 的 MQI 异常意味着句柄可能已进入坏状态：立即丢弃，绝不复用。
            // 下一次取数重新打开并按 MessageId 定位 —— 这就是「故障隔离」的实现，
            // 不需要靠「每页都换句柄」来换。
            discardQueue(queue)
            throw e
        } catch (e: Exception) {
            ibmMqLog.error(
                "[MQ-Perf][IBM MQ] 拉取失败, 异常类型=${e::class.simpleName}, " +
                    "详情=${e.message ?: "unknown"}, 已耗时=${System.currentTimeMillis() - start}ms",
            )
            // 同取消：位置与已交付记录可能已经对不上，下次取数重新定位。
            resyncRequested = true
            throw e
        }
    }

    /**
     * 取常驻句柄；没有就新开一个。
     *
     * 新开的句柄没有光标，所以重置 [atHead]；如果已经交付过东西，还需要按锚点定位
     * （由调用方通过 [resyncRequested] 决定）。
     *
     * 必须在 [mqiLock] 内调用。
     */
    private fun ensureQueue(): MQQueue {
        browseQueue?.let { return it }
        val opened = manager.accessQueue(
            queueName,
            MQConstants.MQOO_BROWSE or MQConstants.MQOO_FAIL_IF_QUIESCING,
        )
        browseQueue = opened
        atHead = true
        // 有交付记录就必须定位，否则新句柄会从队首重新交付。
        resyncRequested = cursor.lastLiveDeliveredId() != null
        ibmMqLog.debug("browse queue opened: queue=$queueName resync=$resyncRequested")
        return opened
    }

    /**
     * 把新句柄的光标定位到最后一条**仍在队列里**的已交付消息上。
     *
     * 用 `MQGMO_BROWSE_FIRST + MQMO_MATCH_MSG_ID`：服务端按 MessageId 索引查找，
     * 与队列深度无关。读到的那条**不会交付**（它已经展示过），只是把光标停上去，
     * 随后 `BROWSE_NEXT` 从下一条继续。
     *
     * @return true = 定位成功（光标已就位，下一次 get 用 BROWSE_NEXT）；
     *   false = 锚点消息已不存在，调用方退回队首扫描（仍然正确，只是 O(n)）。
     */
    private fun repositionAtLastDelivered(queue: MQQueue): Boolean {
        val target = cursor.lastLiveDeliveredId() ?: return false
        val idBytes = target.mqIdFromHex()
        if (idBytes == null) {
            ibmMqLog.warn("browse reposition skipped (unparsable messageId): queue=$queueName id=$target")
            return false
        }
        val options = MQGetMessageOptions()
        options.options =
            MQConstants.MQGMO_BROWSE_FIRST or
                MQConstants.MQGMO_NO_WAIT or
                MQConstants.MQGMO_FAIL_IF_QUIESCING
        options.matchOptions = MQConstants.MQMO_MATCH_MSG_ID
        val message = MQMessage()
        message.messageId = idBytes
        return try {
            queue.get(message, options)
            ibmMqLog.debug("browse cursor repositioned: queue=$queueName id=$target")
            true
        } catch (e: MQException) {
            if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
                // 锚点被别的应用消费掉了：退回队首扫描。这不是错误。
                ibmMqLog.info(
                    "browse reposition target gone, falling back to head scan: queue=$queueName id=$target",
                )
                false
            } else {
                throw e
            }
        }
    }

    /** 丢弃当前句柄（已坏 / 已不适用的光标）：关掉并置空，下次取数重建。 */
    private fun discardQueue(dead: MQQueue) {
        browseQueue = null
        atHead = true
        resyncRequested = cursor.lastLiveDeliveredId() != null
        runCatching { dead.close() }
            .onFailure { ibmMqLog.warn("browse queue close failed: queue=$queueName error=${it.message}") }
    }

    private fun checkOpen() {
        if (closed) {
            throw ServiceException(
                "ibm_mq_err_browse_session_closed",
                fallbackMessage = "The browse session is closed",
            )
        }
    }

    /** 光标重置：下一次 [browseNext] 从队首重新开始（刷新用）。 */
    fun reset() {
        ibmMqLog.debug("browse cursor reset: queue=$queueName")
        // 真正的重置在 browsePage 的锁内做（清账必须与 get 串行）。
        restartRequested = true
    }

    /**
     * 关闭会话：释放常驻的 browse 句柄，重复调用安全。
     *
     * 关闭是阻塞 MQI 调用，而且必须与在飞的 `get` 串行（同一把 [mqiLock]），所以
     * 不能在这里同步关：调用方是 Compose 的 `onDispose`，卡住就是界面冻结。
     * 丢到有界后台线程池里，等锁上限 [CLOSE_LOCK_WAIT_MS]；等不到就放弃 ——
     * 连接断开时 `MQQueueManager.disconnect()` 会把所属队列一并关掉，不会永久泄漏。
     */
    fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        closed = true
        val queue = browseQueue.also { browseQueue = null }
        ibmMqLog.info("browse session closed: queue=$queueName hadQueue=${queue != null}")
        if (queue == null) return
        val submitted = runCatching {
            closeWorkers.execute {
                val locked = try {
                    runBlocking { withTimeoutOrNull(CLOSE_LOCK_WAIT_MS) { mqiLock.withLock { true } } } == true
                } catch (t: Throwable) {
                    ibmMqLog.warn("browse close lock wait failed: queue=$queueName error=${t.message}")
                    false
                }
                if (!locked) {
                    ibmMqLog.warn(
                        "browse queue left open after ${CLOSE_LOCK_WAIT_MS}ms lock wait: queue=$queueName " +
                            "(the connection's disconnect will release it)",
                    )
                    return@execute
                }
                runCatching { queue.close() }
                    .onFailure { ibmMqLog.warn("browse queue close failed: queue=$queueName error=${it.message}") }
            }
        }
        if (submitted.isFailure) {
            ibmMqLog.warn("browse queue close not scheduled (pool saturated): queue=$queueName")
        }
    }

    @Volatile
    private var closed = false

    /** [close] 的收尾只跑一次。 */
    private val closeStarted = AtomicBoolean(false)

    companion object {

        /**
         * 在既有的 [MQQueueManager] 上建立浏览会话（复用连接的 TCP 通道，不另建连接）
         * 并与该连接共用 [mqiLock]（后续 [browseNext] 每页都持它）。
         *
         * **调用方必须已持有 [mqiLock]**（`IbmMQService.openBrowseSession` 在锁内调用，
         * 顺便把 `mqConnection` 的读取也罩住）——本函数自己不再取锁，否则直接死锁。
         * 这里开的队列句柄**直接交给会话**（不再「验证一下然后关掉」）：既验证了
         * 队列可打开，也省掉一次多余的 accessQueue —— 句柄就是后续翻页的光标载体。
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
            } catch (cancellation: CancellationException) {
                // 取消时句柄刚建好，不能留 — 这里还没有会话能接管它。
                runCatching { queue.close() }
                throw cancellation
            }
            val session = MessageBrowseSession(manager, resolvedName, mqiLock, queue)
            ibmMqLog.info(
                "[MQ-Perf][IBM MQ] 获取/初始化客户端实例耗时=${System.currentTimeMillis() - start}ms " +
                    "(browse 会话建立, queue=$queueName)",
            )
            session
        }

        /**
         * 关闭后台任务池。
         *
         * 只为 [close] 服务：关句柄是阻塞 MQI 调用，必须在 [mqiLock] 内、且不能在
         * Compose 的 `onDispose` 线程上等。上限 2 条线程就够（一个对话框同时只会关
         * 一个会话），队列有界以免堆积。
         */
        private val closeWorkers: ThreadPoolExecutor = ThreadPoolExecutor(
            1,
            2,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(32),
        ) { runnable -> Thread(runnable, "ibm-mq-browse-close").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }

        /** [close] 等 [mqiLock] 的上限：等不到就交给连接的 disconnect 兜底。 */
        private const val CLOSE_LOCK_WAIT_MS = 5_000L
    }
}

/**
 * 大写 hex → MQ 的 MessageId 字节；格式不对返回 null。
 *
 * 与 [toMqIdHex] 互逆。只接受偶数长度、全部是十六进制字符的串 —— 宁可返回 null
 * （调用方退回队首扫描）也不要抛异常，因为锚点来自本会话自己交付过的 Id。
 */
internal fun String.mqIdFromHex(): ByteArray? {
    if (isEmpty() || length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
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
