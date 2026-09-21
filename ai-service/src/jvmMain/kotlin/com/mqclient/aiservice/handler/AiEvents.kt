package com.mqclient.aiservice.handler

/**
 * AI 模块的事件名常量（`ai.` 前缀）。
 *
 * 常量本体在 app-core 的 `EventNames`（快照事件的发出方 AiConnectionRepository
 * 在 core 里，依赖方向不允许反向引用 ai-panel）；这里只是 AI 模块侧的引用别名，
 * handler 订阅/过滤统一从这里拿，避免散落字符串。
 */
object AiEvents {
    val AI_CONNECTION_SNAPSHOT_CREATED = com.mqclient.core.event.EventNames.AI_CONNECTION_SNAPSHOT_CREATED
    val AI_CHAT_HISTORY_SAVE = com.mqclient.core.event.EventNames.AI_CHAT_HISTORY_SAVE
}
