package com.mqclient.ai.openai

import com.mqclient.core.AiProvider

/** OpenAI Chat Completions endpoint. */
class OpenAiProvider : AiProvider {
    override val id = "openai"
    override val displayName = "OpenAI"
    override val defaultModel = "gpt-4o"
    override val defaultServerUrl = "https://api.openai.com/v1"
}