package com.mqclient.rabbitmq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.rabbitmq.RabbitMqModule

/** 当前连接状态的组合期读取（侧边栏状态翻转驱动面板重建，与 KafkaPanelGate 同款）。 */
@Composable
internal fun rememberRabbitMqConnectionStatus(connectionId: String): ConnectionStatus {
    val connections by ConnectionStore.connections.collectAsState()
    return connections.firstOrNull { it.id == connectionId }?.status
        ?: ConnectionStatus.Disconnected
}

/**
 * 隐式连接（面板侧兜底）：未连接 / 失败时发起连接，与宿主侧栏导航点击的隐式连接
 * （app-ui `ModuleSidebar`，发同一 `connectAsync` 入口）行为一致。导航点击已触发
 * 一次，这里覆盖的是面板内「重试」按钮等入口。
 */
internal fun ensureRabbitMqConnecting(connectionId: String, status: ConnectionStatus) {
    if (status == ConnectionStatus.Disconnected || status == ConnectionStatus.Failed) {
        RabbitMqModule.connectAsync(connectionId)
    }
}
