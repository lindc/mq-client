package com.mqclient.aiui

import com.mqclient.uicommon.i18n.stringResource
import kotlinx.serialization.json.put

/**
 * 「询问 AI」（MQ 对象链路分析）系统提示词的 i18n 键：文案在 ui-common 的
 * strings_zh-CN / strings_en-US 表里，随应用语言设置走（LanguageResources 按当前
 * 语言整表加载）。
 */
const val KEY_CHAIN_ANALYSIS_SYSTEM_PROMPT = "ai_chain_analysis_system_prompt"

/**
 * 语言表缺键时的兜底（stringResource 缺键会原样返回 key，不能把 key 发给模型）——
 * 与中文表文案保持一致。
 */
private const val FALLBACK_CHAIN_ANALYSIS_PROMPT =
    "你是一个 IBM MQ 运维助手。以下是当前 MQ 对象链路的上下文信息，以 JSON 格式提供。\n\n" +
        "chain 中的每个节点代表一个 MQ 对象，isTarget 为 true 的是用户当前关注的对象。\n\n" +
        "relationToNext 描述节点之间的消息流向关系。\n\n" +
        "请基于以下数据回答用户的问题：\n" +
        "- 重点关注 isTarget 为 true 的对象\n" +
        "- 结合上下游节点的状态判断问题原因\n" +
        "- 如果证据不足，说明需要补充哪些信息\n" +
        "- 不要编造数据中没有的信息\n" +
        "- 回答要具体，定位到具体对象和字段"

/**
 * 解析当前语言的链路分析系统提示词。在**发送时点**调用（[AiPanelState.sendUserMessage]
 * 内）：语言是启动 / 切换时整表加载的（LanguageResources），发送前刚换语言的话本条
 * 消息就用新语言的提示词 —— 与界面文案同源、同刷新节奏。
 */
fun chainAnalysisSystemPrompt(): String {
    val resolved = stringResource(KEY_CHAIN_ANALYSIS_SYSTEM_PROMPT)
    return if (resolved != KEY_CHAIN_ANALYSIS_SYSTEM_PROMPT) resolved else FALLBACK_CHAIN_ANALYSIS_PROMPT
}

private val CHAIN_TIME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * 把发起时点构建的 chain 数组 JSON（[chainArrayJson]）包装成发给模型的完整上下文：
 * `currentTime` + `chain` + `userQuestion`（结构契约见 ai-service 的 chat_history.sq
 * 对 context_json 列的注释）。
 * currentTime 取发送时刻（比发起更接近真实提问时间）；chain JSON 解析失败时降级为
 * 空数组 —— 问题本身仍然发出，不让上下文构建失败挡住对话。
 */
fun buildChainContextJson(chainArrayJson: String, userQuestion: String): String {
    val chainElement = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(chainArrayJson) }
        .getOrDefault(kotlinx.serialization.json.buildJsonArray { })
    return kotlinx.serialization.json.buildJsonObject {
        put("currentTime", java.time.LocalDateTime.now().format(CHAIN_TIME_FORMAT))
        put("chain", chainElement)
        put("userQuestion", userQuestion)
    }.toString()
}
