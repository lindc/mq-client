package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction

/**
 * 构造 IBM MQ 服务卡片上的操作按钮。
 *
 * 当前仅 **属性** 一个按钮（位于尾位，与各对象类型的尾按钮位置一致）。
 */
object ServiceCardActions {

    /**
     * 构造服务卡片的操作按钮列表。
     *
     * @param snapshot 服务快照。
     * @param onOpenProperties 打开服务属性 Dialog 的回调。
     */
    fun create(
        snapshot: MqObjectSnapshot,
        onOpenProperties: () -> Unit,
    ): List<CardAction> = listOf(
        CardAction(
            id = "service.properties",
            icon = Icons.Default.Info,
            tooltip = "service_action_properties",
            onClick = onOpenProperties,
        ),
    )
}
