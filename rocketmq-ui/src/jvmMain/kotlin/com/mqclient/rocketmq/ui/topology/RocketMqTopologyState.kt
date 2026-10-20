package com.mqclient.rocketmq.ui.topology

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.mqui.state.LoadState
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.RocketMqTopicSnapshot
import com.mqclient.rocketmq.ui.ensureRocketMqConnecting
import com.mqclient.uicommon.i18n.stringResource

/** 拓扑链路日志 tag（state / builder / panel 共用）。 */
internal const val TOPOLOGY_LOG_TAG = "rocketmq.topology"

/** 一个 broker 的拓扑信息（集群信息快照投影，纯数据）。 */
data class RocketMqBrokerInfo(
    val name: String,
    val address: String,
)

/** 一个 topic 的拓扑信息：名称 + 按 broker 的路由明细（拓扑图边的数据源）。 */
data class RocketMqTopicTopologyInfo(
    val name: String,
    val system: Boolean,
    val routes: List<RocketMqRouteInfo>,
)

/** 一条 topic → broker 路由明细。 */
data class RocketMqRouteInfo(
    val brokerName: String,
    val readQueueNums: Int,
    val writeQueueNums: Int,
    val perm: Int,
)

/** 一次缓存读取的完整快照（纯数据，拓扑图构建吃它）。 */
data class RocketMqTopologySnapshot(
    val brokers: List<RocketMqBrokerInfo>,
    val topics: List<RocketMqTopicTopologyInfo>,
) {
    val isEmpty: Boolean get() = brokers.isEmpty() && topics.isEmpty()
}

/**
 * 拓扑页的状态：**缓存优先、事件驱动** —— 进页只读扫描缓存（[RocketMqModule.serviceOrNull]
 * 的 cachedTopics / cachedClusterInfo），内容由 `rocketmq.scan.objects.completed` 事件
 * 驱动重建；「重新扫描」按钮经 [RocketMqModule.scanNow] 触发一轮手动慢扫。
 *
 * 连接状态分支语义与列表页一致：未连接自动隐式连接（Loading 等状态翻转 + 首轮扫描）、
 * 失败展示原因。加载态保持到**首个非空缓存**；刷新空结果不覆盖旧图（重扫语义：
 * 成功才换数据）。连接断开时缓存已被清空 —— 缓存读出空且扫过一轮即真实空态。
 */
class RocketMqTopologyState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<RocketMqTopologySnapshot>>(LoadState.Idle); private set

    /** 至少完成过一轮对象扫描：区分「还在等首扫」与「扫完了但确实没有对象」。 */
    var scannedOnce by mutableStateOf(false); private set

    /** 进 Tab 首次加载（组合期判定一次）：**只有这里做隐式连接兜底**。 */
    suspend fun load() {
        val hasSnapshot = loadState is LoadState.Success
        if (!hasSnapshot) loadState = LoadState.Loading
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        when (status) {
            ConnectionStatus.Connecting -> return // 等状态翻转后由 onStatusChanged 接管
            ConnectionStatus.Disconnected -> {
                ensureRocketMqConnecting(connectionId, status)
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
                loadState = LoadState.Error(stringResource("rocketmq_ui_connect_disconnected"))
            ConnectionStatus.Failed ->
                if (!hasSnapshot) loadState = LoadState.Error(reason())
            ConnectionStatus.Connecting ->
                if (!hasSnapshot) loadState = LoadState.Loading
        }
    }

    private fun reason(): String =
        RocketMqModule.connectionOrNull(connectionId)?.lastError
            ?.takeIf(String::isNotBlank)
            ?: stringResource("rocketmq_ui_connect_failed")

    /** OBJECTS_SCANNED 事件回调：读缓存重建；空结果不覆盖旧快照（刷新不闪屏）。 */
    fun onObjectsScanned() {
        scannedOnce = true
        refreshFromCache()
    }

    /** 读扫描缓存投影成拓扑快照；空缓存不落地 —— 保持加载态或保留旧快照。 */
    private fun refreshFromCache() {
        val service = RocketMqModule.serviceOrNull() ?: return
        val topics = service.cachedTopics(connectionId)
        val cluster = service.cachedClusterInfo(connectionId)
        if (topics.isNotEmpty() || (cluster?.brokers?.isNotEmpty() == true)) {
            loadState = LoadState.Success(
                RocketMqTopologySnapshot(
                    brokers = cluster?.brokers?.map {
                        RocketMqBrokerInfo(name = it.brokerName, address = it.address)
                    } ?: emptyList(),
                    topics = topics.map { topic ->
                        RocketMqTopicTopologyInfo(
                            name = topic.name,
                            system = isSystemTopic(topic.name),
                            routes = topic.routes.map {
                                RocketMqRouteInfo(
                                    brokerName = it.brokerName,
                                    readQueueNums = it.readQueueNums,
                                    writeQueueNums = it.writeQueueNums,
                                    perm = it.perm,
                                )
                            },
                        )
                    },
                ),
            )
        }
    }

    /** 「重新扫描」按钮：触发一轮手动慢扫，扫完经 OBJECTS_SCANNED 事件回到这里。 */
    fun requestScan() {
        RocketMqModule.scanNow(connectionId)
    }
}
