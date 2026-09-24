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

        // 热缓存：直接落地，避免无谓的 Loading 闪烁。
        val cached = service.cachedObjects(connectionId, objectType)
        if (cached.isNotEmpty()) {
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
        if (after.isNotEmpty()) {
            loadState = LoadState.Success(after)
        }
        // 否则保持 Loading：真实扫描完成经事件总线（下方订阅）翻 Success，期间持续显示进度圈。
    }

    LaunchedEffect(connectionId) {
        loadInitial()

        // 扫描完成 → 重读缓存（三条扫描链路任一完成都刷新；payload 过滤本连接）。
        // 处于「加载中」或「已加载」时都刷新：覆盖「连接未就绪 → 稍后真正扫描完成」的落地。
        val bus = CoreModule.eventBus
        listOf(
            IbmMQEvents.OBJECTS_SCANNED,
            IbmMQEvents.MESSAGES_SCANNED,
            IbmMQEvents.SCAN_COMPLETED,
        ).forEach { name ->
            bus.on(name, this) { event ->
                if ((event.payload["connectionId"] as? String) == connectionId) {
                    val latest =
                        IbmMQModule.serviceOrNull()?.cachedObjects(connectionId, objectType) ?: emptyList()
                    if (loadState is LoadState.Success || loadState is LoadState.Loading) {
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
