package com.mqclient.aiservice

/**
 * AI 域的默认值 —— 目前只有默认系统提示词。
 *
 * 从 app-core 的 `ConfigModel` 搬来：它是 AI 对话的行为默认值（连接自己填了
 * systemPrompt 时以连接的为准），只有 AI 侧用得上，所以跟着域走。
 * 它也是用户在设置界面里看得见的文本，中英混排无所谓。
 */
const val DEFAULT_SYSTEM_PROMPT: String =
    "你是一个 MQ 管理助手,帮助用户排查消息队列问题、解释概念并生成安全的运维命令。" +
        "回答简洁,中文输出,必要时给出可执行的步骤。"
