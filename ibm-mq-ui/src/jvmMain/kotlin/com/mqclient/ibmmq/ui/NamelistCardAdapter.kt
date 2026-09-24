package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData

/**
 * 名称列表快照 → ObjectCardData 的适配（NamelistCardAdapter），镜像 [MqObjectSnapshot.toServiceCardData]：
 * 名称列表无队列深度 / 运行状态这类强类型字段，故仅用统一的列表图标 + 描述作为次级文案，
 * 操作按钮由调用方经 [actions] 注入。
 */
internal fun MqObjectSnapshot.toNamelistCardData(
    actions: List<CardAction> = emptyList(),
): ObjectCardData {
    return ObjectCardData(
        id = name,
        title = name,
        typeIcon = Icons.Default.List,
        statusIcon = null,
        statusText = description,
        metricValue = null,
        actions = actions,
    )
}
