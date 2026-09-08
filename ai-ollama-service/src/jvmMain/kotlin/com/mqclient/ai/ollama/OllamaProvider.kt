package com.mqclient.ai.ollama

import com.mqclient.core.AiProvider

/** Local Ollama daemon — assumes the default port 11434. */
class OllamaProvider : AiProvider {
    override val id = "ollama"
    override val displayName = "Ollama (本地)"
    override val defaultModel = "llama3.1"
    override val defaultServerUrl = "http://localhost:11434"
}