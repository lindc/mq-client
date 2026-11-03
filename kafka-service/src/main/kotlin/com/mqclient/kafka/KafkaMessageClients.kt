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
 * 一条 Kafka 连接的**共享消息客户端**：一个浏览 consumer + 一个 producer，懒建、
 * 复用、直到连接断开或程序退出。
 *
 * ## 为什么不再每次操作都新建
 *
 * 旧实现每读一次消息 `KafkaConsumer(...)`、每发一条 `KafkaProducer(...)`，用完即关。
 * 两者都不便宜：
 *
 *  - **`KafkaConsumer` 构造即做元数据拉取**（`partitionsFor` / `beginningOffsets`
 *    都要先有 metadata），每次都重新握手一遍 coordinator / broker 连接池；
 *  - **`KafkaProducer` 构造起后台 sender 线程 + 缓冲池 + 到各 broker 的连接**。
 *    Kafka 官方明确 producer「设计为可共享、创建成本高」，逐条新建是最典型的误用。
 *
 * 现在的行为：进程内每条连接一份，`disconnect` / `connection.deleted` / 模块关闭时
 * 统一关闭（见 [KafkaConnectionManager.closeQuietly]）。
 *
 * ## 两个句柄的线程语义**不同**，别混为一谈
 *
 *  - **producer 线程安全**（Kafka 官方保证）：多协程并发发送直接共用，无需加锁。
 *  - **consumer 非线程安全**：`assign` / `seek` / `poll` 必须串行。[consumerAccess]
 *    就是那把锁，**调用方必须整段持有**（从 assign 到 poll 结束），不能只锁创建。
 *
 * ## 浏览语义不变
 *
 * consumer 固定走 `assign + seek + poll`（不 subscribe、不 join group），
 * `enable.auto.commit=false` 且全程不 commit —— 不产生任何提交位移，不影响任何
 * 消费组的进度。`group.id` 只是个过配置校验的固定 dummy 值（以前的随机值是为了
 * 「每次都是新消费者」，现在共享一个反而更干净：broker 侧不会攒出一堆幽灵组）。
 */
class KafkaMessageClients(private val config: KafkaConfig) {

    /**
     * 串行化 consumer 访问。**必须整段持有**（assign → seek → poll → 取结果），
     * 只锁创建等于没锁 —— 两个协程同时 seek 同一个 consumer 会互相窜改位置。
     */
    val consumerAccess = Mutex()

    @Volatile
    private var browseConsumer: KafkaConsumer<ByteArray, ByteArray>? = null

    @Volatile
    private var producer: KafkaProducer<ByteArray, ByteArray>? = null

    /** 创建 / 取用 consumer 的串行点（首次创建只发生一次）。 */
    private val createLock = Mutex()

    /** 共享浏览 consumer；首次调用时创建。 */
    suspend fun browseConsumer(): KafkaConsumer<ByteArray, ByteArray> {
        browseConsumer?.let { return it }
        return createLock.withLock {
            browseConsumer?.let { return@withLock it }
            val created = withContext(Dispatchers.IO) {
                KafkaConsumer<ByteArray, ByteArray>(browseConsumerProperties(config, MAX_POLL_RECORDS))
            }
            browseConsumer = created
            kafkaLog.debug("shared browse consumer created: bootstrap=${config.bootstrapServers}")
            created
        }
    }

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
     * 关闭全部句柄。**不等待 [consumerAccess]** —— 若此刻有协程正卡在 `poll` 里，
     * 等锁就等于把断开路径一起拖死（这正是 RabbitMQ 那轮踩过的坑）。
     *
     * 正确做法是 `wakeup()`：Kafka 官方指定的「从别的线程打断 poll」原语，它让
     * 进行中的 `poll` 立刻抛 `WakeupException` 返回，之后 `close` 才有意义。
     *
     * `close` 各自带超时：consumer 5s、producer 5s（Kafka 的 close 会等未完成的
     * 发送与心跳，没有上限就会卡 30s 默认值）。
     */
    suspend fun closeQuietly() = withContext(Dispatchers.IO) {
        val consumer = browseConsumer.also { browseConsumer = null }
        val prod = producer.also { producer = null }

        consumer?.let {
            // wakeup 是线程安全的；先唤醒再关，避免 close 等一个永远不返回的 poll。
            runCatching { it.wakeup() }
            runCatching { it.close(CLOSE_TIMEOUT) }
                .onFailure { e -> kafkaLog.warn("shared consumer close failed: $e") }
        }
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
