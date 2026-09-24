package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction

/**
 * 进程定义卡片操作（ProcessCardActions）：目前仅「属性」一项，与主题 / 订阅 / 服务页一致。
 * 操作由调用方经 [create] 注入回调，组件不持有任何状态。
 */
internal object ProcessCardActions {
    fun create(
        snapshot: MqObjectSnapshot,
        onOpenProperties: () -> Unit,
    ): List<CardAction> = listOf(
        CardAction(
            id = "process.properties",
            icon = Icons.Default.Info,
            tooltip = "process_definition_action_properties",
            onClick = onOpenProperties,
        ),
    )
}
