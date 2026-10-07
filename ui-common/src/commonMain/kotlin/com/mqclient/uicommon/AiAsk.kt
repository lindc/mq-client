package com.mqclient.uicommon

import androidx.compose.runtime.compositionLocalOf

/**
 * 「询问 AI」请求：业务模块（如 ibm-mq-ui 的属性对话框 / 对象卡片）发起 AI 链路分析时
 * 传给宿主（app-ui）的中立 DTO —— 不含任何模块类型，宿主拿它合成 AI 面板的上下文。
 *
 * [contextJson] 是发起方组装好的链路上下文 JSON（currentTime / chain / userQuestion
 * 结构，IBM MQ = AiChainBuilder 的产物），发起时点构建（只读缓存，不碰 MQ）。
 */
data class AiAskRequest(
    val connectionId: String,
    /** 对象类型标识（模块自定义词汇，IBM MQ = MqObjectType.name，如 QUEUE / CHANNEL / LISTENER）。 */
    val objectType: String,
    /** 目标对象名。 */
    val objectName: String,
    /** 面板「正在分析」chip 的展示文本。 */
    val targetLabel: String,
    /** 链路上下文 JSON 原文。 */
    val contextJson: String,
)

/**
 * 「询问 AI」宿主回调：业务模块用 `LocalAiAsk.current` 取到后调 [onAsk]，
 * 宿主（app-ui）负责打开 AI 面板并注入上下文。
 */
fun interface AiAskStarter {
    fun onAsk(request: AiAskRequest)
}

/**
 * 应用级「询问 AI」入口：在 [app-ui] 的 [Main] 里 Provide，任意深层业务 Composable
 * （属性对话框 footer / 对象卡片按钮）用 `LocalAiAsk.current?.onAsk(...)` 发起分析。
 *
 * 取用时若外层没 Provide 得到 null —— 模块侧应容忍（隐藏按钮或忽略点击），因为
 * 宿主可能变（独立预览等）。
 */
val LocalAiAsk = compositionLocalOf<AiAskStarter?> { null }
