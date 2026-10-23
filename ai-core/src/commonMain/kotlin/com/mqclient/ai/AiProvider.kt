package com.mqclient.ai

/**
 * 一个 AI 服务提供者类型（Provider）的描述。
 *
 * Provider 只回答「这一类 API 长什么样」；用户真正使用的是
 * [com.mqclient.core.AiConnection]（Provider + 地址 / token / 模型），
 * 同一个 Provider 可以有多条连接（多个账号、多个 Ollama 实例……）。
 *
 * 具体 provider 的预置常量（如 `AiProviders.OPENAI_COMPATIBLE`）由**各自的服务模块**
 * 定义，不放在这里 —— ai-core 只提供契约，不认识任何具体协议。
 */
data class AiProvider(
    /** 稳定 id（如 `openai-compatible`）；连接通过 providerId 找到适配器。 */
    val id: String,
    /** 展示名（下拉框用）。 */
    val name: String,
    /** 一句话说明（设置界面展示）。 */
    val description: String = "",
    /** 新建连接时预填的接口地址。 */
    val defaultBaseUrl: String = "",
    /** 是否通常需要 token（本地 Ollama 不需要；只作界面提示，不作硬校验）。 */
    val requiresToken: Boolean = true,
    /** 模型下拉候选；空列表 = 只让用户手填。 */
    val defaultModels: List<String> = emptyList(),
)
