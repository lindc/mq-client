package com.mqclient.kafka

import com.mqclient.core.ServiceException
import com.mqclient.message.interpreter.isProbablyBinary
import com.mqclient.message.interpreter.previewTextOf
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition

/**
 * Kafka 消息读写服务：浏览（读）/ 发送（写）/ 详情，与 ibm-mq 的消息层
 * （MessageBrowseSession / MessageSender / MessageAccess）同职责、同「即时查询、
 * 不进缓存、不发事件」的定位。
 *
 * **句柄生命周期：连接级共享 Producer，业务级隔离 Consumer**（[KafkaMessageClients]）。
 * Producer 挂在 [KafkaConnectionContext] 上，懒建、随连接存活、断开 / 退出时统一关闭
 * （构造要起 sender 线程与 broker 连接池，Kafka 官方明确应共享）。
 * Consumer 非线程安全且 `assign / seek / poll` 带位置状态：每次读取新建独立实例，
 * [withBrowseConsumer] 的 `finally` 强制 `close` —— 一次 poll 异常不会再污染后续
 * 所有请求。AdminClient 同样是连接级共享句柄。
 *
 * **浏览不消费**：consumer 走 `assign + seek + poll`（不 subscribe、不 join group），
 * `enable.auto.commit=false` 且全程不 commit —— 不产生任何提交位移，不影响任何
 * 消费组的进度。group.id 只是为了过配置校验的 dummy 值。
 *
 * 续读游标（`Map<分区, 下一条 offset>`）由调用方持有 —— service 无状态化，每次
 * 调用自包含（对比 MQ 的 browse session：MQI 光标天生在服务端，Kafka 的游标只是
 * 一个 map，没必要造 session 类）。
 *
 * 全程 Dispatchers.IO + 显式超时（`connectionTimeout` 同源），与 [KafkaScanService]
 * 的纪律一致；CancellationException 一律原样上抛。
 */
class KafkaMessageService {

    private companion object {
        /** 单次 poll 的间隔：也是「到货后收手」的检测粒度。 */
        const val POLL_INTERVAL_MS = 500L

        /** browse consumer 的单分区取回上限：默认 1MB 会让大消息直接 RecordTooLargeException。 */
        const val MAX_PARTITION_FETCH_BYTES = 16 * 1024 * 1024

        /** 单次读取的条数上限（UI 侧同样约束）。 */
        const val MAX_READ_COUNT = 500

        /** 临时 consumer / producer 的 close 等待。 */
        val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
    }

    // ---------------------------------------------------------------- 浏览（读）

    /**
     * 按起点读一页消息。**不提交任何 offset**，不影响消费进度。
     *
     * @param partition null = 全部分区（跨分区合并排序，超采截最新）
     * @param startFrom 起点：最新往前 / 最旧往后 / 指定 offset / 指定时间戳
     * @param count 读取条数（1..500，超范围收敛）
     */
    suspend fun readMessages(
        connectionId: String,
        topic: String,
        partition: Int?,
        startFrom: KafkaReadStart,
        count: Int,
    ): KafkaMessagePage {
        val ctx = connectedContext(connectionId)
        kafkaLog.info(
            "[MQ-Perf][Kafka] Service 开始处理拉取请求: topic=$topic, partition=$partition, count=$count",
        )
        return readMessages(ctx.config, topic, partition, startFrom, count, ctx)
    }

