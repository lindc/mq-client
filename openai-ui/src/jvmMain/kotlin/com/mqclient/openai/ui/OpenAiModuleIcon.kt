package com.mqclient.openai.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.mqclient.core.ModuleIcon

/**
 * openai 的品牌图标注册：drawable 与渲染函数本来就在本模块（OpenAiBranding），
 * 这里只是把它挂到 [ModuleIcon] SPI 上，让新建连接卡片等调用方按 serviceId 查到。
 */
class OpenAiModuleIcon : ModuleIcon {
    override val serviceId = "openai-compatible"

    @Composable
    override fun painter(): Painter = openAiIcon()
}
