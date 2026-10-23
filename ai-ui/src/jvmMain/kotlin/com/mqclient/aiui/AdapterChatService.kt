package com.mqclient.aiui

import com.mqclient.ai.AiAdapterRegistry
import com.mqclient.ai.AiChatMessage
import com.mqclient.ai.AiException
import com.mqclient.ai.AiRole
import com.mqclient.aiservice.DEFAULT_SYSTEM_PROMPT
import com.mqclient.core.AiConnection
import kotlinx.coroutines.flow.collect

/**
 * 真实的后端实现：把面板的聊天记录桥接到 ai-core 的适配器注册表
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
        systemPromptOverride: String?,
        onChunk: (String) -> Unit,
    ): AiStreamResult {
        val adapter = AiAdapterRegistry.adapter(connection.providerId)
            ?: throw AiException("没有 providerId=「${connection.providerId}」对应的适配器（连接「${connection.name}」）")

        // 系统提示词 = 连接自己的（没填回退默认）+「询问 AI」分析提示词（非空时追加）。
        val systemPrompt = buildString {
            append(connection.systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT })
            if (!systemPromptOverride.isNullOrBlank()) {
                append("\n\n")
                append(systemPromptOverride)
            }
        }
        val messages = buildList {
            add(AiChatMessage(AiRole.SYSTEM, systemPrompt))
            history.forEach { add(AiChatMessage(it.role.toAiRole(), it.content)) }
        }

        // finishReason 在收尾帧、usage 在末尾的统计帧，都不走 onChunk —— 在这里
        // 顺手捕获，随返回值交给调用方落库。
        var finishReason: String? = null
        var usage = AiStreamResult()
        adapter.chatStream(connection, messages).collect { chunk ->
            if (chunk.delta.isNotEmpty()) onChunk(chunk.delta)
            if (chunk.finishReason != null) finishReason = chunk.finishReason
            usage = usage.copy(
                promptTokens = chunk.promptTokens ?: usage.promptTokens,
                completionTokens = chunk.completionTokens ?: usage.completionTokens,
                totalTokens = chunk.totalTokens ?: usage.totalTokens,
            )
        }
        return usage.copy(finishReason = finishReason)
    }

    private fun Role.toAiRole(): AiRole = when (this) {
        Role.USER -> AiRole.USER
        Role.ASSISTANT -> AiRole.ASSISTANT
    }
}