    /**
     * [readMessages] 的实现体：按 [KafkaConfig] 直调（diag 测试不经模块连接守卫也能
     * 走到同一条代码路径）。
     */
    internal suspend fun readMessages(
        config: KafkaConfig,
        topic: String,
        partition: Int?,
        startFrom: KafkaReadStart,
        count: Int,
        ctx: KafkaConnectionContext? = null,
    ): KafkaMessagePage {
        val timeoutMs = config.connectionTimeout * 1_000L
        val capped = count.coerceIn(1, MAX_READ_COUNT)
        return withContext(Dispatchers.IO) {
            withBrowseConsumer(ctx, config, capped) { consumer ->
                val tps = resolvePartitions(consumer, topic, partition)
                consumer.assign(tps)
                when (startFrom) {
                    is KafkaReadStart.Latest, is KafkaReadStart.Earliest -> {
                        val beginnings = consumer.beginningOffsets(tps, Duration.ofMillis(timeoutMs))
                        val ends = consumer.endOffsets(tps, Duration.ofMillis(timeoutMs))
                        KafkaMessageReadLogic
                            .computeSeekTargets(startFrom, tps, beginnings, ends)
                            .forEach { (tp, offset) -> consumer.seek(tp, offset) }
                    }
                    is KafkaReadStart.AtOffset -> {
                        // offset 是分区内概念：全部分区（partition=null 已在 UI 收敛）或
                        // 非法分区号直接拒绝，避免 seek 到不存在的分区。
                        require(startFrom.partition >= 0) {
            throw ServiceException(
                "kafka_err_offset_needs_partition",
                fallbackMessage = "A specific offset requires a single selected partition",
            )
        }
                        consumer.seek(
                            TopicPartition(topic, startFrom.partition),
                            startFrom.offset,
                        )
                    }
                    is KafkaReadStart.AtTimestamp -> {
                        // 查不到时间点的分区（该时刻之后才有数据 / 空分区）直接剔除。
                        val found = consumer
                            .offsetsForTimes(tps.associateWith { startFrom.epochMillis }, Duration.ofMillis(timeoutMs))
                            .filterValues { it != null }
                        if (found.isEmpty()) {
                            // withBrowseConsumer 的 lambda 不是 inline，只能返回到它。
                            return@withBrowseConsumer KafkaMessagePage(emptyList(), nextCursor = null)
                        }
                        val seekable = found.keys.toList()
                        consumer.assign(seekable)
                        found.forEach { (tp, o) -> consumer.seek(tp, o.offset()) }
                    }
                }
                collectPage(consumer, topic, capped, timeoutMs, previousCursor = null)
            }
        }
    }

    /**
     * 续读一页：按上次返回的游标 seek（只 assign 游标里的分区 —— 续读期间 broker
     * 新增分区不可见，重新「读取」即可），nextCursor 与旧游标合并（本次没读到的
     * 分区保留原值，多分区交错读时空分区不丢游标）。
     */
    suspend fun readMoreMessages(
        connectionId: String,
        topic: String,
        cursor: Map<Int, Long>,
        count: Int,
    ): KafkaMessagePage {
        val ctx = connectedContext(connectionId)
        kafkaLog.info(
            "[MQ-Perf][Kafka] Service 开始处理续读请求: topic=$topic, cursorPartitions=${cursor.keys}, count=$count",
        )
        return readMoreMessages(ctx.config, topic, cursor, count, ctx)
    }

    /** [readMoreMessages] 的实现体（按 config 直调，同 [readMessages] 的拆分理由）。 */
    internal suspend fun readMoreMessages(
        config: KafkaConfig,
        topic: String,
        cursor: Map<Int, Long>,
        count: Int,
        ctx: KafkaConnectionContext? = null,
    ): KafkaMessagePage {
        val timeoutMs = config.connectionTimeout * 1_000L
        val capped = count.coerceIn(1, MAX_READ_COUNT)
        return withContext(Dispatchers.IO) {
            withBrowseConsumer(ctx, config, capped) { consumer ->
                val tps = cursor.keys.map { TopicPartition(topic, it) }
                consumer.assign(tps)
                cursor.forEach { (p, offset) -> consumer.seek(TopicPartition(topic, p), offset) }
                collectPage(consumer, topic, capped, timeoutMs, previousCursor = cursor)
            }
        }
    }

