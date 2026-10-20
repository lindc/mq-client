package com.mqclient.rocketmq.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.mqui.state.LoadState
import com.mqclient.rocketmq.RocketMqGroupLagSnapshot
import com.mqclient.rocketmq.RocketMqGroupSnapshot
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.uicommon.i18n.stringResource

/** 一条消费组的展示信息：慢扫成员数 + 快扫 Lag 合并（未扫到字段显示 "-"）。 */
data class RocketMqGroupCardInfo(
    val name: String,
    val memberCount: Int?,
    val totalLag: Long?,
    val queueCount: Int?,
)

/**
 * 消费组列表页的状态：缓存优先、事件驱动（与主题列表页同一消费模式）。静态数据
 * 由 `rocketmq.scan.objects.completed` 驱动，Lag 由 `rocketmq.scan.lag.completed`
 * （快扫）驱动；「刷新」触发手动慢扫。
 */
class RocketMqGroupsState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<List<RocketMqGroupCardInfo>>>(LoadState.Idle); private set
    var search by mutableStateOf("")
    var scannedOnce by mutableStateOf(false); private set

    val filtered: List<RocketMqGroupCardInfo>
        get() = when (val s = loadState) {
            is LoadState.Success -> s.data
                .filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
            else -> emptyList()
        }

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

    fun onObjectsScanned() {
        scannedOnce = true
        refreshFromCache()
    }

    /** LAG_SCANNED 事件回调：只刷 Lag（列表本体不动，避免闪烁）。 */
    fun onLagScanned() {
        refreshFromCache(keepList = true)
    }

    fun requestScan() {
        RocketMqModule.scanNow(connectionId)
    }

    /** 慢扫 groups + 快扫 groupLags 按组名合并。 */
    private fun refreshFromCache(keepList: Boolean = false) {
        val service = RocketMqModule.serviceOrNull()
        val groups: List<RocketMqGroupSnapshot> = service?.cachedGroups(connectionId) ?: emptyList()
        if (groups.isNotEmpty()) {
            val lags = service?.cachedGroupLags(connectionId) ?: emptyMap()
            loadState = LoadState.Success(
                groups.map { group ->
                    val lag = lags[group.name]
                    RocketMqGroupCardInfo(
                        name = group.name,
                        memberCount = group.memberCount,
                        totalLag = lag?.totalLag,
                        queueCount = lag?.queueCount,
                    )
                },
            )
        } else if (scannedOnce && !keepList) {
            loadState = LoadState.Success(emptyList())
        }
    }

    fun retry() {
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        ensureRocketMqConnecting(connectionId, status)
    }
}
