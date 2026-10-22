package com.mqclient.aiservice

import com.mqclient.aiservice.db.AiChatDatabase
import com.mqclient.aiservice.db.AiConnectionSnapshotRepository
import com.mqclient.aiservice.db.ChatHistoryRepository
import com.mqclient.aiservice.handler.AiChatHistoryHandler
import com.mqclient.aiservice.handler.AiConnectionSnapshotHandler
import com.mqclient.core.ConfigStore
import com.mqclient.core.CoreModule
import com.mqclient.core.database.DatabaseManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * AI 落库基础设施的唯一启动入口：建表 → 启动清理 → 订阅两个落库事件。
 *
 * 由 app-ui 的 `bootstrapEvents()` 在 `DatabaseManager.initialize` 之后调用
 * （模块没有自己的生命周期钩子，宿主的启动序列就是它的初始化点）。
 *
 * 订阅挂在**宿主传入的 app 级 scope** 上（不是组合的 LaunchedEffect scope）：
 * 落库是进程级后台职责，不能跟随某个组合节点离开而退订。
 */
object AiChatPersistence {

    private const val SOURCE = "ai-panel"

    /**
     * 建表（CREATE TABLE IF NOT EXISTS，幂等）→ 按保留天数清一次旧对话 →
     * 注册两个事件订阅。返回订阅 Job（宿主目前不需要持有，取消走进程退出）。
     *
     * 保留天数取启动时的 `general.historyRetentionDays`；运行中改设置下次启动生效
     * （审计数据不需要即时性）。
     */
    suspend fun register(scope: CoroutineScope): List<Job> {
        // DatabaseManager 未初始化（如某些测试环境）就不启动落库，也不订阅 ——
        // 与其让 handler 每条都报错，不如整块缺席。
        // 不用 runCatching：它会把 CancellationException 也当成失败吞掉。
        try {
            DatabaseManager.withDriver { AiChatDatabase.Schema.create(it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            CoreModule.reportError(message = "AI 数据库初始化失败，对话不落库", source = SOURCE, error = error)
            return emptyList()
        }
        try {
            pruneByRetention()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            CoreModule.reportError(message = "AI 对话清理失败", source = SOURCE, error = error)
        }

        val snapshotHandler = AiConnectionSnapshotHandler(AiConnectionSnapshotRepository)
        val chatHandler = AiChatHistoryHandler(ChatHistoryRepository)

        // on() 自带收集协程、默认挂在总线构造时的 app 级 scope 上 —— 正是这里想要的
        // 生命周期，不需要再包一层 launch。
        return listOf(
            CoreModule.eventBus.on(AiEvents.AI_CONNECTION_SNAPSHOT_CREATED) { event ->
                snapshotHandler.handle(event)
            },
            CoreModule.eventBus.on(AiEvents.AI_CHAT_HISTORY_SAVE) { event ->
                chatHandler.handle(event)
            },
        )
    }

    /** 按「保留 N 天」清理：N 来自配置的 historyRetentionDays（设置 → 通用）。 */
    private suspend fun pruneByRetention() {
        val retentionDays = ConfigStore.loadConfig().general.historyRetentionDays
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        ChatHistoryRepository.cleanOldBefore(cutoff)
    }
}
