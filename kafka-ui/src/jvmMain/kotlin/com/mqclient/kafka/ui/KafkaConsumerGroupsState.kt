package com.mqclient.kafka.ui

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
import org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions
import org.apache.kafka.common.ConsumerGroupState

/** 一个 Consumer Group 的展示信息（describeConsumerGroups 的投影，纯数据）。 */
data class KafkaGroupInfo(
    val name: String,
    /** 组状态（Stable / Empty / Dead…，行业术语不翻译）。 */
    val state: String,
    /** 当前成员数。 */
    val members: Int,
    /** 简单组：没有分配协议（客户端手工分区分配）。 */
    val simple: Boolean,
)

/**
 * Consumer Group 列表页的状态：加载、搜索过滤。与 [KafkaTopicsState] 完全同构 ——
 * 直连实时查询 + [KafkaPanelGate] 的连接观察语义（未连接/失败不提示引导，自动
 * 隐式连接或展示原因 + 重试），只是查询换成 listConsumerGroups +
 * describeConsumerGroups。新 Kafka 面板照这份抄即可，连接状态不用再各写一遍。
 */
class KafkaConsumerGroupsState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<List<KafkaGroupInfo>>>(LoadState.Idle); private set
    var search by mutableStateOf("")

    /**
     * 本轮 describe 因无权 / 组已消失被跳过的组数（成功路径统计，其余路径清零）。
     * 受限账号的正常现象，工具栏给出轻提示即可，不算错误。
     */
    var skippedCount by mutableStateOf(0); private set

    /** 搜索过滤后的列表（按名称包含匹配，忽略大小写）。 */
    val filtered: List<KafkaGroupInfo>
        get() = when (val s = loadState) {
            is LoadState.Success -> s.data
                .filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
            else -> emptyList()
        }

    /**
     * 拉取 group 列表：listConsumerGroups 拿 id 清单，describeConsumerGroups 补
     * 状态 / 成员数。连接状态分支与 [KafkaTopicsState.load] 一致。
     */
    suspend fun load() {
        loadState = LoadState.Loading
        skippedCount = 0
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
            // 状态 Connected 但上下文/句柄未就绪（极短时序窗口）：保持 Loading。
            return
        }
        val timeoutMs = ctx.config.connectionTimeout * 1_000L
        try {
            val groupIds = withContext(Dispatchers.IO) {
                admin.listConsumerGroups(ListConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()))
                    .all()
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
                    .map { it.groupId() }
                    .sorted()
            }
            if (groupIds.isEmpty()) {
                loadState = LoadState.Success(emptyList())
                return
            }
            // 逐组 describe 容错：受限账号（如 app）只有前缀 ACL，无权组会整组
            // GroupAuthorizationException —— 用 describedGroups() 的**单组 future**
            // 逐个取，失败（无权 / 组已消失 / 单组超时）只跳过该组，不让整页失败。
            // 用 .all() 则一个无权组就会炸掉全部。
            val described = withContext(Dispatchers.IO) {
                admin.describeConsumerGroups(
                    groupIds,
                    DescribeConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()),
                )
                    .describedGroups()
                    .mapValues { (_, future) ->
                        try {
                            future.get(timeoutMs, TimeUnit.MILLISECONDS)
                        } catch (error: Throwable) {
                            // 协程取消必须原样上抛（藏在 cause 链里时也要认出来）。
                            if (generateSequence<Throwable>(error) { it.cause }
                                    .any { it is kotlinx.coroutines.CancellationException }
                            ) {
                                throw error
                            }
                            null
                        }
                    }
            }
            loadState = LoadState.Success(
                described.values
                    .filterNotNull()
                    .sortedBy { it.groupId() }
                    .map { description ->
                        KafkaGroupInfo(
                            name = description.groupId(),
                            state = description.state().toString(),
                            members = description.members().size,
                            simple = description.isSimpleConsumerGroup,
                        )
                    },
            )
            skippedCount = described.values.count { it == null }
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

    /** 失败原因转提示文案：沿 cause 链取前几条去重拼接（与 [KafkaTopicsState] 同款）。 */
    private fun failureMessage(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(4)
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("  ←  ")
            .ifBlank { error::class.simpleName ?: "unknown" }
}
