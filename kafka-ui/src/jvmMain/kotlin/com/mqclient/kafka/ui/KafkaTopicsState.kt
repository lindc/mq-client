package com.mqclient.kafka.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.kafka.KafkaModule
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.i18n.stringResource
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.ListTopicsOptions
import org.apache.kafka.common.TopicCollection

/** 一个 Topic 的展示信息（describeTopics 的投影，纯数据）。 */
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
 * Topic 列表页的状态：加载、搜索过滤。与 ibm-mq 的扫描-缓存模型不同，Kafka 阶段三
 * **直连实时查询** —— 进入 Tab 时经 AdminClient listTopics + describeTopics 拉一次，
 * 工具栏「刷新」重拉；没有定时扫描，也没有本地缓存（mq_connection 的连接是显式的，
 * 未连接时给出引导文案而不是空列表）。
 */
class KafkaTopicsState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<List<KafkaTopicInfo>>>(LoadState.Idle); private set
    var search by mutableStateOf("")

    /** 搜索过滤后的列表（按名称包含匹配，忽略大小写）。 */
    val filtered: List<KafkaTopicInfo>
        get() = when (val s = loadState) {
            is LoadState.Success -> s.data
                .filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
            else -> emptyList()
        }

    /**
     * 拉取 topic 列表（进入 Tab 自动调一次，「刷新」按钮重拉；连接状态变化由
     * [TopicListPanel] 的 LaunchedEffect 触发重跑）。与 IBM MQ 的 MqObjectListState
     * 同一语义：**不提示「请先连接」** ——
     *  - Connected：正常 listTopics + describeTopics；
     *  - Connecting / Disconnected：保持 Loading（后者顺带兜底触发隐式连接），
     *    等状态翻转后由 LaunchedEffect 重跑翻 Success；
     *  - Failed：Error 态展示失败原因（ctx.lastError），配「重试」按钮。
     */
    suspend fun load() {
        loadState = LoadState.Loading
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        when (status) {
            ConnectionStatus.Connecting -> return // 等 LaunchedEffect 在 Connected 时重跑
            ConnectionStatus.Disconnected -> {
                ensureKafkaConnecting(connectionId, status)
                return
            }
            ConnectionStatus.Failed -> {
                val reason = KafkaModule.connectionOrNull(connectionId)?.lastError
                    ?.takeIf(String::isNotBlank)
                    ?: stringResource("kafka_ui_connect_failed")
                loadState = LoadState.Error(reason)
                return
            }
            ConnectionStatus.Connected -> Unit
        }
        val ctx = KafkaModule.connectionOrNull(connectionId)
        val admin = ctx?.adminClient
        if (ctx == null || admin == null) {
            // 状态 Connected 但上下文/句柄未就绪（极短时序窗口）：保持 Loading，
            // 等下一次状态变化或用户刷新，不误导成「未连接」。
            return
        }
        val timeoutMs = ctx.config.connectionTimeout * 1_000L
        try {
            val listings = withContext(Dispatchers.IO) {
                admin.listTopics(ListTopicsOptions().timeoutMs(timeoutMs.toInt()))
                    .listings()
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
            }
            val names = listings.map { it.name() }.sorted()
            val described = withContext(Dispatchers.IO) {
                admin.describeTopics(TopicCollection.ofTopicNames(names))
                    .allTopicNames()
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
            }
            loadState = LoadState.Success(
                described.values.map { description ->
                    KafkaTopicInfo(
                        name = description.name(),
                        partitions = description.partitions().size,
                        replicas =
                            description.partitions().minOfOrNull { it.replicas().size } ?: 0,
                        internal = description.isInternal,
                    )
                },
            )
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            loadState = LoadState.Error(failureMessage(error))
        }
    }

    /** 「重试」：失败态重新发起连接；连接成功后的数据加载由状态变化驱动。 */
    fun retry() {
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        ensureKafkaConnecting(connectionId, status)
    }

    /** 失败原因转提示文案：沿 cause 链取前几条去重拼接（与 ibm-mq 表单同一套做法）。 */
    private fun failureMessage(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(4)
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("  ←  ")
            .ifBlank { error::class.simpleName ?: "unknown" }
}
