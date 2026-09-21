package com.mqclient.aiservice.handler

import com.mqclient.aiservice.AiEvents
import com.mqclient.aiservice.AiPayloadKeys
import com.mqclient.aiservice.db.AiConnectionSnapshotRepository
import com.mqclient.core.CoreModule
import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.getString

/**
 * 处理 [AiEvents.AI_CONNECTION_SNAPSHOT_CREATED]：把连接快照写进 ai_connection 表。
 *
 * 事件的发出方是本模块的 [com.mqclient.aiservice.AiConnectionRepository]（触发点是新建
 * 连接向导 / 设置对话框保存时的 url 变化检测），这里只做一件事 —— 忠实落库；任何失败都走错误通道上报，绝不上抛
 * （EventBus 的 handler 抛错会被就地吃掉，但显式 report 能让用户看见）。
 */
class AiConnectionSnapshotHandler(private val repository: AiConnectionSnapshotRepository) {

    suspend fun handle(event: AppEvent) {
        if (event.name != AiEvents.AI_CONNECTION_SNAPSHOT_CREATED) return

        val connectionId = event.getString(AiPayloadKeys.CONNECTION_ID) ?: return
        val prevId = event.getString(AiPayloadKeys.PREV_ID) // url 变化 = 旧连接的 UUID；全新建为 null
        val serviceUrl = event.getString(AiPayloadKeys.SERVICE_URL) ?: return

        runCatching { repository.saveSnapshot(connectionId, prevId, serviceUrl) }
            .onFailure { error ->
                CoreModule.reportError(
                    message = "AI 连接快照写入失败（connectionId=$connectionId）",
                    source = "ai-panel",
                    error = error,
                )
            }
    }
}
