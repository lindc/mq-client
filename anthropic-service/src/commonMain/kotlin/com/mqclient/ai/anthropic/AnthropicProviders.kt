package com.mqclient.ai.anthropic

import com.mqclient.ai.AiProvider

/**
 * Anthropic Provider 预置。
 *
 * 刻意不放 openai-service 的 [com.mqclient.ai.AiProviders]：那是 OpenAI 生态的
 * 预置清单，Anthropic 的品牌归本模块 —— 注册表只认 [AiProvider] 实例，归属模块
 * 自带预置即可（对齐「谁的品牌归谁的模块」的既定约定）。
 */
object AnthropicProviders {

    /** Anthropic Messages API 协议（Claude 系列）。 */
    val ANTHROPIC = AiProvider(
        id = "anthropic",
        name = "Anthropic",
        description = "Anthropic Messages API protocol — Claude model family",
        defaultBaseUrl = "https://api.anthropic.com",
        requiresToken = true,
        defaultModels = listOf(
            "claude-sonnet-4-5",
            "claude-opus-4-1",
            "claude-3-5-haiku-latest",
        ),
    )
}
