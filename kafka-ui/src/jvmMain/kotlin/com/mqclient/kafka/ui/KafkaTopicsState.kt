package com.mqclient.kafka.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.kafka.KafkaModule
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.i18n.stringResource

/** 一个 Topic 的展示信息（扫描缓存的投影，纯数据）。 */
data class KafkaTopicInfo(
    val name: String,
    /** 分区数。 */
    val partitions: Int,
    /** 副本因子（取各分区副本数的最小值 —— 正常集群里各分区一致）。 */
    val replicas: Int,
    /** Kafka 内部主题（__consumer_offsets / __transaction_state 等）。 */
    val internal: Boolean,
)

/**
 * Topic 列表页的状态：**缓存优先、事件驱动** —— 与 ibm-mq 的 MqObjectListState 同一
 * 消费模式：进页只读扫描缓存（[KafkaModule.serviceOrNull] 的 cachedTopics），列表内容
 * 由 `kafka.scan.objects.completed` 事件驱动重建（扫描任务在连接成功后按配置间隔自动
 * 跑，UI 不直查 AdminClient）。「刷新」按钮调 [KafkaModule.scanNow] 触发一轮手动扫描。
 *
 * 连接状态分支与旧行为一致：未连接自动隐式连接（Loading 等状态翻转）、失败显示原因
 * + 重试。加载态保持到**首个非空缓存**（与 MQ「不提交空态」同语义）；扫完确实为空
 * 才显示空列表。
 */
class KafkaTopicsState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<List<KafkaTopicInfo>>>(LoadState.Idle); private set
    var search by mutableStateOf("")

    /** 至少完成过一轮对象扫描：区分「还在等首扫」与「扫完了但确实没有对象」。 */
    var scannedOnce by mutableStateOf(false); private set

    /** 搜索过滤后的列表（按名称包含匹配，忽略大小写）。 */
    val filtered: List<KafkaTopicInfo>
        get() = when (val s = loadState) {
            is LoadState.Success -> s.data
                .filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
            else -> emptyList()
        }

    /**
     * 进 Tab 首次加载（组合期判定一次）：**只有这里做隐式连接兜底** —— 用户主动进入
     * 页面才有「自动连接」的意图。显式断开后的状态变化走 [onStatusChanged]，不重连
     * （否则面板会把用户刚点的断开静默撤销；断开后 Tab 由宿主收走）。
     */
    suspend fun load() {
        loadState = LoadState.Loading
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        when (status) {
            ConnectionStatus.Connecting -> return // 等状态翻转后由 onStatusChanged 接管
            ConnectionStatus.Disconnected -> {
                ensureKafkaConnecting(connectionId, status)
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

    /**
     * 连接状态变化的响应（**不发起连接**）：Connected → 读缓存；Disconnected →
     * 「已断开」态；Failed → 错误态；Connecting → 保持加载。
     */
    fun onStatusChanged(status: ConnectionStatus) {
        when (status) {
            ConnectionStatus.Connected -> {
                if (loadState !is LoadState.Success) loadState = LoadState.Loading
                refreshFromCache()
            }
            ConnectionStatus.Disconnected ->
                loadState = LoadState.Error(stringResource("kafka_ui_connect_disconnected"))
            ConnectionStatus.Failed -> loadState = LoadState.Error(reason())
            ConnectionStatus.Connecting ->
                if (loadState !is LoadState.Success) loadState = LoadState.Loading
        }
    }

    private fun reason(): String =
        KafkaModule.connectionOrNull(connectionId)?.lastError
            ?.takeIf(String::isNotBlank)
            ?: stringResource("kafka_ui_connect_failed")

    /** OBJECTS_SCANNED 事件回调：读缓存重建；空缓存不落地（保留旧图 / 保持加载态）。 */
    fun onObjectsScanned() {
        scannedOnce = true
        refreshFromCache()
    }

    /** 「刷新」按钮：触发一轮手动慢扫，扫完经 OBJECTS_SCANNED 事件回到这里。 */
    fun requestScan() {
        KafkaModule.scanNow(connectionId)
    }

    /** 读扫描缓存投影成卡片模型；空缓存不落地 —— 保持加载态或保留旧列表。 */
    private fun refreshFromCache() {
        val topics = KafkaModule.serviceOrNull()?.cachedTopics(connectionId) ?: emptyList()
        if (topics.isNotEmpty()) {
            loadState = LoadState.Success(
                topics.map { topic ->
                    KafkaTopicInfo(
                        name = topic.name,
                        partitions = topic.partitions.size,
                        replicas = topic.partitions.minOfOrNull { it.replicaIds.size } ?: 0,
                        internal = topic.internal,
                    )
                },
            )
        } else if (scannedOnce) {
            loadState = LoadState.Success(emptyList())
        }
    }

    /** 「重试」：失败态重新发起连接；连接成功后的数据加载由状态变化 + 扫描事件驱动。 */
    fun retry() {
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        ensureKafkaConnecting(connectionId, status)
    }
}