    /**
     * 精确定位读一条消息（详情 / 下载用）。浏览到打开详情之间消息可能被 retention
     * 清除 —— 超时拿不到目标 offset 时抛状态异常（UI 显示可读失败态）。
     */
    suspend fun readMessageDetail(
        connectionId: String,
        topic: String,
        partition: Int,
        offset: Long,
    ): KafkaMessageDetail {
        val ctx = connectedContext(connectionId)
        return readMessageDetail(ctx.config, topic, partition, offset, ctx)
    }

    /** [readMessageDetail] 的实现体（按 config 直调，同 [readMessages] 的拆分理由）。 */
    internal suspend fun readMessageDetail(
        config: KafkaConfig,
        topic: String,
        partition: Int,
        offset: Long,
        ctx: KafkaConnectionContext? = null,
    ): KafkaMessageDetail {
        val timeoutMs = config.connectionTimeout * 1_000L
        return withContext(Dispatchers.IO) {
            withBrowseConsumer(ctx, config, maxPollRecords = 1) { consumer ->
                val tp = TopicPartition(topic, partition)
                consumer.assign(listOf(tp))
                consumer.seek(tp, offset)
                val deadline = System.currentTimeMillis() + timeoutMs
                var record: ConsumerRecord<ByteArray, ByteArray>? = null
                while (record == null && System.currentTimeMillis() < deadline) {
                    // 同 collectPage：对话框关掉时要能立刻停下，不能等满 connectionTimeout。
                    currentCoroutineContext().ensureActive()
                    record = runInterruptible { consumer.poll(Duration.ofMillis(POLL_INTERVAL_MS)) }
                        .records(tp)
                        .firstOrNull { it.offset() >= offset }
                }
                record?.toDetail()
                    ?: throw ServiceException(
                        "kafka_err_offset_not_found",
                        args = listOf(offset),
                        fallbackMessage = "Offset %1\$d does not exist (it may have been deleted by retention)",
                    )
            }
        }
    }

    /**
     * poll 循环 + 排序截断 + 游标组装（readMessages / readMoreMessages 共用）。
     *
     * `poll` 是**阻塞**调用，不响应协程取消；必须：
     *  1. 每次 poll 都经 [runInterruptible]（KafkaConsumer 文档明确 `interrupt()` 是
     *     唤醒 poll 的方式，RocketMQ 侧已是同一做法）；
     *  2. 循环头查一次取消，避免「刚好空闲时还在转」。
     * 只靠 `now < deadline` 的话，关掉对话框后 IO 线程会一直被钉到 `connectionTimeout`
     * （默认 30s），期间同一台机器上其它浏览请求一起变慢。
     */
    private suspend fun collectPage(
        consumer: KafkaConsumer<ByteArray, ByteArray>,
        topic: String,
        count: Int,
        timeoutMs: Long,
        previousCursor: Map<Int, Long>?,
    ): KafkaMessagePage = collectPageWithPoll(topic, count, timeoutMs, previousCursor) {
        runInterruptible { consumer.poll(Duration.ofMillis(POLL_INTERVAL_MS)) }
    }

