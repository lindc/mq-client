package com.mqclient.anthropic.ui

import com.mqclient.ai.anthropic.AnthropicProviders
import com.mqclient.aiui.AiProviderConnectionForm

/**
 * Anthropic 服务的「新建连接」表单（`ConnectionFormUi` SPI 实现）。
 *
 * 表单本体在 `ai-ui` 的 [AiProviderConnectionForm]（两个 provider 的字段与落盘路径
 * 完全一致，此前是两份 466 行的逐字副本）。这里只声明本 provider 的 id 与预选：
 * 进入表单时预选 Anthropic、baseUrl / 模型候选取它的预置。
 *
 * 与 OpenAI 表单的唯一差别就是这个预选 —— 此前为了复用 `ModelComboBox`，
 * 本模块还额外 `implementation(projects.openaiUi)`（两个平级 provider 的横向依赖，
 * 会让「删掉一个 provider 不影响其余」不成立）。控件已随表单一并下沉到 `ai-ui`，
 * 那个依赖已删除。
 */
class AnthropicConnectionForm : AiProviderConnectionForm(AnthropicProviders.ANTHROPIC.id) {

    override val serviceId: String = AnthropicProviders.ANTHROPIC.id
}
