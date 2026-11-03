package com.mqclient.rabbitmq.ui.topology

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.mqui.state.LoadState
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.RabbitMqQueueStatusSnapshot
import com.mqclient.rabbitmq.ui.ensureRabbitMqConnecting
import com.mqclient.uicommon.i18n.stringResource

/**
 * 拓扑页的状态：**缓存优先、事件驱动**（与 RabbitMqQueuesState / KafkaTopologyState
 * 同一消费模式）：进页只读扫描缓存，内容由 `rabbitmq.scan.objects.completed`（静态
 * 属性重建）与 `rabbitmq.scan.status.completed`（badge 消息数刷新）两类事件驱动；
 * 「重新扫描」按钮经 [RabbitMqModule.scanNow] 触发一轮手动慢扫。
 *
 * 连接状态分支语义与列表页一致：未连接自动隐式连接、失败展示原因。加载态保持到
 * **首个非空缓存**；扫完确实为空才落空快照（空 vhost → 面板显示「无对象」文案）。
 */
class RabbitMqTopologyState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<RabbitMqTopologySnapshot>>(LoadState.Idle); private set

    /** 至少完成过一轮对象扫描：区分「还在等首扫」与「扫完了但确实没有对象」。 */
    var scannedOnce by mutableStateOf(false); private set

    /**
     * 进 Tab 首次加载（组合期判定一次）：**只有这里做隐式连接兜底**。显式断开后的
     * 状态变化走 [onStatusChanged]，不重连。
     */
    suspend fun load() {
        val hasSnapshot = loadState is LoadState.Success
        if (!hasSnapshot) loadState = LoadState.Loading
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        when (status) {
            ConnectionStatus.Connecting -> return // 等状态翻转后由 onStatusChanged 接管
            ConnectionStatus.Disconnected -> {
                ensureRabbitMqConnecting(connectionId, status)
                return
            }
            ConnectionStatus.Failed -> {
                if (!hasSnapshot) loadState = LoadState.Error(reason())
                return
            }
            ConnectionStatus.Connected -> Unit
        }
        refreshFromCache()
    }

    /** 连接状态变化的响应（**不发起连接**）：已有快照时失败 / 断开保留旧图。 */
    fun onStatusChanged(status: ConnectionStatus) {
        val hasSnapshot = loadState is LoadState.Success
        when (status) {
            ConnectionStatus.Connected -> {
                if (!hasSnapshot) loadState = LoadState.Loading
                refreshFromCache()
            }
            ConnectionStatus.Disconnected ->
                loadState = LoadState.Error(stringResource("rabbitmq_ui_connect_disconnected"))
            ConnectionStatus.Failed ->
                if (!hasSnapshot) loadState = LoadState.Error(reason())
            ConnectionStatus.Connecting ->
                if (!hasSnapshot) loadState = LoadState.Loading
        }
    }

    private fun reason(): String =
        RabbitMqModule.connectionOrNull(connectionId)?.lastError
            ?.takeIf(String::isNotBlank)
            ?: stringResource("rabbitmq_ui_connect_failed")

    /** OBJECTS_SCANNED 事件回调：读缓存重建；空结果不覆盖旧快照（刷新不闪屏）。 */
    fun onObjectsScanned() {
        scannedOnce = true
        refreshFromCache()
    }

    /** STATUS_SCANNED 事件回调：只刷 badge 计数（图结构不动，避免闪烁）。 */
    fun onStatusScanned() {
        refreshFromCache()
    }

    /** 读扫描缓存投影成拓扑快照；空缓存不落地 —— 保持加载态或保留旧快照。 */
    private fun refreshFromCache() {
        val service = RabbitMqModule.serviceOrNull()
        val exchanges = service?.cachedExchanges(connectionId).orEmpty()
        val queues = service?.cachedQueues(connectionId).orEmpty()
        val bindings = service?.cachedBindings(connectionId).orEmpty()
        val status: Map<String, RabbitMqQueueStatusSnapshot> =
            service?.cachedQueueStatus(connectionId).orEmpty()
        if (exchanges.isNotEmpty() || queues.isNotEmpty()) {
            loadState = LoadState.Success(
                RabbitMqTopologySnapshot(
                    exchanges = exchanges,
                    queues = queues,
                    bindings = bindings,
                    queueStatus = status,
                ),
            )
        } else if (scannedOnce) {
            loadState = LoadState.Success(
                RabbitMqTopologySnapshot(emptyList(), emptyList(), emptyList(), emptyMap()),
            )
        }
    }

    /** 「重试」：失败态重新发起连接。 */
    fun retry() {
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        ensureRabbitMqConnecting(connectionId, status)
    }
}
