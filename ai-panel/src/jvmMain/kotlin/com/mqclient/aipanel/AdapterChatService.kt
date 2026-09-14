package com.mqclient.aipanel

import com.mqclient.ai.AiAdapterRegistry
import com.mqclient.ai.AiChatMessage
import com.mqclient.ai.AiException
import com.mqclient.ai.AiRole
import com.mqclient.core.AiConnection
import com.mqclient.core.DEFAULT_SYSTEM_PROMPT
import kotlinx.coroutines.flow.collect

/**
 * 真实的后端实现：把面板的聊天记录桥接到 openai-service 模块的适配器
 * （[AiAdapterRegistry] 按 providerId 分发 + SSE 流式）。
 *
 * 系统提示词取连接自己的；没填就回退共享的 [DEFAULT_SYSTEM_PROMPT]。
 * 面板的 [AiContext] 前缀已由 [AiPanelState.sendUserMessage] 拼进消息内容，这里直接透传。
 */
class AdapterChatService : ChatService {

    override suspend fun streamReply(
        history: List<AiMessage>,
        connection: AiConnection,
        context: AiContext?,
        onChunk: (String) -> Unit,
    ) {
        val adapter = AiAdapterRegistry.adapter(connection.providerId)
            ?: throw AiException("没有 providerId=「${connection.providerId}」对应的适配器（连接「${connection.name}」）")

        val messages = buildList {
            add(AiChatMessage(AiRole.SYSTEM, connection.systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }))
            history.forEach { add(AiChatMessage(it.role.toAiRole(), it.content)) }
        }

        adapter.chatStream(connection, messages).collect { chunk ->
            if (chunk.delta.isNotEmpty()) onChunk(chunk.delta)
        }
    }

    private fun Role.toAiRole(): AiRole = when (this) {
        Role.USER -> AiRole.USER
        Role.ASSISTANT -> AiRole.ASSISTANT
    }
}
