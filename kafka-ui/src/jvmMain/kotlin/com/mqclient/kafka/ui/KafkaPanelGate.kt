package com.mqclient.kafka.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.kafka.KafkaModule

/**
 * Kafka 面板与连接状态的**共享观察层**：所有 Kafka 内容面板（Topic 列表、将来的
 * Consumer Group / Broker 等）都从这里读连接状态，而不是各自判断 —— 修一处，
 * 全部面板受益。
 *
 * 事实来源与侧边栏同源：[ConnectionStore.connections]（KafkaConnectionManager
 * 每次状态翻转都 reportStatus 上报），StateFlow 采集后在 Compose 里天然可观察，
 * 状态一变面板自动重组。`KafkaConnectionContext.status` 是 @Volatile 普通变量，
 * 不用于 UI 观察。
 */
@Composable
internal fun rememberKafkaConnectionStatus(connectionId: String): ConnectionStatus {
    val connections by ConnectionStore.connections.collectAsState()
    return connections.firstOrNull { it.id == connectionId }?.status
        ?: ConnectionStatus.Disconnected
}

/**
 * 隐式连接（面板侧兜底）：未连接 / 失败时发起连接，与宿主侧栏导航点击的隐式连接
 * （app-ui `ModuleSidebar`，发同一 `connectAsync` 入口）行为一致。导航点击已触发
 * 一次，这里覆盖的是面板内「重试」按钮等入口。
 */
internal fun ensureKafkaConnecting(connectionId: String, status: ConnectionStatus) {
    if (status == ConnectionStatus.Disconnected || status == ConnectionStatus.Failed) {
        KafkaModule.connectAsync(connectionId)
    }
}
