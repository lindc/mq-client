package com.mqclient.ai.claude

import com.mqclient.core.AiProvider

/** Anthropic Claude — defaults to the latest Sonnet model. */
class ClaudeProvider : AiProvider {
    override val id = "claude"
    override val displayName = "Claude"
    override val defaultModel = "claude-3-5-sonnet-latest"
    override val defaultServerUrl = "https://api.anthropic.com"
}