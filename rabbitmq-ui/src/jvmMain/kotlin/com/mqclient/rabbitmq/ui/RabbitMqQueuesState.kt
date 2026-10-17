package com.mqclient.rabbitmq.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.mqui.state.LoadState
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.RabbitMqQueueSnapshot
import com.mqclient.uicommon.i18n.stringResource

/** 一条队列的展示信息：慢扫静态属性 + 快扫计数合并（未扫到计数的字段显示 "-"）。 */
data class RabbitMqQueueCardInfo(
    val name: String,
    val state: String?,
    val durable: Boolean,
    val autoDelete: Boolean,
    val exclusive: Boolean,
    val messages: Long?,
    val messagesReady: Long?,
    val messagesUnacknowledged: Long?,
    val consumers: Int?,
)

/**
 * 队列列表页的状态：**缓存优先、事件驱动**（与 KafkaTopicsState 同一消费模式）：
 * 进页只读扫描缓存（[RabbitMqModule.serviceOrNull] 的 cachedQueues / cachedQueueStatus），
 * 列表由 `rabbitmq.scan.objects.completed`（静态属性重建）与
 * `rabbitmq.scan.status.completed`（计数刷新）两类事件驱动；「刷新」按钮调
 * [RabbitMqModule.scanNow] 触发一轮手动慢扫。
 *
 * 静态属性与计数是两条扫描产物，**按队列名合并**：快扫还没跑到（或队列刚建）时
 * 计数字段为 null，卡片显示 "-"，不打断列表。
 *
 * 连接状态分支与 Kafka 一致：未连接自动隐式连接（Loading 等状态翻转）、失败显示
 * 原因 + 重试。加载态保持到首个非空缓存。
 *
 * [problemOnly]：「只看异常队列」过滤（概览健康度 Banner 点击跳转时经
 * [RabbitMqUiHints.consumeProblemQueueFilter] 自动开启，chip 可手动关闭）——
 * 异常 = 有消息堆积且无消费者（与概览 Banner 的判定同口径）。
 */
class RabbitMqQueuesState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<List<RabbitMqQueueCardInfo>>>(LoadState.Idle); private set
    var search by mutableStateOf("")

    /** 「只看异常队列」过滤开关（messages > 0 && consumers == 0）。 */
    var problemOnly by mutableStateOf(false)

    /** 至少完成过一轮对象扫描：区分「还在等首扫」与「扫完了但确实没有对象」。 */
    var scannedOnce by mutableStateOf(false); private set

    /** 当前数据里是否存在异常队列（有堆积无消费者）：过滤 chip 的可用性依据。 */
    val hasProblemQueues: Boolean
        get() = when (val s = loadState) {
            is LoadState.Success -> s.data.any { isProblem(it) }
            else -> false
        }

    /** 搜索过滤后的列表（按名称包含匹配，忽略大小写；叠加 [problemOnly] 过滤）。 */
    val filtered: List<RabbitMqQueueCardInfo>
        get() = when (val s = loadState) {
            is LoadState.Success -> s.data
                .asSequence()
                .filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
                .filter { !problemOnly || isProblem(it) }
                .toList()
            else -> emptyList()
        }

    /** 异常队列判定：有消息堆积且无消费者（与概览 Banner 同口径；计数未知的跳过）。 */
    private fun isProblem(info: RabbitMqQueueCardInfo): Boolean =
        (info.messages ?: 0L) > 0 && (info.consumers ?: 0) == 0

    /**
     * 进 Tab 首次加载（组合期判定一次）：**只有这里做隐式连接兜底**。显式断开后的
     * 状态变化走 [onStatusChanged]，不重连（断开后 Tab 由宿主收走）。
     */
    suspend fun load() {
        loadState = LoadState.Loading
        consumeProblemFilterHint()
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        when (status) {
            ConnectionStatus.Connecting -> return // 等状态翻转后由 onStatusChanged 接管
            ConnectionStatus.Disconnected -> {
                ensureRabbitMqConnecting(connectionId, status)
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

    /** 连接状态变化的响应（**不发起连接**）。 */
    fun onStatusChanged(status: ConnectionStatus) {
        when (status) {
            ConnectionStatus.Connected -> {
                consumeProblemFilterHint()
                if (loadState !is LoadState.Success) loadState = LoadState.Loading
                refreshFromCache()
            }
            ConnectionStatus.Disconnected ->
                loadState = LoadState.Error(stringResource("rabbitmq_ui_connect_disconnected"))
            ConnectionStatus.Failed -> loadState = LoadState.Error(reason())
            ConnectionStatus.Connecting ->
                if (loadState !is LoadState.Success) loadState = LoadState.Loading
        }
    }

    private fun reason(): String =
        RabbitMqModule.connectionOrNull(connectionId)?.lastError
            ?.takeIf(String::isNotBlank)
            ?: stringResource("rabbitmq_ui_connect_failed")

    /** OBJECTS_SCANNED 事件回调：静态属性重建；空缓存不落地（保持加载态）。 */
    fun onObjectsScanned() {
        scannedOnce = true
        refreshFromCache()
    }

    /** STATUS_SCANNED 事件回调：只刷计数（列表本体不动，避免闪烁）。 */
    fun onStatusScanned() {
        refreshFromCache(keepList = true)
    }

    /** 「刷新」按钮：触发一轮手动慢扫，扫完经 OBJECTS_SCANNED 事件回到这里。 */
    fun requestScan() {
        RabbitMqModule.scanNow(connectionId)
    }

    /** 读扫描缓存合并成卡片模型；keepList 时空缓存不落地。 */
    private fun refreshFromCache(keepList: Boolean = false) {
        val service = RabbitMqModule.serviceOrNull()
        val queues: List<RabbitMqQueueSnapshot> = service?.cachedQueues(connectionId) ?: emptyList()
        if (queues.isNotEmpty()) {
            val status = service?.cachedQueueStatus(connectionId) ?: emptyMap()
            loadState = LoadState.Success(
                queues.map { queue ->
                    val s = status[queue.name]
                    RabbitMqQueueCardInfo(
                        name = queue.name,
                        state = s?.state ?: queue.state,
                        durable = queue.durable,
                        autoDelete = queue.autoDelete,
                        exclusive = queue.exclusive,
                        messages = s?.messages,
                        messagesReady = s?.messagesReady,
                        messagesUnacknowledged = s?.messagesUnacknowledged,
                        consumers = s?.consumers,
                    )
                },
            )
        } else if (scannedOnce && !keepList) {
            loadState = LoadState.Success(emptyList())
        }
    }

    /** 「重试」：失败态重新发起连接；连接成功后的数据加载由状态变化 + 扫描事件驱动。 */
    fun retry() {
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        ensureRabbitMqConnecting(connectionId, status)
    }

    /** 概览 Banner 跳转时留下的过滤提示：消费一次，自动开启「只看异常队列」。 */
    private fun consumeProblemFilterHint() {
        if (RabbitMqUiHints.consumeProblemQueueFilter(connectionId)) {
            problemOnly = true
        }
    }
}
