package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction

/**
 * 构造 IBM MQ 订阅卡片上的操作按钮。
 *
 * 当前仅 **属性** 一个按钮（位于尾位，与各对象类型的尾按钮位置一致）。
 * 订阅无「消息处理」语义，故不提供消息按钮。
 */
object SubscriptionCardActions {

    /**
     * 构造订阅卡片的操作按钮列表。
     *
     * @param snapshot 订阅快照。
     * @param onOpenProperties 打开订阅属性 Dialog 的回调。
     */
    fun create(
        snapshot: MqObjectSnapshot,
        onOpenProperties: () -> Unit,
    ): List<CardAction> = listOf(
        CardAction(
            id = "subscription.properties",
            icon = Icons.Default.Info,
            tooltip = "subscription_action_properties",
            onClick = onOpenProperties,
        ),
    )
}
