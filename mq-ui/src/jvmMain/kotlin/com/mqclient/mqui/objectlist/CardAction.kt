package com.mqclient.mqui.objectlist

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 对象卡片上的操作按钮（纯数据描述）。
 *
 * 由各 MQ 模块构造——mq-ui 只负责渲染，不定义任何具体操作（属性 / 消息处理 /
 * 复制名称……都是模块侧的语义）。
 *
 * [tooltip] 是 i18n key（stringResource），点击回调由调用方提供；
 * 危险操作（[isDangerous]）渲染成 error 色图标。
 */
data class CardAction(
    /** 唯一标识（调试 / 测试用）。 */
    val id: String,
    /** 按钮图标。 */
    val icon: ImageVector,
    /** 悬停提示的 i18n key；同时作为图标的 contentDescription（无障碍）。 */
    val tooltip: String,
    /** 是否可用；不可用降透明。 */
    val enabled: Boolean = true,
    /** 危险操作（如删除）：图标用 error 色。 */
    val isDangerous: Boolean = false,
    /** 点击回调。 */
    val onClick: () -> Unit = {},
)
