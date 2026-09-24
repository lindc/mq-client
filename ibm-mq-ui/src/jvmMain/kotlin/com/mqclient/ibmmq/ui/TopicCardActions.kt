package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction

/**
 * 构造 IBM MQ 主题卡片上的操作按钮。
 *
 * 当前仅 **属性** 一个按钮（位于尾位，与各对象类型的尾按钮位置一致）。
 * 主题无「消息处理」语义，故不提供消息按钮。
 */
object TopicCardActions {

    /**
     * 构造主题卡片的操作按钮列表。
     *
     * @param snapshot 主题快照。
     * @param onOpenProperties 打开主题属性 Dialog 的回调。
     */
    fun create(
        snapshot: MqObjectSnapshot,
        onOpenProperties: () -> Unit,
    ): List<CardAction> = listOf(
        CardAction(
            id = "topic.properties",
            icon = Icons.Default.Info,
            tooltip = "topic_action_properties",
            onClick = onOpenProperties,
        ),
    )
}
