package com.mqclient.core.db

/**
 * 一条写操作命令的执行记录（对应 `mq_command` 表，见 mq_command.sq）。
 *
 * 由命令执行咽喉点（IBM MQ = MqscExecutor.executeSingle）组装进
 * `core.mq.command.executed` 事件，MqCommandHistoryHandler 落库。表只追加：
 * 一条记录即一次写操作命令的执行事实，不更新不删除。
 */
data class MqCommandRecord(
    /** 记录自身 UUIDv7（发事件前由发出方生成）。 */
    val id: String,
    /** 执行命令所用连接（UUIDv7，= ConnectionInfo.id）。 */
    val connectionId: String,
    /** 展开后发给 QMGR 的命令原文（不截断）。 */
    val command: String,
    /** 完整输出（成功 = PCF 回显；失败 = 错误翻译说明）；无输出为 null。 */
    val response: String?,
    /** SUCCESS / FAILED / CANCELLED（CANCELLED 预留：PCF 调用不可中断，取消发生在命令边界，当条命令照常完成）。 */
    val status: String,
    /** PCF/MQRC 原因码数字串；成功为 null。 */
    val reasonCode: String?,
    /** 命令发起时刻（Unix 毫秒）。 */
    val requestTime: Long,
    /** 命令返回时刻（Unix 毫秒）。 */
    val responseTime: Long?,
    /** responseTime - requestTime（毫秒）。 */
    val durationMs: Long?,
)
