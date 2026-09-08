package com.mqclient.ai.openai

import com.mqclient.core.AiProvider

/** OpenAI Chat Completions endpoint. */
class OpenAiProvider : AiProvider {
    override val id = "openai"
    override val displayName = "OpenAI"
    override val defaultModel = "gpt-4o"
    override val defaultServerUrl = "https://api.openai.com/v1"
    override val models = listOf(
        "gpt-4o",
        "gpt-4o-mini",
        "gpt-4-turbo",
        "gpt-3.5-turbo",
    )
}