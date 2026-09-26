package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import com.mqclient.core.log.Log
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.uicommon.i18n.stringResource

/** 非运行态的中性灰（未启动 / 未运行 / 启动中 / 停止中 都属正常状态，不当作错误告警）。 */
private val LISTENER_STATUS_NEUTRAL = Color(0xFF9E9E9E)

private val log = Log.withTag("ibm-mq-ui")

/**
 * 监听器快照 → ObjectCardData 的适配（ListenerCardAdapter），镜像 [MqObjectSnapshot.toChannelCardData]：
 * typeIcon 用监听器图标；状态分三档 ——
 *  - `RUNNING`：绿色对勾 + 文案；
 *  - 已知非运行（`NOT_RUNNING` / `STARTING` / `STOPPING`）：中性灰圆点 + 文案，不当作错误；
 *  - 真正未知（`UNKNOWN`，即扫描拿到了状态值但应用不认识）：红色警告 + 「未知」；
 *  - 无状态行（[MqObjectSnapshot.listenerStatus] 为 null，典型如定义了但未启动的默认
 *    监听器，INQUIRE_LISTENER_STATUS 不返回它的行）：中性灰圆点 + 「未启动」，比「未知状态」
 *    更贴近事实（不是数据缺失，而是没起）。端口作为右侧数值展示。
 * 操作按钮由调用方经 [actions] 注入；[executingTarget] 非空（"RUNNING" / "NOT_RUNNING"）
 * 表示该监听器的启停操作正在执行（命令已发、轮询未完成），状态文案改显「启动中 / 停止中」，
 * 不再显示旧状态或已翻面的真实状态（IBM MQ 命令异步生效，真实值要等轮询结束才可靠）。
 */
internal fun MqObjectSnapshot.toListenerCardData(
    actions: List<CardAction> = emptyList(),
    executingActionIds: Set<String> = emptySet(),
    executingTarget: String? = null,
): ObjectCardData {
    val status = listenerStatus
    val isRunning = status == "RUNNING"
    val isUnknown = status == "UNKNOWN"
    return ObjectCardData(
        id = name,
        title = name,
        typeIcon = Icons.Default.Hearing,
        statusIcon = when {
            // 启停执行中：图标与「启动中/停止中」文案一致，用中性点，不显示旧状态的绿勾。
            executingTarget != null -> Icons.Default.Circle
            isRunning -> Icons.Default.CheckCircle
            status == null -> Icons.Default.Circle
            isUnknown -> Icons.Default.Warning
            else -> Icons.Default.Circle
        },
        statusIconTint = when {
            executingTarget != null -> LISTENER_STATUS_NEUTRAL
            isRunning -> Color(0xFF4CAF50)
            status == null -> LISTENER_STATUS_NEUTRAL
            isUnknown -> Color(0xFFF44336)
            else -> LISTENER_STATUS_NEUTRAL
        },
        statusText = when {
            executingTarget == "RUNNING" -> stringResource("listener_status_starting")
            executingTarget == "NOT_RUNNING" -> stringResource("listener_status_stopping")
            status == null -> stringResource("listener_status_not_started")
            else -> listenerStatusText(status)
        },
        metricValue = port?.toString(),
        actions = actions,
        executingActionIds = executingActionIds,
    )
}

/** 监听器运行状态 → i18n 文案（未覆盖的状态值回退到「未知」，不丢信息）。 */
internal fun listenerStatusText(status: String): String {
    val key = when (status) {
        "RUNNING" -> "listener_status_running"
        "NOT_RUNNING" -> "listener_status_not_running"
        "STARTING" -> "listener_status_starting"
        "STOPPING" -> "listener_status_stopping"
        "STOPPED" -> "listener_status_stopped"
        else -> null
    }
    return key?.let { stringResource(it) } ?: stringResource("listener_status_unknown")
}
