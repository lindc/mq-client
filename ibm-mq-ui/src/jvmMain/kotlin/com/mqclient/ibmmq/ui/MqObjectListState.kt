package com.mqclient.ibmmq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mqclient.core.CoreModule
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.event.IbmMQEvents
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.LocalSnackbarHostState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MQ 对象列表页**统一的状态机**：把「初始 / 重试加载 + 刷新 + 事件总线自动刷新」全流程
 * 收拢到一处，所有对象类型列表页（队列 / 通道 / 主题 / 订阅 / 监听器 / 服务 / 进程定义 /
 * 名称列表 / 认证信息 / 通信信息）共用，避免每份页面重复一遍样板。
 *
 * 语义与 [LoadStateContent] 配合：初始 [LoadState.Loading]（记住连接 id，切换连接自动重置）；
 * 读缓存（空则扫一轮）决定如何落地；连接未就绪时 scanObjects no-op，保持 Loading 等真实
 * 扫描经事件总线翻 Success（不提交空态，避免空白窗体）。刷新保留旧数据、顶部细进度条，
 * 失败仅 snackbar 提示。
 *
 * 各页只需传入 `connectionId` / `objectType` / 两类失败文案，无需关心加载细节。
 */
data class MqObjectListState(
    val loadState: LoadState<List<MqObjectSnapshot>>,
    val isRefreshing: Boolean,
    val refresh: () -> Unit,
    val retry: () -> Unit,
)

/** 初始 / 重试加载时 Loading 至少可见的时长，避免缓存命中过快导致进度圈"闪不过去"。 */
private const val MIN_LOADING_MS = 400L

