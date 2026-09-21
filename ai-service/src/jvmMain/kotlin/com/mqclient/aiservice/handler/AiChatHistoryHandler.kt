package com.mqclient.aiservice.handler

import com.mqclient.aiservice.AiEvents
import com.mqclient.aiservice.AiPayloadKeys
import com.mqclient.aiservice.db.ChatHistoryRepository
import com.mqclient.core.CoreModule
import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.getInt
import com.mqclient.core.event.getLong
import com.mqclient.core.event.getString

/**
 * 处理 [AiEvents.AI_CHAT_HISTORY_SAVE]：把一条已完成的对话（成功或失败）写进 chat_history 表。
 * snapshot_id 由 [ChatHistoryRepository.saveWithLatestSnapshot] 在写入时查最新快照补上。
 *
 * 事件的发出方是 ai-panel 的聊天状态机（AI 回复完成 / 失败），职责只有忠实落库；
 * 失败走错误通道上报，不上抛。
 */
class AiChatHistoryHandler(private val repository: ChatHistoryRepository) {

    suspend fun handle(event: AppEvent) {
        if (event.name != AiEvents.AI_CHAT_HISTORY_SAVE) return

        val connectionId = event.getString(AiPayloadKeys.CONNECTION_ID) ?: return
        val modelName = event.getString(AiPayloadKeys.MODEL_NAME) ?: return

        runCatching {
            repository.saveWithLatestSnapshot(
                connectionId = connectionId,
                modelName = modelName,
                question = event.getString(AiPayloadKeys.QUESTION),
                reply = event.getString(AiPayloadKeys.REPLY),
                tokenCount = event.getInt(AiPayloadKeys.TOKEN_COUNT) ?: 0,
                promptTokens = event.getInt(AiPayloadKeys.PROMPT_TOKENS) ?: 0,
                completionTokens = event.getInt(AiPayloadKeys.COMPLETION_TOKENS) ?: 0,
                createdTime = event.getLong(AiPayloadKeys.CREATED_TIME) ?: System.currentTimeMillis(),
                startedTime = event.getLong(AiPayloadKeys.STARTED_TIME),
                finishedTime = event.getLong(AiPayloadKeys.FINISHED_TIME),
                status = event.getString(AiPayloadKeys.STATUS) ?: "SUCCESS",
                errorMessage = event.getString(AiPayloadKeys.ERROR_MESSAGE),
                finishReason = event.getString(AiPayloadKeys.FINISH_REASON),
            )
        }.onFailure { error ->
            CoreModule.reportError(
                message = "AI 对话记录写入失败（connectionId=$connectionId）",
                source = "ai-panel",
                error = error,
            )
        }
    }
}
