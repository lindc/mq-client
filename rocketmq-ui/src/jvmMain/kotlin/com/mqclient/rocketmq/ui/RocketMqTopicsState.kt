package com.mqclient.rocketmq.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.mqui.state.LoadState
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.RocketMqTopicSnapshot
import com.mqclient.uicommon.i18n.stringResource

/**
 * 主题列表页的状态：**缓存优先、事件驱动**（与 RabbitMqQueuesState 同一消费模式）：
 * 进页只读扫描缓存（cachedTopics），列表由 `rocketmq.scan.objects.completed` 事件
 * 驱动；「刷新」按钮调 [RocketMqModule.scanNow] 触发一轮手动慢扫。
 *
 * 连接状态分支：未连接自动隐式连接（Loading 等状态翻转）、失败显示原因 + 重试。
 */
class RocketMqTopicsState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<List<RocketMqTopicSnapshot>>>(LoadState.Idle); private set
    var search by mutableStateOf("")

    /** 至少完成过一轮对象扫描：区分「还在等首扫」与「扫完了但确实没有主题」。 */
    var scannedOnce by mutableStateOf(false); private set

    /** 搜索过滤后的列表（按名称包含匹配，忽略大小写）。 */
    val filtered: List<RocketMqTopicSnapshot>
        get() = when (val s = loadState) {
            is LoadState.Success -> s.data
                .filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
            else -> emptyList()
        }

    /** 进 Tab 首次加载：只有这里做隐式连接兜底。 */
    suspend fun load() {
        loadState = LoadState.Loading
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        when (status) {
            ConnectionStatus.Connecting -> return
            ConnectionStatus.Disconnected -> {
                ensureRocketMqConnecting(connectionId, status)
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
                loadState = LoadState.Error(stringResource("rocketmq_ui_connect_disconnected"))
            ConnectionStatus.Failed -> loadState = LoadState.Error(reason())
            ConnectionStatus.Connecting ->
                if (loadState !is LoadState.Success) loadState = LoadState.Loading
        }
    }

    private fun reason(): String =
        RocketMqModule.connectionOrNull(connectionId)?.lastError
            ?.takeIf(String::isNotBlank)
            ?: stringResource("rocketmq_ui_connect_failed")

    /** OBJECTS_SCANNED 事件回调：列表重建；空缓存不落地（保持加载态）。 */
    fun onObjectsScanned() {
        scannedOnce = true
        refreshFromCache()
    }

    /** 「刷新」按钮：触发一轮手动慢扫，扫完经 OBJECTS_SCANNED 事件回到这里。 */
    fun requestScan() {
        RocketMqModule.scanNow(connectionId)
    }

    private fun refreshFromCache() {
        val service = RocketMqModule.serviceOrNull()
        val topics: List<RocketMqTopicSnapshot> = service?.cachedTopics(connectionId) ?: emptyList()
        if (topics.isNotEmpty()) {
            loadState = LoadState.Success(topics)
        } else if (scannedOnce) {
            loadState = LoadState.Success(emptyList())
        }
    }

    /** 「重试」：失败态重新发起连接。 */
    fun retry() {
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        ensureRocketMqConnecting(connectionId, status)
    }
}
