package com.mqclient.kafka

import org.apache.kafka.clients.producer.RecordMetadata

/**
 * 消息域数据模型：浏览列表（Summary）/ 详情（Detail）/ 读取起点（[KafkaReadStart]）/
 * 一页结果（[KafkaMessagePage]）/ 发送结果（[KafkaSendResult]）。
 *
 * 刻意不放 [KafkaSnapshot.kt]：那份文件约定是「扫描缓存快照（Snapshot 后缀）」，随
 * 定时扫描落缓存；消息模型是即时查询的返回值，不进缓存、生命周期只到 UI 渲染为止。
 *
 * ByteArray 字段的 data class equals/hashCode 是引用比较 —— 这些类一律不要当
 * LazyColumn key / remember key / 相等判断用（定位消息用 `partition:offset`）。
 */

/** 列表与详情的 Key / Value 预览截断字节数。 */
internal const val PREVIEW_BYTES = 256

/** 一条消息头：[org.apache.kafka.common.header.Header] 的投影（value 为 null 合法）。 */
data class KafkaMessageHeader(
    val key: String,
    val value: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is KafkaMessageHeader && other.key == key && other.value.contentEquals(value)

    override fun hashCode(): Int = key.hashCode() * 31 + value.contentHashCode()
}

/**
 * 消息列表行（轻量）：value 只带预览，完整字节经 [KafkaMessageService.readMessageDetail]
 * 现查。key/valuePreview 是前 [PREVIEW_BYTES] 字节的 UTF-8 容错解码（非法字节出替换
 * 符）；对应 hex 截断到 2×[PREVIEW_BYTES]，供疑似二进制时展示。
 */
data class KafkaMessageSummary(
    val topic: String,
    val partition: Int,
    val offset: Long,
    /** 记录时间戳（epoch millis）；NO_TIMESTAMP_TYPE 时为 -1，UI 显示 "-"。 */
    val timestamp: Long,
    /** TimestampType.name：CREATE_TIME / LOG_APPEND_TIME / NO_TIMESTAMP_TYPE。 */
    val timestampType: String,
    val keyPreview: String?,
    val keyHex: String?,
    val valuePreview: String,
    val valueHex: String,
    /** 疑似二进制（message-interpreter-api 的 isProbablyBinary），列表据此选 preview 或 hex。 */
    val valueProbablyBinary: Boolean,
    val valueSize: Int,
)

/** 消息详情（详情对话框 / 下载用）：现查现返，不进任何缓存。 */
data class KafkaMessageDetail(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val timestamp: Long,
    val timestampType: String,
    val keyBytes: ByteArray?,
    val valueBytes: ByteArray,
    /** 消息头列表，按 ConsumerRecord.headers() 的原始顺序。 */
    val headers: List<KafkaMessageHeader>,
    val keySize: Int?,
    val valueSize: Int,
)

/**
 * 浏览起点（UI 顶部选择区的 service 投影）：
 *  - [Latest]：每分区 seek 到 `max(earliest, end - N)`（钳位防 OutOfRange）；
 *  - [Earliest]：每分区 seek 到 beginning；
 *  - [AtOffset]：指定 offset（仅单分区模式可用，UI 侧保证）；
 *  - [AtTimestamp]：offsetsForTimes 定位，查不到的分区跳过。
 */
sealed interface KafkaReadStart {
    data class Latest(val count: Int) : KafkaReadStart
    data class Earliest(val count: Int) : KafkaReadStart
    data class AtOffset(val partition: Int, val offset: Long) : KafkaReadStart
    data class AtTimestamp(val epochMillis: Long) : KafkaReadStart
}

/** 一页读取结果。nextCursor = null 表示已耗尽（无可续读游标）。 */
data class KafkaMessagePage(
    val messages: List<KafkaMessageSummary>,
    /** 分区 → 下一条的 offset（readMoreMessages 的续读入参）。 */
    val nextCursor: Map<Int, Long>?,
)

/** 发送结果（对标 ibm-mq 的 SendResult）：Kafka 多返回定位三元组 partition/offset/timestamp。 */
data class KafkaSendResult(
    val success: Boolean,
    val topic: String? = null,
    val partition: Int? = null,
    val offset: Long? = null,
    val timestamp: Long? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun success(meta: RecordMetadata): KafkaSendResult = KafkaSendResult(
            success = true,
            topic = meta.topic(),
            partition = meta.partition(),
            offset = meta.offset(),
            timestamp = meta.timestamp(),
        )

        fun failure(message: String): KafkaSendResult = KafkaSendResult(
            success = false,
            errorMessage = message,
        )
    }
}
