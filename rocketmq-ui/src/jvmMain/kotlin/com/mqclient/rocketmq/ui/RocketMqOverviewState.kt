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

    /**
     * 读慢扫缓存。三分支显式覆盖全部状态（**绝不停留在 Idle** —— Idle 在
     * LoadStateContent 里渲染空白）：
     *  - 缓存有集群数据 → Success；
     *  - 没数据但慢扫跑完过一轮（扫描标记）→ Error + 真实原因（NameServer 层查询
     *    失败，取连接的 lastError）；
     *  - 还没跑过首扫 → **显式 Loading**（居中转圈，首扫完成经 OBJECTS_SCANNED
     *    事件回到这里翻页）。
     */
    fun refreshFromCache() {
        val service = RocketMqModule.serviceOrNull() ?: return
        val cluster = service.cachedClusterInfo(connectionId)
        if (cluster != null) {
            loadState = LoadState.Success(cluster)
            topicCount = service.cachedTopics(connectionId).size
            groupCount = service.cachedGroups(connectionId).size
        } else if (service.hasScannedObjects(connectionId)) {
            loadState = LoadState.Error(reason())
        } else {
            loadState = LoadState.Loading
        }
    }

    private fun reason(): String =
        RocketMqModule.connectionOrNull(connectionId)?.lastError
            ?.takeIf(String::isNotBlank)
            ?: stringResource("rocketmq_ui_connect_failed")
}
