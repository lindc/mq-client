package com.mqclient.openai.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import mq_client.openai_ui.generated.resources.Res
import mq_client.openai_ui.generated.resources.ic_openai
import org.jetbrains.compose.resources.painterResource

/**
 * openai-service 的品牌资源与界面件。
 *
 * 品牌素材放这里而不是 app-ui 的 `ModuleIcons`：`*-service` 模块没有 Compose 插件、
 * 携带不了 composeResources，而 `*-ui` 模块可以 —— 谁的品牌归谁的 *-ui 模块管。
 *
 * 目前只有一个资源：连接创建 / 编辑卡片上展示的 OpenAI logo。
 */
@Composable
fun openAiIcon(): Painter = painterResource(Res.drawable.ic_openai)
