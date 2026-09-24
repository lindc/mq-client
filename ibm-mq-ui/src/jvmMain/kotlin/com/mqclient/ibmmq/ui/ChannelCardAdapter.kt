package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.uicommon.i18n.stringResource

/**
 * 通道快照 → ObjectCardData 的适配（ChannelCardAdapter），镜像 [MqObjectSnapshot.toQueueCardData]：
 * typeIcon 按通道类型解析（[ChannelIconResolver]）；状态显示只有两种 —— 通道处于 `RUNNING`
 * 时显示绿色对勾 + 状态文案，否则显示红色警告图标 + 状态文案（RETRYING / STOPPED / BINDING…）。
 * 操作按钮由调用方经 [actions] 注入。
 */
internal fun MqObjectSnapshot.toChannelCardData(
    actions: List<CardAction> = emptyList(),
): ObjectCardData {
    val status = channelStatus
    val isRunning = status == "RUNNING"
    return ObjectCardData(
        id = name,
        title = name,
        typeIcon = ChannelIconResolver.resolveIcon(this),
        statusIcon = if (isRunning) Icons.Default.CheckCircle else Icons.Default.Warning,
        statusIconTint = if (isRunning) Color(0xFF4CAF50) else Color(0xFFF44336),
        statusText = status?.let { channelStatusText(it) } ?: stringResource("channel_status_unknown"),
        metricValue = null,
        actions = actions,
    )
}

/** 通道运行状态 → i18n 文案（未知状态回退到原始英文状态码，不丢信息）。 */
internal fun channelStatusText(status: String): String {
    val key = when (status) {
        "RUNNING" -> "channel_status_running"
        "RETRYING" -> "channel_status_retrying"
        "STOPPED" -> "channel_status_stopped"
        "BINDING" -> "channel_status_binding"
        "INITIALIZING" -> "channel_status_initializing"
        else -> null
    }
    return key?.let { stringResource(it) } ?: status
}
