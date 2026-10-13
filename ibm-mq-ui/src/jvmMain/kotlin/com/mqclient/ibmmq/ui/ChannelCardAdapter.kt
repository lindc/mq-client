package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.uicommon.i18n.stringResource

/** 非运行态的中性灰（已停止 / 未运行 / 启动中等都属正常状态，不当作错误告警）。 */
private val CHANNEL_STATUS_NEUTRAL = Color(0xFF9E9E9E)

/**
 * 通道快照 → ObjectCardData 的适配（ChannelCardAdapter），镜像 [MqObjectSnapshot.toQueueCardData]：
 * typeIcon 按通道类型解析（[ChannelIconResolver]）；状态分三档 ——
 *  - `RUNNING`：绿色对勾 + 文案；
 *  - 已知状态（含 `STOPPED` / `RETRYING` / `STARTING`… 以及「已定义但无状态行」的「未运行」）：
 *    中性灰圆点 + 文案，不当作错误告警；
 *  - 真正未知 / 待加载（`channelStatus == null` 且连 `channelType` 都还没有，即快扫最小快照
 *    尚未补全）：红色警告图标 + 「未知状态」，提示信息缺失而非通道故障。
 * 操作按钮由调用方经 [actions] 注入；[executingTarget] 非空（"RUNNING" / "STOPPED"）
 * 表示该通道的启停操作正在执行（命令已发、轮询未完成），状态文案改显「启动中 / 停止中」，
 * 不再显示旧状态或已翻面的真实状态（IBM MQ 命令异步生效，真实值要等轮询结束才可靠）。
 */
internal fun MqObjectSnapshot.toChannelCardData(
    actions: List<CardAction> = emptyList(),
    executingActionIds: Set<String> = emptySet(),
    executingTarget: String? = null,
): ObjectCardData {
    val status = channelStatus
    val isRunning = status == "RUNNING"
    // 「已定义但无状态行」：慢扫见过通道定义（channelType 非空）却没拿到运行时状态，
    // 典型如停止中的发送通道——属正常，显示中性「未运行」，不当作红色告警。
    val isDefinedInactive = channelType != null && status == null
    // 真正待加载 / 未知：连通道类型都尚未补全的最小快照，信息缺失，标红提示。
    val isUnknown = status == null && !isDefinedInactive
    return ObjectCardData(
        id = name,
        title = name,
        typeIcon = ChannelIconResolver.resolveIcon(this),
        typeLabel = ChannelIconResolver.resolveLabel(this),
        statusIcon = when {
            // 启停执行中：图标与「启动中/停止中」文案一致，用中性点，不显示旧状态的绿勾/红叉。
            executingTarget != null -> Icons.Default.Circle
            isRunning -> Icons.Default.CheckCircle
            isUnknown -> Icons.Default.Warning
            else -> Icons.Default.Circle
        },
        statusIconTint = when {
            executingTarget != null -> CHANNEL_STATUS_NEUTRAL
            isRunning -> Color(0xFF4CAF50)
            isUnknown -> Color(0xFFF44336)
            else -> CHANNEL_STATUS_NEUTRAL
        },
        statusText = when {
            executingTarget == "RUNNING" -> stringResource("ibm_mq_channel_status_starting")
            executingTarget == "STOPPED" -> stringResource("ibm_mq_channel_status_stopping")
            status != null -> channelStatusText(status)
            isDefinedInactive -> stringResource("ibm_mq_channel_status_inactive")
            else -> stringResource("ibm_mq_channel_status_unknown")
        },
        metricValue = null,
        actions = actions,
        executingActionIds = executingActionIds,
    )
}

/** 通道运行状态 → i18n 文案（未覆盖的状态值回退到原始英文状态码，不丢信息）。 */
internal fun channelStatusText(status: String): String {
    val key = when (status) {
        "RUNNING" -> "ibm_mq_channel_status_running"
        "RETRYING" -> "ibm_mq_channel_status_retrying"
        "STOPPED" -> "ibm_mq_channel_status_stopped"
        "BINDING" -> "ibm_mq_channel_status_binding"
        "INITIALIZING" -> "ibm_mq_channel_status_initializing"
        "STARTING" -> "ibm_mq_channel_status_starting"
        "STOPPING" -> "ibm_mq_channel_status_stopping"
        "PAUSED" -> "ibm_mq_channel_status_paused"
        "REQUESTING" -> "ibm_mq_channel_status_requesting"
        "INACTIVE" -> "ibm_mq_channel_status_inactive"
        else -> null
    }
    return key?.let { stringResource(it) } ?: status
}
