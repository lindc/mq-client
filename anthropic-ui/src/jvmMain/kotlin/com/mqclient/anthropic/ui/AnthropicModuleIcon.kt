package com.mqclient.anthropic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.mqclient.ai.anthropic.AnthropicProviders
import com.mqclient.core.ModuleIcon

/**
 * Anthropic 的品牌图标注册：drawable 与渲染函数本来就在本模块（AnthropicBranding），
 * 这里只是把它挂到 [ModuleIcon] SPI 上，让新建连接卡片等调用方按 serviceId 查到。
 */
class AnthropicModuleIcon : ModuleIcon {
    override val serviceId = AnthropicProviders.ANTHROPIC.id

    @Composable
    override fun painter(): Painter = anthropicIcon()
}
