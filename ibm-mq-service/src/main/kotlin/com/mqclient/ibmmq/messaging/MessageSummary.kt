package com.mqclient.ibmmq.messaging

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 队列消息的列表展示模型（browse 光标逐条摘要，不取消息体）。
 *
 * 字段全部来自 MQMD：MessageId / CorrelationId 是 24 字节二进制，统一转
 * **大写十六进制串**（MQ Explorer 同款显示，48 字符）后再进模型，UI 层不碰字节。
 *
 * 消息体不在列表里加载——用户点「详细」时再按需读取（下一步实装）。
 */
data class MessageSummary(
    /** MQMD.MessageId 的十六进制串。 */
    val messageId: String,
    /** MQMD.CorrelationId 的十六进制串。 */
    val correlationId: String,
    /** MQMD.PutDate 原始串（YYYYMMDD）。 */
    val putDate: String,
    /** MQMD.PutTime 原始串（HHMMSSss，末两位是百分秒）。 */
    val putTime: String,
    /** 消息体长度（字节，MQMessage.dataLength，不含头）。 */
    val dataLength: Int,
    /** MQMD.Persistence（0=非持久，1=持久）。 */
    val persistence: Int,
    /** MQMD.Priority（0-9）。 */
    val priority: Int,
    /** MQMD.MsgType（列表不显示，备用，供详情页区分报告消息等）。 */
    val msgType: Int,
) {
    /**
     * PutDate + PutTime（截掉百分秒）解析成 [LocalDateTime]；
     * 脏数据解析失败返回 null（时间过滤时该条只对「全部」可见）。
     */
    val putDateTime: LocalDateTime? by lazy {
        runCatching {
            LocalDateTime.parse(
                putDate + putTime.take(6),
                DateTimeFormatter.ofPattern("yyyyMMddHHmmss"),
            )
        }.getOrNull()
    }
}