    /**
     * [collectPage] 的可测内核：poll 抽成 lambda，便于在无 broker 的环境下断言
     * 「循环响应取消」。生产调用方见 [collectPage]。
     */
    internal suspend fun collectPageWithPoll(
        topic: String,
        count: Int,
        timeoutMs: Long,
        previousCursor: Map<Int, Long>?,
        pollOnce: suspend () -> ConsumerRecords<ByteArray, ByteArray>,
    ): KafkaMessagePage {
        val deadline = System.currentTimeMillis() + timeoutMs
        val collected = mutableListOf<ConsumerRecord<ByteArray, ByteArray>>()
        var ioMs = 0L
        while (collected.size < count && System.currentTimeMillis() < deadline) {
            // 取消检查：poll 之间必须能停下来（runInterruptible 负责打断在飞的那一次）。
            currentCoroutineContext().ensureActive()
            // 网络 IO：poll 本体（每轮上限 POLL_INTERVAL_MS=500ms）。
            val t0 = System.currentTimeMillis()
            val records = pollOnce()
            ioMs += System.currentTimeMillis() - t0
            if (records.isEmpty) {
                // 到货后一轮空 poll = 尾部已到，收手（不白等满 deadline）；
                // 从未到货则继续转，直到 deadline（空分区 / 空 topic 场景）。
                if (collected.isNotEmpty()) break
                continue
            }
            records.forEach { collected += it }
        }
        // 数据解析：ConsumerRecord -> KafkaMessageSummary（DTO -> Domain）。
        val parseStart = System.currentTimeMillis()
        val messages = KafkaMessageReadLogic.mergeAndCap(collected, count)
            .map { it.toSummary(topic) }
        kafkaLog.info(
            "[MQ-Perf][Kafka] collectPage done: topic=$topic, ioMs=$ioMs, " +
                "parseMs=${System.currentTimeMillis() - parseStart}, messages=${messages.size}",
        )
        val advanced = KafkaMessageReadLogic.nextCursorOf(collected)
        val exhausted = collected.size < count
        val merged = when {
            exhausted -> null
            previousCursor == null -> advanced
            else -> previousCursor + advanced // 旧分区保留原值，本次读到的覆盖推进
        }
        return KafkaMessagePage(messages, nextCursor = merged)
    }

    // ---------------------------------------------------------------- 发送（写）

    /**
     * 发送一条消息。阻塞等 ack 与元数据（partition/offset/timestamp），失败转
     * [KafkaSendResult.failure] 不上抛（批量逐条记状态需要）；真正的协程取消除外。
     * 每次临时建 producer（对标 ibm-mq MessageSender 的独立句柄模式）。
     *
     * 消息生产**不进写操作审计**（mq_command 表语义是管理操作，且高频批量会膨胀
     * 审计表 —— 与 IBM MQ 的 MqscExecutor 咽喉点口径一致）。
     */
    suspend fun sendMessage(
        connectionId: String,
        topic: String,
        value: ByteArray,
        key: ByteArray? = null,
        partition: Int? = null,
        headers: List<KafkaMessageHeader> = emptyList(),
    ): KafkaSendResult {
        val ctx = connectedContext(connectionId)
        return sendMessage(ctx.config, topic, value, key, partition, headers, ctx)
    }

    /** [sendMessage] 的实现体（按 config 直调，同 [readMessages] 的拆分理由）。 */
    internal suspend fun sendMessage(
        config: KafkaConfig,
        topic: String,
        value: ByteArray,
        key: ByteArray? = null,
        partition: Int? = null,
        headers: List<KafkaMessageHeader> = emptyList(),
        ctx: KafkaConnectionContext? = null,
    ): KafkaSendResult {
        return withContext(Dispatchers.IO) {
            // 共享 producer 线程安全，可直接并发发送；临时句柄（diag）用完即关。
            if (ctx != null) {
                sendBlocking(ctx.clients().producer(), topic, value, key, partition, headers, config)
            } else {
                val producer = newProducer(config)
                try {
                    sendBlocking(producer, topic, value, key, partition, headers, config)
                } finally {
                    runCatching { producer.close(CLOSE_TIMEOUT) }
                }
            }
        }
    }

