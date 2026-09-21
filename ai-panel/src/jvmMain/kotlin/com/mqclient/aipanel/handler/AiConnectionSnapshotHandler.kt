package com.mqclient.aipanel.handler

import com.mqclient.aipanel.db.AiConnectionSnapshotRepository
import com.mqclient.core.CoreModule
import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.getString

/**
 * 处理 `ai.connection.snapshot.created`：把连接快照写进 ai_connection 表。
 *
 * 事件的发出方是 app-core（新建连接向导 / 设置对话框保存的 url 变化检测），
 * 这里的职责只有一件事 —— 忠实落库；任何失败都走错误通道上报，绝不上抛
 * （EventBus 的 handler 抛错会被就地吃掉，但显式 report 能让用户看见）。
 */
class AiConnectionSnapshotHandler(private val repository: AiConnectionSnapshotRepository) {

    suspend fun handle(event: AppEvent) {
        if (event.name != AiEvents.AI_CONNECTION_SNAPSHOT_CREATED) return

        val connectionId = event.getString("connectionId") ?: return
        val prevId = event.getString("prevId") // url 变化 = 旧连接的 UUID；全新建为 null
        val serviceUrl = event.getString("serviceUrl") ?: return

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
