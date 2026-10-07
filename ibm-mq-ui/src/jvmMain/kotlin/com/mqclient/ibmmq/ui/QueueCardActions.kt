package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.ui.ai.askAiCardAction
import com.mqclient.mqui.objectlist.CardAction

/**
 * 构造 IBM MQ 队列卡片上的操作按钮。
 *
 * 按钮数量由队列类型决定：
 *  - 普通本地队列（QLOCAL 且非传输用途）：**消息 + AI（可选）+ 属性** ——
 *    只有本地队列能收发消息，传输 / 远程 / 别名 / 模型队列没有「消息处理」语义；
 *  - 其他队列（传输 / QREMOTE / QALIAS / QMODEL / 未识别）：只有**属性**按钮。
 *
 * 类型判定复用 [QueueIconResolver.resolveType]（图标与按钮同源，不会出现
 * 「图标是本地队列、按钮却是消息按钮」的分裂）。
 */
object QueueCardActions {

    /**
     * 构造队列卡片的操作按钮列表。
     *
     * @param snapshot 队列快照（类型 / 用途从 attributes 解析）。
     * @param onOpenProperties 打开队列属性 Dialog 的回调。
     * @param onOpenMessages 打开消息处理 Dialog 的回调（仅普通本地队列的卡片有此按钮）。
     * @param onAskAi 发起「询问 AI」的回调（仅普通本地队列 + 宿主提供 AI 入口时有此按钮；
     *                 null = 宿主未提供，按钮不显示）。
     */
    fun create(
        snapshot: MqObjectSnapshot,
        onOpenProperties: () -> Unit,
        onOpenMessages: () -> Unit,
        onAskAi: (() -> Unit)? = null,
    ): List<CardAction> {
        val actions = mutableListOf<CardAction>()
        // 消息按钮（仅普通本地队列有）放前面，属性按钮恒在最后 —— 各对象类型的
        // 尾按钮保持一致（选中后同位置操作）。
        if (QueueIconResolver.resolveType(snapshot) == QueueType.LOCAL) {
            actions += CardAction(
                id = "queue.messages",
                icon = Icons.Default.Email,
                tooltip = "queue_action_messages",
                onClick = onOpenMessages,
            )
            onAskAi?.let { actions += askAiCardAction(it) }
        }
        actions += CardAction(
            id = "queue.properties",
            icon = Icons.Default.Info,
            tooltip = "queue_action_properties",
            onClick = onOpenProperties,
        )
        return actions
    }
}
