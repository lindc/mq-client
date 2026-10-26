package com.mqclient.kafka

import com.mqclient.message.interpreter.isProbablyBinary
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer

/**
 * Kafka 消息读写服务：浏览（读）/ 发送（写）/ 详情，与 ibm-mq 的消息层
 * （MessageBrowseSession / MessageSender / MessageAccess）同职责、同「即时查询、
 * 不进缓存、不发事件」的定位。
 *
 * **句柄生命周期：Consumer / Producer 每次操作临时创建、finally 里 close** ——
 * 不挂 [KafkaConnectionContext]（consumer 非线程安全且有 poll 心跳语义，常驻管理
 * 成本远高于百 ms 级临时建连；断开连接时也没有游散句柄要追杀）。
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

        /** 发送 `.get()` 的额外余量（覆盖 delivery.timeout.ms 的 retry 全程）。 */
        const val SEND_EXTRA_WAIT_MS = 10_000L

        /** 发送的 delivery 超时（ConfigDef 要求 32 位 Int，别传 Long）。 */
        const val DELIVERY_TIMEOUT_MS = 60_000
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
    ): KafkaMessagePage =
        readMessages(connectedContext(connectionId).config, topic, partition, startFrom, count)

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
    ): KafkaMessagePage {
        val timeoutMs = config.connectionTimeout * 1_000L
        val capped = count.coerceIn(1, MAX_READ_COUNT)
        return withContext(Dispatchers.IO) {
            val consumer = newConsumer(config, capped)
            try {
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
                        require(startFrom.partition >= 0) { "指定 Offset 需先选择单个分区" }
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
                            return@withContext KafkaMessagePage(emptyList(), nextCursor = null)
                        }
                        val seekable = found.keys.toList()
                        consumer.assign(seekable)
                        found.forEach { (tp, o) -> consumer.seek(tp, o.offset()) }
                    }
                }
                collectPage(consumer, topic, capped, timeoutMs, previousCursor = null)
            } finally {
                runCatching { consumer.close(CLOSE_TIMEOUT) }
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
    ): KafkaMessagePage =
        readMoreMessages(connectedContext(connectionId).config, topic, cursor, count)

    /** [readMoreMessages] 的实现体（按 config 直调，同 [readMessages] 的拆分理由）。 */
    internal suspend fun readMoreMessages(
        config: KafkaConfig,
        topic: String,
        cursor: Map<Int, Long>,
        count: Int,
    ): KafkaMessagePage {
        val timeoutMs = config.connectionTimeout * 1_000L
        val capped = count.coerceIn(1, MAX_READ_COUNT)
        return withContext(Dispatchers.IO) {
            val consumer = newConsumer(config, capped)
            try {
                val tps = cursor.keys.map { TopicPartition(topic, it) }
                consumer.assign(tps)
                cursor.forEach { (p, offset) -> consumer.seek(TopicPartition(topic, p), offset) }
                collectPage(consumer, topic, capped, timeoutMs, previousCursor = cursor)
            } finally {
                runCatching { consumer.close(CLOSE_TIMEOUT) }
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
    ): KafkaMessageDetail =
        readMessageDetail(connectedContext(connectionId).config, topic, partition, offset)

    /** [readMessageDetail] 的实现体（按 config 直调，同 [readMessages] 的拆分理由）。 */
    internal suspend fun readMessageDetail(
        config: KafkaConfig,
        topic: String,
        partition: Int,
        offset: Long,
    ): KafkaMessageDetail {
        val timeoutMs = config.connectionTimeout * 1_000L
        return withContext(Dispatchers.IO) {
            val consumer = newConsumer(config, count = 1)
            try {
                val tp = TopicPartition(topic, partition)
                consumer.assign(listOf(tp))
                consumer.seek(tp, offset)
                val deadline = System.currentTimeMillis() + timeoutMs
                var record: ConsumerRecord<ByteArray, ByteArray>? = null
                while (record == null && System.currentTimeMillis() < deadline) {
                    record = consumer.poll(Duration.ofMillis(POLL_INTERVAL_MS))
                        .records(tp)
                        .firstOrNull { it.offset() >= offset }
                }
                record?.toDetail()
                    ?: throw IllegalStateException("offset $offset 不存在（可能已过期清除）")
            } finally {
                runCatching { consumer.close(CLOSE_TIMEOUT) }
            }
        }
    }

    /** poll 循环 + 排序截断 + 游标组装（readMessages / readMoreMessages 共用）。 */
    private fun collectPage(
        consumer: KafkaConsumer<ByteArray, ByteArray>,
        topic: String,
        count: Int,
        timeoutMs: Long,
        previousCursor: Map<Int, Long>?,
    ): KafkaMessagePage {
        val deadline = System.currentTimeMillis() + timeoutMs
        val collected = mutableListOf<ConsumerRecord<ByteArray, ByteArray>>()
        while (collected.size < count && System.currentTimeMillis() < deadline) {
            val records = consumer.poll(Duration.ofMillis(POLL_INTERVAL_MS))
            if (records.isEmpty) {
                // 到货后一轮空 poll = 尾部已到，收手（不白等满 deadline）；
                // 从未到货则继续转，直到 deadline（空分区 / 空 topic 场景）。
                if (collected.isNotEmpty()) break
                continue
            }
            records.forEach { collected += it }
        }
        val messages = KafkaMessageReadLogic.mergeAndCap(collected, count)
            .map { it.toSummary(topic) }
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
    ): KafkaSendResult =
        sendMessage(connectedContext(connectionId).config, topic, value, key, partition, headers)

    /** [sendMessage] 的实现体（按 config 直调，同 [readMessages] 的拆分理由）。 */
    internal suspend fun sendMessage(
        config: KafkaConfig,
        topic: String,
        value: ByteArray,
        key: ByteArray? = null,
        partition: Int? = null,
        headers: List<KafkaMessageHeader> = emptyList(),
    ): KafkaSendResult {
        val timeoutMs = config.connectionTimeout * 1_000L
        return withContext(Dispatchers.IO) {
            val producer = newProducer(config)
            try {
                val record = ProducerRecord<ByteArray, ByteArray>(topic, partition, key, value)
                headers.forEach { record.headers().add(it.key, it.value) }
                val meta = producer.send(record).get(timeoutMs + SEND_EXTRA_WAIT_MS, TimeUnit.MILLISECONDS)
                kafkaLog.info(
                    "message sent: topic=${meta.topic()} " +
                        "partition=${meta.partition()} offset=${meta.offset()} size=${value.size}",
                )
                KafkaSendResult.success(meta)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                KafkaSendResult.failure(errorMessage(error))
            } finally {
                runCatching { producer.close(CLOSE_TIMEOUT) }
            }
        }
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
            ?: throw IllegalStateException(
                KafkaModule.connectionOrNull(connectionId)?.let { connectionRefusalText(it) }
                    ?: "连接未登记：该连接已被删除或模块未初始化",
            )

    /** 分区解析：指定分区直接用；否则 partitionsFor 现查（不用扫描缓存 —— 分区数可能刚变）。 */
    private fun resolvePartitions(
        consumer: KafkaConsumer<ByteArray, ByteArray>,
        topic: String,
        partition: Int?,
    ): List<TopicPartition> {
        if (partition != null) return listOf(TopicPartition(topic, partition))
        val infos = consumer.partitionsFor(topic)
            ?: throw IllegalStateException("topic '$topic' 不存在或无权访问")
        return infos.sortedBy { it.partition() }.map { TopicPartition(topic, it.partition()) }
    }

    /** browse consumer 属性：连接四块 + assign-only 浏览所需的浏览器侧配置。 */
    private fun newConsumer(config: KafkaConfig, count: Int): KafkaConsumer<ByteArray, ByteArray> =
        KafkaConsumer(java.util.Properties().apply {
            putAll(config.toConnectionProperties())
            put("group.id", "mqclient-browse-${UUID.randomUUID()}")
            put("enable.auto.commit", false)
            put("key.deserializer", ByteArrayDeserializer::class.java.name)
            put("value.deserializer", ByteArrayDeserializer::class.java.name)
            put("max.poll.records", count)
            put("max.partition.fetch.bytes", MAX_PARTITION_FETCH_BYTES)
        })

    /** producer 属性：acks=all + 重试；幂等显式关（3.x acks=all 默认开，需 IdempotentWrite ACL）。 */
    private fun newProducer(config: KafkaConfig): KafkaProducer<ByteArray, ByteArray> =
        KafkaProducer(java.util.Properties().apply {
            putAll(config.toConnectionProperties())
            put("key.serializer", ByteArraySerializer::class.java.name)
            put("value.serializer", ByteArraySerializer::class.java.name)
            put("acks", "all")
            put("retries", 10)
            put("delivery.timeout.ms", DELIVERY_TIMEOUT_MS)
            put("linger.ms", 5)
            put("max.block.ms", config.connectionTimeout * 1_000)
            put("enable.idempotence", false)
        })

    private fun ConsumerRecord<ByteArray, ByteArray>.toSummary(topic: String): KafkaMessageSummary {
        val value = value()
        val key = key()
        return KafkaMessageSummary(
            topic = topic,
            partition = partition(),
            offset = offset(),
            timestamp = timestamp(),
            timestampType = timestampType().name,
            keyPreview = key?.let { previewOf(it) },
            keyHex = key?.let { hexOf(it) },
            valuePreview = previewOf(value),
            valueHex = hexOf(value),
            valueProbablyBinary = isProbablyBinary(value, codedCharSetId = 1208),
            valueSize = serializedValueSize(),
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

    /** UTF-8 容错解码（非法字节出替换符）+ 截断到 [PREVIEW_BYTES] 字节。 */
    private fun previewOf(bytes: ByteArray): String {
        val end = minOf(bytes.size, PREVIEW_BYTES)
        return String(bytes, 0, end, Charsets.UTF_8)
    }

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
