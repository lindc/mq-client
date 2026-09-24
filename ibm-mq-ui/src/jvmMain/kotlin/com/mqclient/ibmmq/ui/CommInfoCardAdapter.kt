package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsInputAntenna
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData

/**
 * 通信信息快照 → ObjectCardData 的适配（CommInfoCardAdapter），镜像 [MqObjectSnapshot.toServiceCardData]：
 * 通信信息无队列深度 / 运行状态这类强类型字段，故仅用统一的通信天线图标 + 描述作为次级文案，
 * 操作按钮由调用方经 [actions] 注入。
 */
internal fun MqObjectSnapshot.toCommInfoCardData(
    actions: List<CardAction> = emptyList(),
): ObjectCardData {
    return ObjectCardData(
        id = name,
        title = name,
        typeIcon = Icons.Default.SettingsInputAntenna,
        statusIcon = null,
        statusText = description,
        metricValue = null,
        actions = actions,
    )
}
