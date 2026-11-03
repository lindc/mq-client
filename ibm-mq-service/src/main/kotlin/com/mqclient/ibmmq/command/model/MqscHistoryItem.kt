package com.mqclient.ibmmq.command.model

/**
 * 一条历史记录（按连接归档）。v1 只存内存（[com.mqclient.ibmmq.command.MqscHistoryRepository]），
 * 落库时沿用同一形状（id 换成自增主键即可）。
 */
data class MqscHistoryItem(
    val id: Long,
    val connectionId: String,
    /** 原始命令文本。 */
    val command: String,
    /** 执行是否成功。 */
    val success: Boolean,
    /** 记录时间（epoch 毫秒）。 */
    val createdTime: Long,
)