    /**
     * 发送主体（同步等 ack）；共享 / 临时两条路径共用。
     *
     * 等待上限用 [KafkaMessageClients.sendWaitMs]（= max.block.ms + delivery.timeout.ms
     * + 余量），**不再**用 `connectionTimeout + 10s`：后者比 `delivery.timeout.ms` 短，
     * 会在 40–60s 窗口里报“失败”而消息其实可能已投递，用户重试就是重复消息。
     */
    private fun sendBlocking(
        producer: KafkaProducer<ByteArray, ByteArray>,
        topic: String,
        value: ByteArray,
        key: ByteArray?,
        partition: Int?,
        headers: List<KafkaMessageHeader>,
        config: KafkaConfig,
    ): KafkaSendResult =
        try {
            val record = ProducerRecord<ByteArray, ByteArray>(topic, partition, key, value)
            headers.forEach { record.headers().add(it.key, it.value) }
            val meta = producer.send(record).get(KafkaMessageClients.sendWaitMs(config), TimeUnit.MILLISECONDS)
            kafkaLog.info(
                "message sent: topic=${meta.topic()} " +
                    "partition=${meta.partition()} offset=${meta.offset()} size=${value.size}",
            )
            KafkaSendResult.success(meta)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            KafkaSendResult.failure(errorMessage(error))
        }

    // ---------------------------------------------------------------- 内部零件

    /**
     * 消息会话的上下文：只要 AdminClient 还在（即这条连接真的连上过）就允许，不拿
     * `status` 当否决票 —— 与扫描 / 请求入口同一口径。否则一次抖动误标的 `Failed`
     * 会把消息浏览 / 发送也一并堵死，而底层连接其实还能用。
     *
     * 真的没连（或已断开）时 `adminClient` 已由 `cleanup()` 置空，这里仍然会拒，
     * 并给出分层原因。
     */
    private fun connectedContext(connectionId: String): KafkaConnectionContext =
        KafkaModule.connectionOrNull(connectionId)
            ?.takeIf { it.adminClient != null }
            ?: throw KafkaModule.connectionOrNull(connectionId)?.let { connectionRefusal(it) }
                ?: ServiceException(
                    "kafka_err_not_registered",
                    fallbackMessage = "Connection not registered: it was deleted or the module is not initialized",
                )

    /** 分区解析：指定分区直接用；否则 partitionsFor 现查（不用扫描缓存 —— 分区数可能刚变）。 */
    private fun resolvePartitions(
        consumer: KafkaConsumer<ByteArray, ByteArray>,
        topic: String,
        partition: Int?,
    ): List<TopicPartition> {
        if (partition != null) return listOf(TopicPartition(topic, partition))
        val infos = consumer.partitionsFor(topic)
            ?: throw ServiceException(
                    "kafka_err_topic_not_found",
                    args = listOf(topic),
                    fallbackMessage = "Topic '%1\$s' does not exist or is not accessible",
                )
        return infos.sortedBy { it.partition() }.map { TopicPartition(topic, it.partition()) }
    }

    /** 临时浏览 consumer（diag / 单测路径）：属性与共享句柄同源。 */
    private fun newConsumer(config: KafkaConfig, count: Int): KafkaConsumer<ByteArray, ByteArray> =
        KafkaConsumer(KafkaMessageClients.browseConsumerProperties(config, count))

    /** 临时 producer（diag / 单测路径）：属性与共享句柄同源。 */
    private fun newProducer(config: KafkaConfig): KafkaProducer<ByteArray, ByteArray> =
        KafkaProducer(KafkaMessageClients.producerProperties(config))

    /**
     * 取本连接的共享消息客户端（懒建，挂在 context 上）。
     *
     * 创建点**只有这里一处**。守卫用 `adminClient != null` —— 与 [connectedContext]
     * 完全同口径：只要管理客户端还在（说明这条连接真的连过），就允许建消息句柄。
     * 不能用 `status == Connected`：扫描连续失败会把状态暂置 `Failed` 而 adminClient
     * 仍在，那种"可恢复"的窗口里读消息应该继续可用。
     */
    private fun KafkaConnectionContext.clients(): KafkaMessageClients =
        messageClients.acquire(canCreate = { adminClient != null }) {
            KafkaMessageClients(config)
        }

