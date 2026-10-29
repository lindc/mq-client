package com.mqclient.kafka.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.kafka.KafkaConfig
import com.mqclient.kafka.KafkaModule
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.i18n.stringResource

/** 一个 Consumer Group 的展示信息（扫描缓存的投影，纯数据）。 */
data class KafkaGroupInfo(
    val name: String,
    /** 组状态（Stable / Empty / Dead…，行业术语不翻译）。 */
    val state: String,
    /** 当前成员数（卡片不展示，进详情对话框看）。 */
    val members: Int,
    /** 简单组：没有分配协议（客户端手工分区分配）。 */
    val simple: Boolean,
    /**
     * 总 Lag（快扫缓存 groupLags 按 groupId 联表）；null = 还没有心跳产物
     * （连接刚建 / 快扫未跑）—— 卡片右上角不显示 Lag 位。
     */
    val totalLag: Long? = null,
)

/**
 * 首屏「等一轮扫描」的上限。
 *
 * 20 秒的依据：服务层的消费组列表超时是 20 秒档（[KafkaConfig.GROUP_LIST_TIMEOUT_MS]），
 * 这里必须**不小于**它 —— 否则 UI 会比底层先放弃，出现「明明还在查，界面已经报超时」。
 * 旧值 5 秒正是这个错配：等的是「整轮对象慢扫」（brokers + topics + groups 三类并发），
 * 大集群上 topics 一批就超过 5 秒。
 *
 * 注意：这条只是**兜底**。正常路径走 [KafkaConsumerGroupsState.fetchGroupsDirectly]
 * （定向查询消费组），压根不等整轮扫描。
 */
private const val SCAN_WAIT_TIMEOUT_MS = 20_000L

/**
 * Consumer Group 列表页的状态：**定向查询优先 + 事件驱动刷新**。
 *
 * 与 [KafkaTopicsState] 的差别只有一处，但很关键：进页不再干等整轮对象慢扫，而是
 * 直接用 `refreshGroups` 查一次组（listConsumerGroups + 批量 describeConsumerGroups），
 * 1~2 秒就能出卡片。Lag 不在这条路径上 —— 它由独立的心跳快扫产出，卡片先显示
 * 「计算中」。`kafka.scan.objects.completed` 事件仍会驱动整表刷新（与 topic 页同构）。
 *
 * 「刷新」按钮仍调 [KafkaModule.scanNow] 触发一轮手动慢扫（用户要的是全量最新）；
 * 「重试」（失败态）走同一套首屏路径。
 */
