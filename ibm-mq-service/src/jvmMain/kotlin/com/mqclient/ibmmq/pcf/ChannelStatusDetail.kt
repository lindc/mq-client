package com.mqclient.ibmmq.pcf

/**
 * 单通道运行状态明细（DISPLAY CHSTATUS 语义，实时查询、不经慢扫缓存）。
 *
 * 属性对话框的「运行状态」分组数据源：与 DISPLAY CHANNEL 的定义属性严格分层 ——
 * 序列号 / in-doubt / 字节计数等只在通道有活动实例时才有值；通道已完全停止
 * （CHSTATUS 无状态行）时查询方法返回 null，UI 显示「通道未运行」。
 *
 * 字段可用性随通道类型变化（SDR/RCVR/RQSTR/SVR 各有不同子集），缺失即 null，
 * 由 UI 按「不适用的类型显示不适用、适用的类型缺数据显示 -」区分呈现。
 */
data class ChannelStatusDetail(
    /** 通道名。 */
    val channelName: String,
    /** 通道类型可读名（SENDER / RECEIVER / CLUSRCVR…）。 */
    val channelType: String?,
    /** 运行状态可读名（RUNNING / RETRYING / STOPPED…）。 */
    val status: String?,
    /** 当前序列号（CURSEQNO / MQIACH_CURRENT_SEQ_NUMBER）。 */
    val currentSeqNo: Long?,
    /** 最后序列号（LSEQNUM / MQIACH_LAST_SEQ_NUMBER）。 */
    val lastSeqNum: Long?,
    /** In-doubt 状态（INDOUBT；true = 有未决消息）。 */
    val inDoubt: Boolean?,
    /** 已处理消息数（MSGS）。 */
    val messages: Long?,
    /** 已发送字节数（BYTSSENT）。 */
    val bytesSent: Long?,
    /** 已接收字节数（BYTSRCVD）。 */
    val bytesReceived: Long?,
    /** 最后一条消息日期（LSTMSGDA，yyyyMMdd）。 */
    val lastMsgDate: String?,
    /** 最后一条消息时间（LSTMSGTI，HHmmss）。 */
    val lastMsgTime: String?,
)