    /**
     * 消费者取用：共享（生产路径）或临时（diag）。
     *
     * 共享路径**整段持锁** —— `assign / seek / poll` 是一个不可分割的序列，只锁
     * 句柄创建等于没锁：两个协程同时 seek 同一个 consumer 会互相窜位置。
     */
    private suspend fun <T> withBrowseConsumer(
        ctx: KafkaConnectionContext?,
        config: KafkaConfig,
        maxPollRecords: Int,
        block: suspend (KafkaConsumer<ByteArray, ByteArray>) -> T,
    ): T {
        // 规范：重量级连接（AdminClient / Producer）共享，业务句柄（Consumer）隔离。
        // KafkaConsumer 非线程安全，且 assign / seek / poll 带位置状态 —— 严禁跨协程、
        // 跨请求复用：一次 poll 异常会污染内部状态，后续读取全部瘫痪。每次读取都新建
        // 独立 Consumer，无论成功或异常都在 finally 中 close（带 5s 上限）。
        // ctx 仅用于日志标记连接 / diag 路径，不再决定句柄来源。
        val t0 = System.currentTimeMillis()
        val consumer = newConsumer(config, maxPollRecords)
        kafkaLog.info(
            "[MQ-Perf][Kafka] 获取/初始化客户端实例耗时=${System.currentTimeMillis() - t0}ms " +
                "(每次读取新建独立 Consumer, connection=${ctx?.connectionId ?: "diag"})",
        )
        try {
            return block(consumer)
        } finally {
            runCatching { consumer.close(CLOSE_TIMEOUT) }
                .onFailure { kafkaLog.warn("browse consumer close failed: $it") }
        }
    }

    private fun ConsumerRecord<ByteArray, ByteArray>.toSummary(topic: String): KafkaMessageSummary {
        // tombstone（value = null，合法的「删除标记」）当空消息处理：原来直接喂给
        // previewOf / isProbablyBinary 会 NPE，整页读取都会挂。
        val value = value() ?: ByteArray(0)
        val key = key()
        return KafkaMessageSummary(
            topic = topic,
            partition = partition(),
            offset = offset(),
            timestamp = timestamp(),
            timestampType = timestampType().name,
            keyPreview = key?.let { previewTextOf(it) },
            keyHex = key?.let { hexOf(it) },
            valuePreview = previewTextOf(value),
            valueHex = hexOf(value),
            valueProbablyBinary = isProbablyBinary(value, codedCharSetId = 1208),
            // null value 的 serializedValueSize() 是 -1；统一成 0，UI 才能靠
            // `valueSize == 0` 判定「空消息」（见 KafkaMessageBrowseTab 的 ValueCell）。
            valueSize = serializedValueSize().coerceAtLeast(0),
        )
    }

    private fun ConsumerRecord<ByteArray, ByteArray>.toDetail(): KafkaMessageDetail = KafkaMessageDetail(
        topic = topic(),
        partition = partition(),
        offset = offset(),
        timestamp = timestamp(),
        timestampType = timestampType().name,
        keyBytes = key(),
        valueBytes = value(),
        headers = headers().map { KafkaMessageHeader(it.key(), it.value()) },
        keySize = key()?.size,
        valueSize = serializedValueSize(),
    )

    /** hex 串，截断到 2×[PREVIEW_BYTES]（与预览字节对齐）。 */
    private fun hexOf(bytes: ByteArray): String {
        val end = minOf(bytes.size, PREVIEW_BYTES)
        val sb = StringBuilder(end * 2)
        for (i in 0 until end) {
            sb.append("%02x".format(bytes[i]))
        }
        return sb.toString()
    }

    /** 给日志 / 失败结果的错误原因：解掉 ExecutionException 包装拿根因（与 KafkaScanService 同款）。 */
    private fun errorMessage(error: Throwable): String =
        (error as? java.util.concurrent.ExecutionException)?.cause?.message
            ?: error.message
            ?: error::class.simpleName
            ?: "unknown"
}