class KafkaConsumerGroupsState(
    private val connectionId: String,
) {

    var loadState by mutableStateOf<LoadState<List<KafkaGroupInfo>>>(LoadState.Idle); private set
    var search by mutableStateOf("")

    /** 至少完成过一轮对象扫描：区分「还在等首扫」与「扫完了但确实没有组」。 */
    var scannedOnce by mutableStateOf(false); private set

    private var loadAwaiter = CompletableDeferred<Unit>()

    /** 搜索过滤后的列表（按名称包含匹配，忽略大小写）。 */
    val filtered: List<KafkaGroupInfo>
        get() = when (val s = loadState) {
            is LoadState.Success -> s.data
                .filter { search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) }
            else -> emptyList()
        }

    /**
     * 进 Tab 首次加载（组合期判定一次）：**只有这里做隐式连接兜底** —— 用户主动进入
     * 页面才有「自动连接」的意图。显式断开后的状态变化走 [onStatusChanged]，不重连。
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
        loadAfterConnected()
    }

    /**
     * 连接就绪后的取数路径（首屏与「连接刚建立」共用，保证两条入口行为一致）：
     * 缓存 → 定向查询 → 等扫描事件 → 柔和提示。
     */
    private suspend fun loadAfterConnected() {
        refreshFromCache()
        if (loadState is LoadState.Success) return

        // ① 定向查询：只拉组（listConsumerGroups + 批量 describeConsumerGroups），
        //    不等整轮慢扫。绝大多数集群 1~2 秒出卡片，这是首屏的主路径。
        if (fetchGroupsDirectly()) return

        // ② 定向查询没成（连接正忙 / Admin 尚未就绪）：退回等慢扫事件。
        if (scannedOnce) {
            refreshFromCache()
            return
        }
        awaitScan(SCAN_WAIT_TIMEOUT_MS)
    }

    /**
     * 等一轮扫描完成并读缓存；超时不再判死，改成「耗时较长」的柔和提示。
     *
     * 为什么不再用 5 秒：原来的 `withTimeoutOrNull(5000)` 等的是**整轮对象慢扫**
     * （brokers + topics + groups 三类并发），大集群上 topics 一批就轻松超过 5 秒 ——
     * 于是组查询明明会成功，界面却先报「加载超时」。现在 20 秒档，且超时文案说明
     * 这是「慢」而不是「失败」，并保留重试。
     */
    private suspend fun awaitScan(timeoutMs: Long) {
        loadAwaiter = CompletableDeferred()
        val got = withTimeoutOrNull(timeoutMs) { loadAwaiter.await() } != null
        if (!got) {
            // 不判死：把状态置成可重试的提示；若慢扫稍后真完成了，onObjectsScanned
            // 仍会 refreshFromCache 把提示换成真实列表（自愈）。
            loadState = LoadState.Error(stringResource("kafka_ui_group_load_timeout"))
            return
        }
        refreshFromCache()
    }

    /**
     * 定向查询消费组列表（只查组，不查 topic / broker，不算 Lag）。
     *
     * @return true = 已拿到结果（含「确实一个组都没有」），调用方无需再等扫描事件。
     */
    private suspend fun fetchGroupsDirectly(): Boolean {
        val service = KafkaModule.serviceOrNull() ?: return false
        val groups = try {
            service.refreshGroups(connectionId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // 连接正忙 / Admin 未就绪 / 权限不足：交给慢扫事件兜底，不在这里判死。
            return false
        }
        scannedOnce = true
        loadState = LoadState.Success(groups.map { it.toInfo() })
        return true
    }

    /** 快照 → 卡片模型（Lag 取当前心跳缓存；没有就是 null = 卡片显示「计算中」）。 */
    private fun com.mqclient.kafka.KafkaGroupSnapshot.toInfo(): KafkaGroupInfo =
        KafkaGroupInfo(
            name = groupId,
            state = state,
            members = members,
            simple = simple,
            totalLag = KafkaModule.serviceOrNull()
                ?.cachedGroupLags(connectionId)
                ?.get(groupId)
                ?.totalLag,
        )

    /**
     * 连接状态变化的响应（**不发起连接**）：Connected → 读缓存；Disconnected →
     * 「已断开」态；Failed → 错误态；Connecting → 保持加载。
     */
    suspend fun onStatusChanged(status: ConnectionStatus) {
        when (status) {
            // 隐式连接的落点：load() 在 Disconnected 时只发起连接就返回了，真正取数
            // 必须在这里接上 —— 否则首屏会永远停在 Loading（旧行为靠 5 秒超时兜底）。
            ConnectionStatus.Connected -> {
                if (loadState !is LoadState.Success) loadState = LoadState.Loading
                loadAfterConnected()
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

    /** OBJECTS_SCANNED 事件回调：读缓存重建；空缓存不落地（保留旧列表 / 保持加载态）。 */
    fun onObjectsScanned() {
        scannedOnce = true
        if (!loadAwaiter.isCompleted) loadAwaiter.complete(Unit)
        refreshFromCache()
    }

    /** 「刷新」按钮：触发一轮手动慢扫，扫完经 OBJECTS_SCANNED 事件回到这里。 */
    fun requestScan() {
        KafkaModule.scanNow(connectionId)
    }

    /**
     * Lag 告警阈值（连接设置 lagWarningThreshold，默认 10000；0 = 不告警）：
     * 卡片右上角 Lag 超过它换警示色。读当前连接的配置快照。
     */
    val lagWarningThreshold: Long
        get() = KafkaModule.connectionOrNull(connectionId)?.config?.lagWarningThreshold
            ?: 10_000L

    /** 读扫描缓存投影成卡片模型；空缓存不落地。groups 与 groupLags 按 groupId 联表。 */
    private fun refreshFromCache() {
        val service = KafkaModule.serviceOrNull()
        val groups = service?.cachedGroups(connectionId) ?: emptyList()
        val lags = service?.cachedGroupLags(connectionId) ?: emptyMap()
        if (groups.isNotEmpty()) {
            loadState = LoadState.Success(
                groups.map { g ->
                    KafkaGroupInfo(
                        name = g.groupId,
                        state = g.state,
                        members = g.members,
                        simple = g.simple,
                        totalLag = lags[g.groupId]?.totalLag,
                    )
                },
            )
        } else if (scannedOnce) {
            loadState = LoadState.Success(emptyList())
        }
    }

    /** 「重试」：重新走一遍首屏路径（定向查询 → 等扫描）；连接不在时重新发起连接。 */
    suspend fun retry() {
        val status = ConnectionStore.connections.value
            .firstOrNull { it.id == connectionId }?.status ?: ConnectionStatus.Disconnected
        when (status) {
            // 连接仍在：先回加载态，再走与首屏完全相同的路径（定向查询为主）。
            ConnectionStatus.Connected -> load()
            else -> {
                ensureKafkaConnecting(connectionId, status)
                // 连接建立后由 onStatusChanged → load 的路径并不存在（它只读缓存），
                // 所以这里把「连接中」留在 Loading，等状态翻转后由面板的
                // LaunchedEffect 再触发一次 load —— 与首屏的隐式连接同一时序。
                loadState = LoadState.Loading
            }
        }
    }
}
