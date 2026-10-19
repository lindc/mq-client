package com.mqclient.rabbitmq.ui

import androidx.compose.ui.graphics.Color

/**
 * RabbitMQ 模块统一状态色板：概览健康度 Banner / 集群节点、队列卡片状态点、拓扑
 * 严重级共用同一来源（改一处全局生效）。取概览页原色值（Material Green 800 /
 * Orange 700 / Red 700，浅色表面上对比度足够）。
 */
internal val RabbitMqRunningGreen = Color(0xFF2E7D32)
internal val RabbitMqWarningOrange = Color(0xFFED6C02)
internal val RabbitMqDangerRed = Color(0xFFD32F2F)

/**
 * 队列运行状态 → 色点颜色（全模块同一映射，与拓扑 queueSeverity 同口径）：
 * running 绿；flow / down / crashed 红（flow = 内存/磁盘告警发布被阻塞，与 down
 * 同级危险）；idle / 未知 / null（快扫未到该队列）→ null，调用方落主题次要灰。
 */
internal fun rabbitMqQueueStateColor(state: String?): Color? = when (state) {
    "running" -> RabbitMqRunningGreen
    "flow", "down", "crashed" -> RabbitMqDangerRed
    else -> null
}
