package com.mqclient.anthropic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import mq_client.anthropic_ui.generated.resources.Res
import mq_client.anthropic_ui.generated.resources.ic_anthropic
import org.jetbrains.compose.resources.painterResource

/**
 * Anthropic 的品牌资源与界面件。
 *
 * 品牌素材放这里而不是 app-ui 的 `ModuleIcons`：`*-service` 模块没有 Compose 插件、
 * 携带不了 composeResources，而 `*-ui` 模块可以 —— 谁的品牌归谁的 `*-ui` 模块管
 * （openai-ui 的 OpenAiBranding 先例）。
 *
 * 目前只有一个资源：连接创建 / 编辑卡片上展示的 Anthropic logo。
 */
@Composable
fun anthropicIcon(): Painter = painterResource(Res.drawable.ic_anthropic)
