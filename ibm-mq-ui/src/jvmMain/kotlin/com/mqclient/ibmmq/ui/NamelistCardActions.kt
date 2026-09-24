package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction

/**
 * 名称列表卡片操作（NamelistCardActions）：目前仅「属性」一项，与主题 / 订阅 / 服务 /
 * 进程定义页一致。操作由调用方经 [create] 注入回调，组件不持有任何状态。
 */
internal object NamelistCardActions {
    fun create(
        snapshot: MqObjectSnapshot,
        onOpenProperties: () -> Unit,
    ): List<CardAction> = listOf(
        CardAction(
            id = "namelist.properties",
            icon = Icons.Default.Info,
            tooltip = "namelist_action_properties",
            onClick = onOpenProperties,
        ),
    )
}
