package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction

/**
 * 认证信息卡片操作（AuthInfoCardActions）：目前仅「属性」一项，与主题 / 订阅 / 服务 /
 * 进程定义 / 名称列表页一致。操作由调用方经 [create] 注入回调，组件不持有任何状态。
 */
internal object AuthInfoCardActions {
    fun create(
        snapshot: MqObjectSnapshot,
        onOpenProperties: () -> Unit,
    ): List<CardAction> = listOf(
        CardAction(
            id = "authInfo.properties",
            icon = Icons.Default.Info,
            tooltip = "auth_info_action_properties",
            onClick = onOpenProperties,
        ),
    )
}