@Composable
fun rememberMqObjectListState(
    connectionId: String,
    objectType: MqObjectType,
    loadFailedText: (Throwable) -> String,
    scanFailedText: (Throwable) -> String,
): MqObjectListState {
    // 加载状态收拢为单一 LoadState：初始 Loading（记住连接 id，切换连接自动重置）。
    var loadState by remember(connectionId) {
        mutableStateOf<LoadState<List<MqObjectSnapshot>>>(LoadState.Loading)
    }
    // 刷新（保留旧数据）时的小指示器；与整页 Loading 区分开。
    var isRefreshing by remember { mutableStateOf(false) }

    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    /** 保证 Loading 至少渲染一帧：缓存命中 / 扫描过快时 Loading→Success 会被同帧合并，进度圈闪不过去。 */
    suspend fun delayUntilMin(start: Long) {
        val elapsed = System.currentTimeMillis() - start
        if (elapsed < MIN_LOADING_MS) delay(MIN_LOADING_MS - elapsed)
    }

    /** 初次 / 重试加载：置 Loading → 读缓存（空则扫一轮），由缓存是否就绪决定如何落地。 */
    suspend fun loadInitial() {
        loadState = LoadState.Loading
        val start = System.currentTimeMillis()
        val service = IbmMQModule.serviceOrNull() ?: run {
            delayUntilMin(start)
            return
        }

        // 热缓存：但若只是快扫写的最小快照（不含子类型 channelType / MQIA_Q_TYPE），
        // 直接渲染会先显示问号图标、慢扫补全后再闪成正确图标（见下方说明）。因此这类
        // 「未带类型的快照」视为尚未就绪，落入下方慢扫路径，保持加载态直到类型补全。
        val cached = service.cachedObjects(connectionId, objectType)
        if (cached.isNotEmpty() && cached.isTyped(objectType)) {
            delayUntilMin(start)
            loadState = LoadState.Success(cached)
            return
        }

        // 冷缓存：触发扫描。连接就绪则扫完写缓存、此处能拿到数据；未就绪则 scanObjects
        // no-op（connectedContext 为 null），缓存仍空 → 保持 Loading，等连接就绪后真正
        // 的扫描经事件总线翻 Success（不提交空态，避免空白窗体）。
        runCatching { service.scanObjects(connectionId) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                delayUntilMin(start)
                loadState = LoadState.Error(loadFailedText(error), error)
                return
            }

        val after = service.cachedObjects(connectionId, objectType)
        delayUntilMin(start)
        // 非空且已带类型 → 安全落地（慢扫产物，图标正确，不会闪问号）；
        // 其余两种情况都保持 Loading，等慢扫经 OBJECTS_SCANNED 事件翻 Success：
        //  - 空列表：可能是连接尚未就绪（scanObjects no-op）或该类型无对象，沿用原语义
        //    不提交空态，避免空白窗体；
        //  - 非空但无类型（快扫最小快照）：等慢扫补全类型，避免先渲染问号再闪。
        if (after.isNotEmpty() && after.isTyped(objectType)) {
            loadState = LoadState.Success(after)
        }
        // 否则保持 Loading：真实扫描完成经事件总线（下方订阅）翻 Success，期间持续显示进度圈。
    }

    LaunchedEffect(connectionId) {
        loadInitial()

        // 慢扫完成 → 重读缓存翻 Success：慢扫产物一定带类型，可安全落地（「加载中」或
        // 「已加载」时都接收，覆盖「连接未就绪 → 稍后真正扫描完成」的落地）。
        val bus = CoreModule.eventBus
        bus.on(IbmMQEvents.OBJECTS_SCANNED, this) { event ->
            if ((event.payload["connectionId"] as? String) == connectionId) {
                val latest =
                    IbmMQModule.serviceOrNull()?.cachedObjects(connectionId, objectType) ?: emptyList()
                if (loadState is LoadState.Success || loadState is LoadState.Loading) {
                    loadState = LoadState.Success(latest)
                }
            }
        }
        // 快扫（心跳 / 手动快扫）只补深度 / 状态，写入的是「无类型最小快照」——它若覆盖已带
        // 类型的列表会重新触发问号闪烁。因此只在该连接已成功展示、且本次数据仍带类型时更新；
        // 已带类型的列表在快扫 add-or-update 下会保留 channelType / MQIA_Q_TYPE，故可放心刷新。
        listOf(
            IbmMQEvents.MESSAGES_SCANNED,
            IbmMQEvents.SCAN_COMPLETED,
        ).forEach { name ->
            bus.on(name, this) { event ->
                if ((event.payload["connectionId"] as? String) == connectionId) {
                    val latest =
                        IbmMQModule.serviceOrNull()?.cachedObjects(connectionId, objectType) ?: emptyList()
                    if (loadState is LoadState.Success && latest.isTyped(objectType)) {
                        loadState = LoadState.Success(latest)
                    }
                }
            }
        }
    }

    /** 刷新（保留旧数据）：置 isRefreshing，扫完由事件驱动重载；失败仅 snackbar 提示。 */
    fun refresh() {
        scope.launch {
            isRefreshing = true
            val service = IbmMQModule.serviceOrNull() ?: run { isRefreshing = false; return@launch }
            runCatching { service.scanObjects(connectionId) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    snackbarHostState.showSnackbar(scanFailedText(error))
                }
            // 成功后的缓存重载由 SCAN_COMPLETED 事件驱动（上面已订阅）；保留旧数据。
            isRefreshing = false
        }
    }

    return MqObjectListState(
        loadState = loadState,
        isRefreshing = isRefreshing,
        refresh = ::refresh,
        retry = { scope.launch { loadInitial() } },
    )
}

/** stringResource 无格式化参数；%s / %1$s 占位替换（全项目约定，仅此一份实现）。 */
internal fun String.withArg(arg: Any): String =
    replace("%1\$s", arg.toString()).replace("%s", arg.toString())

/**
 * 列表快照是否已带「子类型」字段（图标解析依赖）：通道看 [MqObjectSnapshot.channelType]，
 * 队列看 `attributes["MQIA_Q_TYPE"]`。这两类字段**只**由慢扫（MQCMD_INQUIRE_CHANNEL /
 * MQCMD_INQUIRE_Q）写入；心跳快扫写入的是无类型最小快照。
 *
 * 未带类型的快照直接渲染会先显示问号图标、慢扫补全后再闪成正确图标，故视为「尚未就绪」，
 * 列表保持加载态直到慢扫补全类型。其它对象类型不走子类型图标解析，一律视为已就绪。
 */
private fun List<MqObjectSnapshot>.isTyped(objectType: MqObjectType): Boolean = when (objectType) {
    MqObjectType.CHANNEL -> any { it.channelType != null }
    MqObjectType.QUEUE -> any { it.attributes.containsKey("MQIA_Q_TYPE") }
    else -> true
}
