package com.mqclient.ai

import com.mqclient.core.AiConnection
import kotlinx.coroutines.flow.Flow

/**
 * 一个 Provider 的适配器：把统一的 [AiChatMessage] 翻成该服务的 wire 格式并收发。
 *
 * 实现约定：
 *  - 网络 / 协议错误统一抛 [AiException]（带 statusCode 与服务端说明）；
 *  - [chat] 非流式、[chatStream] 流式（SSE，[Flow] 收集结束即流结束）；
 *  - [testConnection] 是「设置界面的测试按钮」：发一个最小请求验证配置，
 *    失败返回 false（原因写日志），**不抛异常**。
 */
interface AiAdapter {

    /** 对应 [AiProvider.id]（如 `openai-compatible`）。 */
    val providerId: String

    /** 非流式对话：一次拿完整回复。 */
    suspend fun chat(connection: AiConnection, messages: List<AiChatMessage>): AiResponse

    /** 流式对话：SSE 分片以 [Flow] 逐片产出。 */
    fun chatStream(connection: AiConnection, messages: List<AiChatMessage>): Flow<AiStreamChunk>

    /** 发一个简单请求验证连接配置；失败返回 false（不抛）。 */
    suspend fun testConnection(connection: AiConnection): Boolean
}
