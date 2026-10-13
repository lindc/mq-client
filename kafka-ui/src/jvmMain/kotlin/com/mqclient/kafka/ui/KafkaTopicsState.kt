package com.mqclient.kafka.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
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
     * 拉取 topic 列表（进入 Tab 自动调一次，「刷新」按钮重拉）。
     * 未连接（AdminClient 不存在）按失败态展示引导文案；查询超时用连接配置的
     * connectionTimeout。失败沿 cause 链取前几条消息 —— KafkaFuture 的
     * ExecutionException 包装会把真正的错误藏在里面。
     */
    suspend fun load() {
        loadState = LoadState.Loading
        val ctx = KafkaModule.connectionOrNull(connectionId)
        val admin = ctx?.adminClient
        if (ctx == null || admin == null || ctx.status != ConnectionStatus.Connected) {
            loadState = LoadState.Error(stringResource("kafka_ui_error_not_connected"))
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

    /** 失败原因转提示文案：沿 cause 链取前几条去重拼接（与 ibm-mq 表单同一套做法）。 */
    private fun failureMessage(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(4)
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("  ←  ")
            .ifBlank { error::class.simpleName ?: "unknown" }
}
