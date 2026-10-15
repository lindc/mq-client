package com.mqclient.kafka.ui.topology

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.ui.ensureKafkaConnecting
import com.mqclient.mqui.state.LoadState
import com.mqclient.core.log.Log
import com.mqclient.uicommon.i18n.stringResource
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.DescribeClusterOptions
import org.apache.kafka.clients.admin.ListTopicsOptions
import org.apache.kafka.common.TopicCollection

/** 拓扑链路日志 tag（state / builder / panel 共用）。 */
internal const val TOPOLOGY_LOG_TAG = "kafka.topology"

/** 一个 broker 的拓扑信息（describeCluster 的投影，纯数据）。 */
data class KafkaBrokerInfo(
    val id: Int,
    val host: String,
    val port: Int,
)

/** 一个分区的拓扑信息（TopicPartitionInfo 的投影）：leader / 副本 / ISR 的 broker id。 */
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

/** 一次拓扑数据拉取的完整快照（纯数据，UI 状态与 kuiver 构建都吃它）。 */
data class KafkaTopologySnapshot(
    val brokers: List<KafkaBrokerInfo>,
    val topics: List<KafkaTopicTopologyInfo>,
) {
    val isEmpty: Boolean get() = brokers.isEmpty() && topics.isEmpty()
}

/**
 * 拓扑页的状态：加载。与 [com.mqclient.kafka.ui.KafkaTopicsState] 同构 —— 直连实时
 * 查询（describeCluster + describeTopics），进入 Tab 拉一次，右上角「重新扫描」重拉；
 * 没有定时扫描也没有本地缓存。连接状态分支语义与 Topic 列表页一致（不提示「请先连接」，
 * 未连接自动隐式连接，失败展示原因）。
 *
 * 刷新不闪屏：已有快照时重拉不清空旧数据（保持旧图渲染），拉取成功后整体替换；
 * 只有还没有快照时才进 Loading。
 */
class KafkaTopologyState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<KafkaTopologySnapshot>>(LoadState.Idle); private set

    /**
     * 拉取拓扑快照：describeCluster 拿 broker 列表；listTopics（**含内部主题**，
     * 过滤交给展示层开关）+ describeTopics 拿分区明细（leader / replicas / isr）。
     */
    suspend fun load() {
        val hasSnapshot = loadState is LoadState.Success
        if (!hasSnapshot) loadState = LoadState.Loading
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        when (status) {
            ConnectionStatus.Connecting -> return // 等 LaunchedEffect 在 Connected 时重跑
            ConnectionStatus.Disconnected -> {
                ensureKafkaConnecting(connectionId, status)
                if (!hasSnapshot) loadState = LoadState.Loading
                return
            }
            ConnectionStatus.Failed -> {
                if (!hasSnapshot) {
                    val reason = KafkaModule.connectionOrNull(connectionId)?.lastError
                        ?.takeIf(String::isNotBlank)
                        ?: stringResource("kafka_ui_connect_failed")
                    loadState = LoadState.Error(reason)
                }
                return
            }
            ConnectionStatus.Connected -> Unit
        }
        val ctx = KafkaModule.connectionOrNull(connectionId)
        val admin = ctx?.adminClient
        if (ctx == null || admin == null) {
            // 状态 Connected 但上下文/句柄未就绪（极短时序窗口）：保持现状，
            // 等下一次状态变化或用户刷新。
            return
        }
        val timeoutMs = ctx.config.connectionTimeout * 1_000L
        try {
            val brokers = withContext(Dispatchers.IO) {
                admin.describeCluster(DescribeClusterOptions().timeoutMs(timeoutMs.toInt()))
                    .nodes()
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
            }
                .map { KafkaBrokerInfo(id = it.id(), host = it.host(), port = it.port()) }
                .sortedBy { it.id }

            // 含内部主题：过滤是展示层开关（builder）的事，数据一次拉全。
            val names = withContext(Dispatchers.IO) {
                admin.listTopics(ListTopicsOptions().listInternal(true).timeoutMs(timeoutMs.toInt()))
                    .listings()
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
            }
                .map { it.name() }
                .sorted()
            val topics: List<KafkaTopicTopologyInfo> = if (names.isEmpty()) {
                emptyList()
            } else {
                val described = withContext(Dispatchers.IO) {
                    admin.describeTopics(TopicCollection.ofTopicNames(names))
                        .allTopicNames()
                        .get(timeoutMs, TimeUnit.MILLISECONDS)
                }
                described.values
                    .map { description ->
                        KafkaTopicTopologyInfo(
                            name = description.name(),
                            internal = description.isInternal,
                            partitions = description.partitions()
                                .map { p ->
                                    KafkaPartitionInfo(
                                        id = p.partition(),
                                        // 离线分区 leader 是 Node.noLeader()（id -1）或 null → 统一 null。
                                        leaderId = p.leader()?.takeIf { it.id() >= 0 }?.id(),
                                        replicaIds = p.replicas().map { it.id() },
                                        isrIds = p.isr().map { it.id() },
                                    )
                                }
                                .sortedBy { it.id },
                        )
                    }
                    .sortedBy { it.name }
            }
            loadState = LoadState.Success(KafkaTopologySnapshot(brokers = brokers, topics = topics))
            Log.info(
                TOPOLOGY_LOG_TAG,
                "topology snapshot loaded: connection=$connectionId brokers=${brokers.size} " +
                    "topics=${topics.size} internal=${topics.count { it.internal }}",
            )
        } catch (error: Throwable) {
            // 有旧快照时刷新失败不打断旧图，仅记日志（重扫按钮语义：成功才换数据）。
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (!hasSnapshot) {
                loadState = LoadState.Error(failureMessage(error))
            }
        }
    }

    /** 「重试」：失败态重新发起连接；连接成功后的数据加载由状态变化驱动。 */
    fun retry() {
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        ensureKafkaConnecting(connectionId, status)
    }

    /** 失败原因转提示文案：沿 cause 链取前几条去重拼接（与 [com.mqclient.kafka.ui.KafkaTopicsState] 同款）。 */
    private fun failureMessage(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(4)
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("  ←  ")
            .ifBlank { error::class.simpleName ?: "unknown" }
}
