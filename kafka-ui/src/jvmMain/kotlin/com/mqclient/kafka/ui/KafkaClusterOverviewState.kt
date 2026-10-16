package com.mqclient.kafka.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.kafka.KafkaBrokerSnapshot
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.KafkaTopicSnapshot
import com.mqclient.kafka.ui.ensureKafkaConnecting
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.i18n.stringResource

/** 一次缓存读取的集群概览快照（纯数据；brokers/topics 直接复用服务层快照模型）。 */
data class KafkaClusterOverviewSnapshot(
    val clusterId: String?,
    val controllerId: Int?,
    val brokers: List<KafkaBrokerSnapshot>,
    val topics: List<KafkaTopicSnapshot>,
) {
    val isEmpty: Boolean get() = brokers.isEmpty() && topics.isEmpty()

    /**
     * 离线 broker 推断：describeCluster 只回**存活**节点，副本分配（replicaIds）里
     * 引用、却不在存活列表的 broker id 即「离线 / 已下线」（含已移除但残留副本的
     * id）。这是不引 JMX 指标时唯一可行的离线语义。
     */
    val offlineBrokerIds: Set<Int>
        get() = topics.asSequence()
            .flatMap { it.partitions }
            .flatMap { it.replicaIds }
            .toSet() - brokers.map { it.brokerId }.toSet()
}

/**
 * 集群概览页的状态：**缓存优先、事件驱动** —— 与 [com.mqclient.kafka.ui.topology.KafkaTopologyState]
 * 同一消费模式（进页只读扫描缓存，OBJECTS_SCANNED 事件驱动重建，重扫走 KafkaModule.scanNow）。
 * 集群元信息（Cluster ID / controller）随对象慢扫每轮刷新（默认 10min），无独立定时任务。
 *
 * 连接状态分支语义与拓扑页一致：未连接自动隐式连接、失败展示原因、加载态保持到首个
 * 非空缓存；刷新空结果不覆盖旧快照。
 */
class KafkaClusterOverviewState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<KafkaClusterOverviewSnapshot>>(LoadState.Idle)
        ; private set

    /** 至少完成过一轮对象扫描：区分「还在等首扫」与「扫完了但确实没有对象」。 */
    var scannedOnce by mutableStateOf(false); private set

    /** 进 Tab 首次加载（组合期判定一次）：只有这里做隐式连接兜底。 */
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

    /** 连接状态变化的响应（不发起连接）：Connected → 读缓存；断开 → 已断开态；失败 → 错误态。 */
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

    /** OBJECTS_SCANNED 事件回调：读缓存重建；空结果不覆盖旧快照（刷新不闪屏）。 */
    fun onObjectsScanned() {
        scannedOnce = true
        refreshFromCache()
    }

    /** 「重试」：失败态重新发起连接。 */
    fun retry() {
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        ensureKafkaConnecting(connectionId, status)
    }

    private fun reason(): String =
        KafkaModule.connectionOrNull(connectionId)?.lastError
            ?.takeIf(String::isNotBlank)
            ?: stringResource("kafka_ui_connect_failed")

    /** 读扫描缓存组装快照；空缓存不落地 —— 保持加载态或保留旧快照。 */
    private fun refreshFromCache() {
        val service = KafkaModule.serviceOrNull()
        val cluster = service?.cachedCluster(connectionId)
        val brokers = service?.cachedBrokers(connectionId).orEmpty()
        val topics = service?.cachedTopics(connectionId).orEmpty()
        if (brokers.isNotEmpty() || topics.isNotEmpty()) {
            loadState = LoadState.Success(
                KafkaClusterOverviewSnapshot(
                    clusterId = cluster?.clusterId,
                    controllerId = cluster?.controllerId,
                    brokers = brokers,
                    topics = topics,
                ),
            )
        } else if (scannedOnce) {
            loadState = LoadState.Success(
                KafkaClusterOverviewSnapshot(null, null, emptyList(), emptyList()),
            )
        }
    }
}
