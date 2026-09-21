package com.mqclient.ai

/**
 * 一条发给模型的对话消息（wire 格式，只有角色和内容）。
 *
 * 刻意不叫 `AiMessage`：ai-panel 的聊天记录已经叫
 * [com.mqclient.aiui.AiMessage]（带 id / 时间戳 / 评价），两者在 ChatService
 * 桥接处会同时出现在一个文件里，同名会让 import 打架。
 */
data class AiChatMessage(
    val role: AiRole,
    val content: String,
)

/** 消息角色。 */
enum class AiRole {
    SYSTEM,
    USER,
    ASSISTANT,
}
