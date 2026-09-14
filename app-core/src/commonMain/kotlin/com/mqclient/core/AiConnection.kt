package com.mqclient.core

import kotlinx.serialization.Serializable

/**
 * 一条用户创建的 AI 连接（Provider + 具体配置）。
 *
 * 与 MQ 侧的 [ConnectionInfo] 同一思路，但只服务 AI 面板 / AI 适配器：
 *  - [providerId] 指向 ai-service 里 `com.mqclient.ai.AiProvider.id`
 *    （`openai-compatible` / `claude` / `gemini`），适配器据此分发；
 *  - [token] 字段名命中 ConfigManager 的敏感关键词（"token"）→ 落盘自动加密；
 *    [Sensitive] 标注把同一意图写进 schema；
 *  - 同一个 Provider 可以有多条连接（多个账号、多个 Ollama 实例），互不影响。
 *
 * 为什么放 commonMain：ai-service 的 commonMain（适配器接口）要引用它；
 * jvmMain 的 ConfigModel 再引用 commonMain 的类则是合法方向（jvmMain 可见 commonMain）。
 */
@Serializable
data class AiConnection(
    val id: String,
    /** 展示名（连接下拉里显示），如 "OpenAI 主账号"。 */
    val name: String,
    /** 所属 Provider 类型 id，见类注释。 */
    val providerId: String,
    /** 接口地址（Provider 预置默认值只是「新建时预填」）。 */
    val baseUrl: String = "",
    /** API key / token，落盘自动加密。 */
    @Sensitive
    val token: String = "",
    val model: String = "",
    /** 系统提示词（明文存储；空 = 用默认提示词）。 */
    val systemPrompt: String = "",
    /** 停用的连接不出现在面板的连接下拉里（设置界面仍然可见，方便改回来）。 */
    val enabled: Boolean = true,
)
