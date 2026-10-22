package com.mqclient.ai

/** 一次非流式对话的完整结果。 */
data class AiResponse(
    /** 助手回复的全文。 */
    val content: String,
    /** 服务端实际使用的模型 id（响应里没有时回退到请求里的模型）。 */
    val model: String,
    /** 总 token 数；部分服务 / 部分响应不提供时为 null。 */
    val tokensUsed: Int? = null,
    /** 结束原因（`stop` / `end_turn` / `STOP` …），没有则为 null。 */
    val finishReason: String? = null,
)
