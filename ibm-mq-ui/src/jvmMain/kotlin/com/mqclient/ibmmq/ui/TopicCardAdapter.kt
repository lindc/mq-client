package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData

/**
 * 主题快照 → ObjectCardData 的适配（TopicCardAdapter），镜像 [MqObjectSnapshot.toChannelCardData]：
 * 主题无队列深度 / 运行状态这类强类型字段，故仅用统一的主题图标 + 描述作为次级文案，
 * 操作按钮由调用方经 [actions] 注入。主题类型（本地 / 集群 / 别名）差异暂不在卡片上区分。
 */
internal fun MqObjectSnapshot.toTopicCardData(
    actions: List<CardAction> = emptyList(),
): ObjectCardData {
    return ObjectCardData(
        id = name,
        title = name,
        typeIcon = Icons.Default.Forum,
        statusIcon = null,
        statusText = description,
        metricValue = null,
        actions = actions,
    )
}
