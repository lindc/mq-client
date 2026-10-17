package com.mqclient.rabbitmq.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.mqui.state.LoadState
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.uicommon.i18n.stringResource

/**
 * 概览页的状态（与 RabbitMqQueuesState 同一消费模式：缓存优先、事件驱动）：进页
 * 只读扫描缓存（queues / exchanges / queueStatus / clusterInfo），两类扫描事件任一
 * 完成都重读；「刷新」按钮走 [RabbitMqModule.scanNow]。
 *
 * **数据就绪判定**：`scannedOnce` 只在「有数据」时置位 —— 对象慢扫到，或快扫的
 * queueStatus 非空（快扫 runOnStart 先到，也能在 ~1s 内出卡片，Loading 窗口不被
 * 慢扫的 5s 起步延迟拖长）。Loading 阶段走 [LoadState.Loading] 全页占位 —— 健康
 * 度 Banner 拿不到数据就不会渲染，杜绝「全 0 数据显示健康」的误报。
 */
class RabbitMqOverviewState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<Unit>>(LoadState.Idle); private set

    /** 至少拿到过一轮数据（慢扫名单或快扫计数）：区分「还在等首扫」与「确认为空」。 */
    var scannedOnce by mutableStateOf(false); private set

    /**
     * 进 Tab 首次加载（组合期判定一次）：只有这里做隐式连接兜底。显式断开后的
     * 状态变化走 [onStatusChanged]，不重连。
     */
    suspend fun load() {
        loadState = LoadState.Loading
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        when (status) {
            ConnectionStatus.Connecting -> return
            ConnectionStatus.Disconnected -> {
                ensureRabbitMqConnecting(connectionId, status)
                return
            }
            ConnectionStatus.Failed -> {
                loadState = LoadState.Error(reason())
                return
            }
            ConnectionStatus.Connected -> Unit
        }
        refreshFromCache()
    }

    /** 连接状态变化的响应（不发起连接）。 */
    fun onStatusChanged(status: ConnectionStatus) {
        when (status) {
            ConnectionStatus.Connected -> {
                if (loadState !is LoadState.Success) loadState = LoadState.Loading
                refreshFromCache()
            }
            ConnectionStatus.Disconnected ->
                loadState = LoadState.Error(stringResource("rabbitmq_ui_connect_disconnected"))
            ConnectionStatus.Failed -> loadState = LoadState.Error(reason())
            ConnectionStatus.Connecting ->
                if (loadState !is LoadState.Success) loadState = LoadState.Loading
        }
    }

    private fun reason(): String =
        RabbitMqModule.connectionOrNull(connectionId)?.lastError
            ?.takeIf(String::isNotBlank)
            ?: stringResource("rabbitmq_ui_connect_failed")

    /** 对象慢扫完成：名单落地即数据就绪。 */
    fun onObjectsScanned() {
        scannedOnce = true
        refreshFromCache()
    }

    /** 状态快扫完成：queueStatus 有内容也算数据就绪（快扫 runOnStart 先到不被浪费）。 */
    fun onStatusScanned() {
        val status = RabbitMqModule.serviceOrNull()?.cachedQueueStatus(connectionId).orEmpty()
        if (status.isNotEmpty()) scannedOnce = true
        refreshFromCache()
    }

    /** 「刷新」按钮：触发一轮手动慢扫。 */
    fun requestScan() {
        RabbitMqModule.scanNow(connectionId)
    }

    private fun refreshFromCache() {
        val service = RabbitMqModule.serviceOrNull()
        val queues = service?.cachedQueues(connectionId).orEmpty()
        val exchanges = service?.cachedExchanges(connectionId).orEmpty()
        val queueStatus = service?.cachedQueueStatus(connectionId).orEmpty()
        val dataReady = queues.isNotEmpty() || exchanges.isNotEmpty() || queueStatus.isNotEmpty()
        if (dataReady) {
            loadState = LoadState.Success(Unit)
        } else if (scannedOnce) {
            // 扫过但 vhost 确实全空：合法空态（指标全 0 + 空队列 Banner）。
            loadState = LoadState.Success(Unit)
        }
        // 两者皆否 → 维持 Loading：健康度 Banner 不渲染，不会误报「健康」。
    }

    /** 「重试」：失败态重新发起连接。 */
    fun retry() {
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        ensureRabbitMqConnecting(connectionId, status)
    }
}
