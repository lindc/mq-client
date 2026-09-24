package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction

/**
 * 构造 IBM MQ 通道卡片上的操作按钮。
 *
 * 当前仅 **属性** 一个按钮（位于尾位，与队列卡片的尾按钮位置一致）。
 * 通道无「消息处理」语义，故不提供消息按钮。后续若需要 START / STOP / RESET
 * 等通道运维动作，在此按类型条件追加即可（与 [ChannelIconResolver.resolveType] 同源）。
 */
object ChannelCardActions {

    /**
     * 构造通道卡片的操作按钮列表。
     *
     * @param snapshot 通道快照。
     * @param onOpenProperties 打开通道属性 Dialog 的回调。
     */
    fun create(
        snapshot: MqObjectSnapshot,
        onOpenProperties: () -> Unit,
    ): List<CardAction> = listOf(
        CardAction(
            id = "channel.properties",
            icon = Icons.Default.Info,
            tooltip = "channel_action_properties",
            onClick = onOpenProperties,
        ),
    )
}
