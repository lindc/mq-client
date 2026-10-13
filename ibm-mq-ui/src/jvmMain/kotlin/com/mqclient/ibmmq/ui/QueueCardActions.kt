package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction

/**
 * 构造 IBM MQ 队列卡片上的操作按钮。
 *
 * 按钮数量由队列类型决定：
 *  - 普通本地队列（QLOCAL 且非传输用途）：**消息 + 属性** ——
 *    只有本地队列能收发消息，传输 / 远程 / 别名 / 模型队列没有「消息处理」语义；
 *  - 其他队列（传输 / QREMOTE / QALIAS / QMODEL / 未识别）：只有**属性**按钮。
 *
 * 「询问 AI」按钮不在这里：它在 QueueListPanel 的 actions 拼接层排到**图标区第一个
 * 位置**（在拓扑按钮之前），显示规则见 ai 包的 [com.mqclient.ibmmq.ui.ai.isCardAiTarget]。
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
     */
    fun create(
        snapshot: MqObjectSnapshot,
        onOpenProperties: () -> Unit,
        onOpenMessages: () -> Unit,
    ): List<CardAction> {
        val actions = mutableListOf<CardAction>()
        // 消息按钮（仅普通本地队列有）放前面，属性按钮恒在最后 —— 各对象类型的
        // 尾按钮保持一致（选中后同位置操作）。
        if (QueueIconResolver.resolveType(snapshot) == QueueType.LOCAL) {
            actions += CardAction(
                id = "queue.messages",
                icon = Icons.Default.Email,
                tooltip = "ibm_mq_queue_action_messages",
                onClick = onOpenMessages,
            )
        }
        actions += CardAction(
            id = "queue.properties",
            icon = Icons.Default.Info,
            tooltip = "ibm_mq_queue_action_properties",
            onClick = onOpenProperties,
        )
        return actions
    }
}
