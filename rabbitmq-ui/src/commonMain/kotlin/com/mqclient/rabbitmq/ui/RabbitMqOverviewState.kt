package com.mqclient.rabbitmq.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.mqui.state.LoadState
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.uicommon.i18n.stringResource

/**
 * 概览页的状态：**Connected 即进入 Success**（页面立刻渲染 Dashboard 骨架，数据位
 * 先以 "-" 占位），数值随扫描事件经 [refreshTick] 驱动刷新 —— 连接状态分支与队列
 * 列表页同一消费模式（未连接隐式连接兜底、失败 Error + 重试、断开 Tab 由宿主收走）。
 *
 * **refreshTick 的必要性**：扫描事件回调里 loadState 的赋值是结构相等的
 * `Success(Unit)`，mutableStateOf 不会触发重组 —— 计数器让每次扫描事件必然带动
 * 面板重读缓存刷新数字。
 */
class RabbitMqOverviewState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<Unit>>(LoadState.Idle); private set

    /** 扫描事件计数：OBJECTS_SCANNED / STATUS_SCANNED 任一完成即 ++（面板据此重算汇总）。 */
    var refreshTick by mutableIntStateOf(0); private set

    /**
     * 进 Tab 首次加载（组合期判定一次）：**只有这里做隐式连接兜底**。显式断开后的
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
        loadState = LoadState.Success(Unit)
    }

    /** 连接状态变化的响应（**不发起连接**）。 */
    fun onStatusChanged(status: ConnectionStatus) {
        when (status) {
            ConnectionStatus.Connected -> loadState = LoadState.Success(Unit)
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

    /** OBJECTS_SCANNED 事件回调：++ 触发面板重算（慢扫名单变化 / 首次落地）。 */
    fun onObjectsScanned() {
        refreshTick++
    }

    /** STATUS_SCANNED 事件回调：++ 触发面板重算（计数 / badge 刷新）。 */
    fun onStatusScanned() {
        refreshTick++
    }

    /** 「刷新」按钮：触发一轮手动慢扫。 */
    fun requestScan() {
        RabbitMqModule.scanNow(connectionId)
    }

    /** 「重试」：失败态重新发起连接。 */
    fun retry() {
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        ensureRabbitMqConnecting(connectionId, status)
    }
}
