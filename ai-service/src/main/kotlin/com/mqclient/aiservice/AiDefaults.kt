package com.mqclient.aiservice

/**
 * AI 域的默认值 —— 目前只有默认系统提示词。
 *
 * 从 app-core 的 `ConfigModel` 搬来：它是 AI 对话的行为默认值（连接自己填了
 * systemPrompt 时以连接的为准），只有 AI 侧用得上，所以跟着域走。
 * 面向全球发布的默认值用英文；用户可在连接设置里填自己的提示词。
 */
const val DEFAULT_SYSTEM_PROMPT: String =
    "You are a message-queue administration assistant. Help users troubleshoot MQ problems, " +
        "explain concepts, and generate safe operational commands. " +
        "Answer concisely, and give executable steps when needed."
