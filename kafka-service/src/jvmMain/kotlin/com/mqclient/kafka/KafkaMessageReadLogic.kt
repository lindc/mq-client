package com.mqclient.kafka

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition

/**
 * 消息读取的**纯逻辑**（不碰 IO、不建连接）：seek 起点计算、跨分区合并排序、游标
 * 推进。提成独立 object 是为了让 `jvmTest` 离线单测 —— kafka-clients 在测试 classpath
 * 上就有，ConsumerRecord / TopicPartition 可以直接构造，不需要真 broker。
 */
internal object KafkaMessageReadLogic {

    /**
     * Latest / Earliest 模式 → 每分区 seek 起点。Latest 钳到 earliest 之上：`end - N`
     * 跨过 retention 起点时 seek 到更早的 offset 会拉到 OutOfRange。AtOffset /
     * AtTimestamp 由 service 层直接调 consumer API（beginningOffsets 等没有纯函数形态），
     * 这里返回空表。
     */
    fun computeSeekTargets(
        start: KafkaReadStart,
        tps: List<TopicPartition>,
        beginnings: Map<TopicPartition, Long>,
        ends: Map<TopicPartition, Long>,
    ): Map<TopicPartition, Long> = when (start) {
        is KafkaReadStart.Latest -> tps.associate { tp ->
            val earliest = beginnings[tp] ?: 0L
            val end = ends[tp] ?: 0L
            tp to maxOf(earliest, end - start.count)
        }
        is KafkaReadStart.Earliest -> tps.associate { tp ->
            tp to (beginnings[tp] ?: 0L)
        }
        else -> emptyMap()
    }

    /**
     * 跨分区合并排序 + 截断：timestamp（epoch millis，负值 = 无时间戳排最后）→
     * partition → offset 升序；超出 [count] 时保留**末尾**（最新）的 count 条 ——
     * 「最新 N」全分区模式下自然超采，截掉的是最旧的。
     */
    fun mergeAndCap(
        records: List<ConsumerRecord<ByteArray, ByteArray>>,
        count: Int,
    ): List<ConsumerRecord<ByteArray, ByteArray>> {
        if (count <= 0) return emptyList()
        return records.sortedWith(
            compareBy(
                { it.timestamp().takeIf { ts -> ts >= 0 } ?: Long.MAX_VALUE },
                { it.partition() },
                { it.offset() },
            ),
        ).takeLast(count)
    }

    /**
     * 游标推进：**实际读到记录**的分区才进游标（记 maxOffset + 1），空分区不产生
     * 条目 —— 续读只续已读分区，不会从空分区 beginning 吐回一堆旧数据打乱「加载更多」。
     */
    fun nextCursorOf(records: List<ConsumerRecord<ByteArray, ByteArray>>): Map<Int, Long> =
        records.groupBy { it.partition() }
            .mapValues { (_, recs) -> recs.maxOf { it.offset() } + 1 }
}
