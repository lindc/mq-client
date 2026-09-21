package com.mqclient.aipanel.handler

import com.mqclient.aipanel.db.ChatHistoryRepository
import com.mqclient.core.CoreModule
import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.getInt
import com.mqclient.core.event.getLong
import com.mqclient.core.event.getString

/**
 * 处理 `ai.chat.history.save`：把一条已完成的对话（成功或失败）写进 chat_history 表。
 * snapshot_id 由 [ChatHistoryRepository.saveWithLatestSnapshot] 在写入时查最新快照补上。
 *
 * 事件的发出方是 ai-panel 的聊天状态机（AI 回复完成 / 失败），职责只有忠实落库；
 * 失败走错误通道上报，不上抛。
 */
class AiChatHistoryHandler(private val repository: ChatHistoryRepository) {

    suspend fun handle(event: AppEvent) {
        if (event.name != AiEvents.AI_CHAT_HISTORY_SAVE) return

        val connectionId = event.getString("connectionId") ?: return
        val modelName = event.getString("modelName") ?: return

        runCatching {
            repository.saveWithLatestSnapshot(
                connectionId = connectionId,
                modelName = modelName,
                question = event.getString("question"),
                reply = event.getString("reply"),
                tokenCount = event.getInt("tokenCount") ?: 0,
                promptTokens = event.getInt("promptTokens") ?: 0,
                completionTokens = event.getInt("completionTokens") ?: 0,
                createdTime = event.getLong("createdTime") ?: System.currentTimeMillis(),
                startedTime = event.getLong("startedTime"),
                finishedTime = event.getLong("finishedTime"),
                status = event.getString("status") ?: "SUCCESS",
                errorMessage = event.getString("errorMessage"),
                finishReason = event.getString("finishReason"),
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
