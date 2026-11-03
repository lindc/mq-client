package com.mqclient.openai.ui

import com.mqclient.ai.AiProviders
import com.mqclient.aiui.AiProviderConnectionForm

/**
 * OpenAI 兼容服务的「新建连接」表单（`ConnectionFormUi` SPI 实现）。
 *
 * 表单本体在 `ai-ui` 的 [AiProviderConnectionForm]（两个 provider 的字段与落盘路径
 * 完全一致，此前是两份 466 行的逐字副本）。这里只声明本 provider 的 id 与预选：
 * 进入表单时预选 OpenAI 兼容、baseUrl / 模型候选取它的预置。
 *
 * Provider 下拉保留全量列表 —— 用户在任何一个 AI 表单里都可以切换 provider 类型。
 * 注册见 `META-INF/services/com.mqclient.core.ConnectionFormUi`。
 */
class OpenAiConnectionForm : AiProviderConnectionForm(AiProviders.OPENAI_COMPATIBLE.id) {

    override val serviceId: String = AiProviders.OPENAI_COMPATIBLE.id
}
