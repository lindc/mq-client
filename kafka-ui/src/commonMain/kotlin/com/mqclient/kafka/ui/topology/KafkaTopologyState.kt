package com.mqclient.kafka.ui.topology

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.KafkaBrokerSnapshot
import com.mqclient.kafka.KafkaTopicSnapshot
import com.mqclient.kafka.ui.ensureKafkaConnecting
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.i18n.stringResource

/** 拓扑链路日志 tag（state / builder / panel 共用）。 */
internal const val TOPOLOGY_LOG_TAG = "kafka.topology"

/** 一个 broker 的拓扑信息（扫描缓存投影，纯数据）。 */
data class KafkaBrokerInfo(
    val id: Int,
    val host: String,
    val port: Int,
)

/** 一个分区的拓扑信息：leader / 副本 / ISR 的 broker id。 */
data class KafkaPartitionInfo(
    val id: Int,
    /** leader broker id；分区无 leader（离线）时为 null。 */
    val leaderId: Int?,
    val replicaIds: List<Int>,
    val isrIds: List<Int>,
)

/** 一个 topic 的拓扑信息：名称 + 全部分区明细（拓扑图展开分区的数据源）。 */
data class KafkaTopicTopologyInfo(
    val name: String,
    val internal: Boolean,
    val partitions: List<KafkaPartitionInfo>,
)

/** 一次缓存读取的完整快照（纯数据，拓扑图构建吃它）。 */
data class KafkaTopologySnapshot(
    val brokers: List<KafkaBrokerInfo>,
    val topics: List<KafkaTopicTopologyInfo>,
    /** 当前 controller 的 broker id（broker 详情对话框的 controller 徽标用）。 */
    val controllerId: Int? = null,
) {
    val isEmpty: Boolean get() = brokers.isEmpty() && topics.isEmpty()
}

/**
 * 拓扑页的状态：**缓存优先、事件驱动** —— 进页只读扫描缓存（[KafkaModule.serviceOrNull]
 * 的 cachedBrokers / cachedTopics），内容由 `kafka.scan.objects.completed` 事件驱动重建；
 * 「重新扫描」按钮经 [KafkaModule.scanNow] 触发一轮手动慢扫。与 ibm-mq 的拓扑面板同一
 * 消费模式（进页不直查，扫描由连接生命周期驱动）。
 *
 * 连接状态分支语义与列表页一致：未连接自动隐式连接（Loading 等状态翻转 + 首轮扫描）、
 * 失败展示原因。加载态保持到**首个非空缓存**；刷新空结果不覆盖旧图（重扫语义：
 * 成功才换数据）。连接断开时缓存已被清空 —— 缓存读出空且扫过一轮即真实空态。
 */
class KafkaTopologyState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<KafkaTopologySnapshot>>(LoadState.Idle); private set

    /** 至少完成过一轮对象扫描：区分「还在等首扫」与「扫完了但确实没有对象」。 */
    var scannedOnce by mutableStateOf(false); private set

    /**
     * 进 Tab 首次加载（组合期判定一次）：**只有这里做隐式连接兜底** —— 用户主动进入
     * 页面才有「自动连接」的意图。显式断开后的状态变化走 [onStatusChanged]，不重连。
     */
    suspend fun load() {
        val hasSnapshot = loadState is LoadState.Success
        if (!hasSnapshot) loadState = LoadState.Loading
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        when (status) {
            ConnectionStatus.Connecting -> return // 等状态翻转后由 onStatusChanged 接管
            ConnectionStatus.Disconnected -> {
                ensureKafkaConnecting(connectionId, status)
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

    /**
     * 连接状态变化的响应（**不发起连接**）：Connected → 读缓存；Disconnected →
     * 「已断开」态；Failed → 错误态（已有快照则保留旧图）；Connecting → 保持加载。
     */
    fun onStatusChanged(status: ConnectionStatus) {
        val hasSnapshot = loadState is LoadState.Success
        when (status) {
            ConnectionStatus.Connected -> {
                if (!hasSnapshot) loadState = LoadState.Loading
                refreshFromCache()
            }
            ConnectionStatus.Disconnected ->
                loadState = LoadState.Error(stringResource("kafka_ui_connect_disconnected"))
            ConnectionStatus.Failed ->
                if (!hasSnapshot) loadState = LoadState.Error(reason())
            ConnectionStatus.Connecting ->
                if (!hasSnapshot) loadState = LoadState.Loading
        }
    }

    private fun reason(): String =
        KafkaModule.connectionOrNull(connectionId)?.lastError
            ?.takeIf(String::isNotBlank)
            ?: stringResource("kafka_ui_connect_failed")

    /** OBJECTS_SCANNED 事件回调：读缓存重建；空结果不覆盖旧快照（刷新不闪屏）。 */
    fun onObjectsScanned() {
        scannedOnce = true
        refreshFromCache()
    }

    /** 读扫描缓存投影成拓扑快照；空缓存不落地 —— 保持加载态或保留旧快照。 */
    private fun refreshFromCache() {
        val service = KafkaModule.serviceOrNull()
        val brokers = service?.cachedBrokers(connectionId).orEmpty()
        val topics = service?.cachedTopics(connectionId).orEmpty()
        if (brokers.isNotEmpty() || topics.isNotEmpty()) {
            loadState = LoadState.Success(
                KafkaTopologySnapshot(
                    brokers = brokers.map(KafkaBrokerSnapshot::toBrokerInfo),
                    topics = topics.map(KafkaTopicSnapshot::toTopologyInfo),
                    controllerId = service?.cachedCluster(connectionId)?.controllerId,
                ),
            )
        } else if (scannedOnce) {
            loadState = LoadState.Success(
                KafkaTopologySnapshot(emptyList(), emptyList(), controllerId = null),
            )
        }
    }

    /** 「重试」：失败态重新发起连接。 */
    fun retry() {
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        ensureKafkaConnecting(connectionId, status)
    }
}

/** 服务层快照 → 拓扑 UI 模型（1:1 投影）。 */
private fun KafkaBrokerSnapshot.toBrokerInfo() =
    KafkaBrokerInfo(id = brokerId, host = host, port = port)

private fun KafkaTopicSnapshot.toTopologyInfo() =
    KafkaTopicTopologyInfo(
        name = name,
        internal = internal,
        partitions = partitions
            .map { KafkaPartitionInfo(id = it.id, leaderId = it.leaderId, replicaIds = it.replicaIds, isrIds = it.isrIds) },
    )
