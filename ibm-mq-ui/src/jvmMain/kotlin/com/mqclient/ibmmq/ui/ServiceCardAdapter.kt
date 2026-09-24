package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData

/**
 * 服务快照 → ObjectCardData 的适配（ServiceCardAdapter），镜像 [MqObjectSnapshot.toTopicCardData]：
 * 服务无队列深度 / 运行状态这类强类型字段，故仅用统一的服务图标 + 描述作为次级文案，
 * 操作按钮由调用方经 [actions] 注入。
 */
internal fun MqObjectSnapshot.toServiceCardData(
    actions: List<CardAction> = emptyList(),
): ObjectCardData {
    return ObjectCardData(
        id = name,
        title = name,
        typeIcon = Icons.Default.Settings,
        statusIcon = null,
        statusText = description,
        metricValue = null,
        actions = actions,
    )
}
