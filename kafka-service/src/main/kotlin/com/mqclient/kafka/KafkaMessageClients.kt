package com.mqclient.kafka

import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer

/**
 * 一条 Kafka 连接的**共享消息客户端**：只持有连接级 Producer（懒建、复用、直到
 * 连接断开或程序退出）。
 *
 * ## 句柄隔离规范（底层连接共享，业务句柄用完即毁）
 *
 *  - **Producer 共享**：构造起后台 sender 线程 + 缓冲池 + 到各 broker 的连接，
 *    Kafka 官方明确「设计为可共享、创建成本高」，且线程安全，多协程并发直接共用。
 *    每条连接只建一次，`disconnect` / `connection.deleted` / 模块关闭时统一关闭。
 *  - **Consumer 绝不共享**：`KafkaConsumer` 非线程安全，`assign` / `seek` / `poll`
 *    带位置状态，跨请求复用会让一次 poll 异常污染后续所有读取。每次「读取消息」/
 *    后台拉取都由 [KafkaMessageService.withBrowseConsumer] 新建独立实例，`finally`
 *    中 `close`（5s 上限）。构造虽需元数据拉取，但换来的是故障隔离。
 *
 * ## 浏览语义不变
 *
 * consumer 固定走 `assign + seek + poll`（不 subscribe、不 join group），
 * `enable.auto.commit=false` 且全程不 commit —— 不产生任何提交位移，不影响任何
 * 消费组的进度。`group.id` 只是个过配置校验的固定 dummy 值，broker 侧不会攒出
 * 一堆幽灵组。
 */
class KafkaMessageClients(private val config: KafkaConfig) {

    /**
     * 共享 Producer（连接级唯一）。**不再持有任何 Consumer** —— 每次读取由
     * [KafkaMessageService.withBrowseConsumer] 新建独立实例并 finally close，
     * 见那里的说明。
     */
    @Volatile
    private var producer: KafkaProducer<ByteArray, ByteArray>? = null

    /** 共享 Producer 懒建的串行点（双检，只建一次）。 */
    private val createLock = Mutex()

    /** 共享 producer；首次调用时创建。**线程安全，可直接并发使用**。 */
    suspend fun producer(): KafkaProducer<ByteArray, ByteArray> {
        producer?.let { return it }
        return createLock.withLock {
            producer?.let { return@withLock it }
            val created = withContext(Dispatchers.IO) {
                KafkaProducer<ByteArray, ByteArray>(producerProperties(config))
            }
            producer = created
            kafkaLog.debug("shared producer created: bootstrap=${config.bootstrapServers}")
            created
        }
    }

    /**
     * 关闭共享 Producer（连接断开 / 模块关闭时）。Consumer 已改为每次操作独立并
     * 在 `finally` 关闭，断开路径不再需要 wakeup / 等锁。
     *
     * `close` 带 5s 超时（Kafka 的 close 会等未完成的发送与心跳，没有上限会卡
     * 30s 默认值）。
     */
    suspend fun closeQuietly() = withContext(Dispatchers.IO) {
        val prod = producer.also { producer = null }
        prod?.let {
            runCatching { it.close(CLOSE_TIMEOUT) }
                .onFailure { e -> kafkaLog.warn("shared producer close failed: $e") }
        }
        kafkaLog.debug("shared message clients closed")
    }

    internal companion object {
        /**
         * 浏览 consumer 的固定 group.id。
         *
         * 旧实现每次 `mqclient-browse-<uuid>` 造一个新组 —— broker 侧的组列表会被这些
         * 幽灵组污染（虽然 assign-only 不 join，但客户端仍会以该 id 出现在连接信息里）。
         * 固定一个即可：assign-only 从不提交位移，共用不会串进度。
         */
        const val BROWSE_GROUP_ID = "mqclient-browse"

        /** 单次 poll 的上限：具体条数由调用方 collectPage 截断。 */
        const val MAX_POLL_RECORDS = 500

        /** browse consumer 的单分区取回上限：默认 1MB 会让大消息直接 RecordTooLargeException。 */
        const val MAX_PARTITION_FETCH_BYTES = 16 * 1024 * 1024

        /** 发送的 delivery 超时（ConfigDef 要求 32 位 Int）。 */
        const val DELIVERY_TIMEOUT_MS = 60_000

        /**
         * 等 ack 的额外余量：send() 返回之后 delivery 计时才开始，留一点收尾时间。
         */
        const val SEND_MARGIN_MS = 5_000L

        /**
         * 发送要等多久才算“确定失败”（毫秒）。
         *
         * ## 为什么不能拍一个固定值
         *
         * 旧实现是 `.get(connectionTimeout + 10s)`（默认 40s），而 producer 的
         * `delivery.timeout.ms = 60s`。`Future.get` 超时**不会取消**发送：sender 线程
         * 还会继续重试到 delivery 截止。于是 40–60s 这个窗口里，UI 已经报“发送失败”，
         * 消息却可能已被 broker 收下 —— 用户一点重试就是真实重复消息。
         *
         * 现在的上界按 Kafka 的语义逐项相加：
         *  - `max.block.ms`（= `connectionTimeout`）—— `send()` 等待元数据的上限；
         *  - `delivery.timeout.ms` —— `send()` 返回后到给出成功/失败的上限。
         *
         * 等满这两段 + [SEND_MARGIN_MS]，我们报的失败才与 Kafka 的结论一致。
         */
        fun sendWaitMs(config: KafkaConfig): Long =
            config.connectionTimeout * 1_000L + DELIVERY_TIMEOUT_MS + SEND_MARGIN_MS

        /** close 的等待上限。 */
        val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)

        /**
         * 浏览 consumer 的属性。**唯一来源** —— 共享句柄（本类）与临时句柄（diag / 单测）
         * 都从这里取，否则两边配置漂移后“diag 过了但生产挂”这类问题极难查。
         */
        fun browseConsumerProperties(config: KafkaConfig, maxPollRecords: Int): java.util.Properties =
            java.util.Properties().apply {
                putAll(config.toConnectionProperties())
                // 固定 dummy 组：assign-only 不 join 组、不提交，group.id 只用于过配置校验。
                put("group.id", BROWSE_GROUP_ID)
                put("enable.auto.commit", false)
                put("key.deserializer", ByteArrayDeserializer::class.java.name)
                put("value.deserializer", ByteArrayDeserializer::class.java.name)
                put("max.poll.records", maxPollRecords)
                put("max.partition.fetch.bytes", MAX_PARTITION_FETCH_BYTES)
            }

        /**
         * producer 的属性（同上：唯一来源）。
         *
         * acks=all + 重试；幂等显式关（3.x 的 acks=all 默认会开幂等，需 IdempotentWrite ACL）。
         */
        fun producerProperties(config: KafkaConfig): java.util.Properties =
            java.util.Properties().apply {
                putAll(config.toConnectionProperties())
                put("key.serializer", ByteArraySerializer::class.java.name)
                put("value.serializer", ByteArraySerializer::class.java.name)
                put("acks", "all")
                put("retries", 10)
                put("delivery.timeout.ms", DELIVERY_TIMEOUT_MS)
                put("linger.ms", 5)
                put("max.block.ms", config.connectionTimeout * 1_000)
                put("enable.idempotence", false)
            }
    }
}
