package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.uicommon.i18n.stringResource

/**
 * 队列快照 → ObjectCardData 的适配（QueueCardAdapter）：
 * typeIcon 按队列类型解析（QueueIconResolver）；状态显示只有两种 —— 使用率
 * （currentDepth / maxDepth）达到告警阈值（[warningThreshold]%）时显示红色警告
 * 图标 + 文案，否则第 2 行左侧留空（maxDepth 未知——如心跳先于慢扫建快照——不算
 * 告警）；操作按钮由调用方经 [actions] 注入。
 */
internal fun MqObjectSnapshot.toQueueCardData(
    warningThreshold: Int,
    actions: List<CardAction> = emptyList(),
): ObjectCardData {
    // 判定与拓扑图共用一份（见 isQueueDepthWarning 的 KDoc：两份实现曾分叉）。
    val hasWarning = isQueueDepthWarning(this.currentDepth, this.maxDepth, warningThreshold)

    return ObjectCardData(
        id = name,
        title = name,
        typeIcon = QueueIconResolver.resolveIcon(this),
        statusIcon = if (hasWarning) Icons.Default.Warning else null,
        statusIconTint = if (hasWarning) Color(0xFFF44336) else null,
        statusText = if (hasWarning) stringResource("ibm_mq_queue_status_warning") else null,
        metricValue = (messageCount ?: 0).toString(),
        actions = actions,
    )
}
