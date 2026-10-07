package com.mqclient.core.db

/**
 * 一次「MQ 对象 AI 分析问答」的记录（对应 `mq_ai_conversation` 表，见 mq_ai_conversation.sq）。
 *
 * 由 AI 面板（ai-ui 的 AiPanelState）在回答完成 / 失败 / 取消后组装进
 * `core.mq.ai.conversation.completed` 事件，MqAiConversationHandler 落库。表只追加：
 * 一条记录即一次分析问答的事实，不更新不删除。
 */
data class MqAiConversationRecord(
    /** 记录自身 UUIDv7（发事件前由发出方生成）。 */
    val id: String,
    /** 发起分析所用连接（UUIDv7，= ConnectionInfo.id）。 */
    val connectionId: String,
    /** 目标对象类型（QUEUE / CHANNEL / LISTENER）；未知为 null。 */
    val objectType: String?,
    /** 目标对象名（如 ORDER.LOCAL.Q、QM1.TO.QM2）；未知为 null。 */
    val objectName: String?,
    /** 用户问题原文（不含上下文 JSON 包装）。 */
    val question: String,
    /** 发给模型的链路上下文 JSON（currentTime / chain / userQuestion 结构）；无上下文为 null。 */
    val contextJson: String?,
    /** AI 回答（FAILED 时为已流出的部分文本，可能 null）。 */
    val answer: String?,
    /** SUCCESS / FAILED / CANCELLED。 */
    val status: String,
    /** 失败原因；成功 / 取消为 null。 */
    val errorMessage: String?,
    /** 问题发起时刻（Unix 毫秒）。 */
    val requestTime: Long,
    /** 回答完成 / 失败时刻（Unix 毫秒）。 */
    val responseTime: Long?,
    /** responseTime - requestTime（毫秒）。 */
    val durationMs: Long?,
)
