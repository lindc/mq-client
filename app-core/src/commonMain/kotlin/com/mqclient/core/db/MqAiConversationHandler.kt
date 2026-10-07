package com.mqclient.core.db

import com.mqclient.core.CoreModule
import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getLong
import com.mqclient.core.event.getString

/**
 * 处理 [EventNames.MQ_AI_CONVERSATION_COMPLETED]：把一次「MQ 对象 AI 分析问答」
 * 写进 mq_ai_conversation 表。
 *
 * 分工：事件的发出方是 AI 面板（ai-ui 的 AiPanelState，回答完成 / 失败 / 取消后各发一次，
 * 仅限从「询问 AI」入口发起的链路分析会话），payload 自包含，本类只做一件事 —— 忠实落库。
 * 任何失败都走错误通道上报，绝不上抛（EventBus 会就地吃掉 handler 异常，但显式 report
 * 能让用户看见）；写入失败不影响 AI 对话本身。
 */
class MqAiConversationHandler(private val repository: MqAiConversationRepository) {

    suspend fun handle(event: AppEvent) {
        if (event.name != EventNames.MQ_AI_CONVERSATION_COMPLETED) return

        // 必填字段缺一即放弃（事件由代码组装，缺字段属于编程错误，不值得落一条残缺记录）。
        val id = event.getString("conversationId") ?: return
        val connectionId = event.getString("connectionId") ?: return
        val question = event.getString("question") ?: return
        val status = event.getString("status") ?: return
        val requestTime = event.getLong("requestTime") ?: return

        val record = MqAiConversationRecord(
            id = id,
            connectionId = connectionId,
            objectType = event.getString("objectType"),
            objectName = event.getString("objectName"),
            question = question,
            contextJson = event.getString("contextJson"),
            answer = event.getString("answer"),
            status = status,
            errorMessage = event.getString("errorMessage"),
            requestTime = requestTime,
            responseTime = event.getLong("responseTime"),
            durationMs = event.getLong("durationMs"),
        )

        runCatching { repository.save(record) }
            .onFailure { error ->
                CoreModule.reportError(
                    message = "AI 分析问答记录写入失败（connectionId=$connectionId，question=${question.take(40)}）",
                    source = "core.db",
                    error = error,
                )
            }
    }
}
