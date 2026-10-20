package com.mqclient.rocketmq.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.mqui.state.LoadState
import com.mqclient.rocketmq.RocketMqClusterInfoSnapshot
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.uicommon.i18n.stringResource

/**
 * 集群概览页的状态：只读慢扫缓存（clusterInfo / topics / groups 计数），事件驱动
 * 刷新。没有隐式连接兜底 —— 概览页挂在已连接 Tab 下（宿主保证）。
 */
class RocketMqOverviewState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<RocketMqClusterInfoSnapshot>>(LoadState.Idle); private set
    var topicCount by mutableStateOf(0); private set
    var groupCount by mutableStateOf(0); private set

    /** OBJECTS_SCANNED 事件回调：重读缓存。 */
    fun onObjectsScanned() {
        refreshFromCache()
    }

    fun refreshFromCache() {
        val service = RocketMqModule.serviceOrNull() ?: return
        val cluster = service.cachedClusterInfo(connectionId)
        if (cluster != null) {
            loadState = LoadState.Success(cluster)
            topicCount = service.cachedTopics(connectionId).size
            groupCount = service.cachedGroups(connectionId).size
        } else {
            loadState = LoadState.Error(stringResource("rocketmq_ui_connect_failed"))
        }
    }
}
