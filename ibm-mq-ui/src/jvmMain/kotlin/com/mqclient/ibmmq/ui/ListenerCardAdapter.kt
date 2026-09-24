package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.uicommon.i18n.stringResource

/**
 * 监听器快照 → ObjectCardData 的适配（ListenerCardAdapter），镜像 [MqObjectSnapshot.toChannelCardData]：
 * typeIcon 用监听器图标；状态显示两种 —— `RUNNING` 时显示绿色对勾 + 状态文案，
 * 否则显示红色警告图标 + 状态文案（STOPPED…）。端口作为右侧数值展示。
 * 操作按钮由调用方经 [actions] 注入。
 */
internal fun MqObjectSnapshot.toListenerCardData(
    actions: List<CardAction> = emptyList(),
): ObjectCardData {
    val status = listenerStatus
    val isRunning = status == "RUNNING"
    return ObjectCardData(
        id = name,
        title = name,
        typeIcon = Icons.Default.Hearing,
        statusIcon = if (isRunning) Icons.Default.CheckCircle else Icons.Default.Warning,
        statusIconTint = if (isRunning) Color(0xFF4CAF50) else Color(0xFFF44336),
        statusText = status?.let { listenerStatusText(it) } ?: stringResource("listener_status_unknown"),
        metricValue = port?.toString(),
        actions = actions,
    )
}

/** 监听器运行状态 → i18n 文案（未知状态回退到原始英文状态码，不丢信息）。 */
internal fun listenerStatusText(status: String): String {
    val key = when (status) {
        "RUNNING" -> "listener_status_running"
        "STOPPED" -> "listener_status_stopped"
        else -> null
    }
    return key?.let { stringResource(it) } ?: status
}
