package com.mqclient.ibmmq.ui.topology

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.topology.TopologyCanvas
import com.mqclient.topology.getColor
import com.mqclient.graph.TopologyData
import com.mqclient.graph.TopologyNodeType
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.CoreModule
import com.mqclient.core.concurrent.runCatchingCancellable
import com.mqclient.core.topology.TopologyFocusHolder
import com.mqclient.ibmmq.IbmMQConfig
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.event.IbmMQEvents
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogHost
import com.mqclient.ibmmq.ui.properties.rememberObjectPropertiesDialogController
import com.mqclient.ibmmq.ui.properties.toMqObjectType
import com.mqclient.ibmmq.ibmMQConfigFromOptions
import com.mqclient.core.log.Log
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.launch

/**
 * IBM MQ 专属拓扑面板（固定列布局版）。
 *
 * 列序按 MQ 消息流向固定（alias→topic→sub→remote→xmitq→channel→远程QMGR→alias→local），
 * 含 REMOTE_QMGR 虚拟节点与环（BACK 边）标橙。宿主路由
 * （[com.mqclient.ibmmq.ui.IbmMqUI].contentFor("topology")）指向本面板。
 *
 * 数据自给，**缓存优先**：
 *  1. 进入面板只读慢扫缓存出图，不触发扫描（后台既有的定时扫描照常经事件刷新图）；
 *  2. 缓存为空才继续：已连接 → 触发一次对象慢扫；未连接 → [IbmMQModule.connectAsync]
 *     隐式连接（与侧栏点击连接同一入口），CONNECTED 事件到达后再扫；
 *  3. OBJECTS_SCANNED → 从缓存重建图。
 * 加载态保持到**首个非空图**（与列表页「不提交空态」同语义）；之后扫描空结果不覆盖旧图。
 * 强制刷新走右上角「重新扫描」按钮。
 *
 * 焦点：列表页「在拓扑图中查看」经 [TopologyFocusHolder] 带入对象名，出图后选中该节点
 * （图内高亮 + 底部详情卡）——全量固定列图本身一屏可见，无需再按跳数裁剪。
 *
 * 画布组装（kuiver viewer / 选中高亮 / 流动动画 / 缩放控件 / 详情卡）已泛化为
 * topology-ui 的 [TopologyCanvas]，本面板只提供数据与业务行为（双击开属性对话框）。
 */
@Composable
fun TopologyPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    // connectionId 可能为空白：残留 Tab、「在拓扑图中查看」请求未带连接、或路由时序错位都会
    // 让面板拿到空 id。空白时从全局 ConnectionStore 取当前 IBM MQ 连接兜底，避免面板直接空状态 /
    // 无限转圈。这是「打开拓扑图却显示『请选择连接』」的根因：空 id 下 buildKuiverFromCache
    // 查不到任何缓存，data 永远为 null，既不报错也不加载。
    val effectiveId = remember(connectionId) {
        if (connectionId.isNotBlank()) {
            connectionId
        } else {
            ConnectionStore.connections.value
                .firstOrNull { it.serviceId == "ibm-mq" && it.status == ConnectionStatus.Connected }
                ?.id
                ?: ConnectionStore.connections.value
                    .firstOrNull { it.serviceId == "ibm-mq" }
                    ?.id
                ?: ""
        }
    }
    val hasConnection = effectiveId.isNotBlank()

    // 队列深度告警阈值（%）：与队列列表页同源（连接配置 queueDepthWarningThreshold，默认 85）。
    // 响应式读取：组合期直接读 StateFlow.value，编辑连接阈值后面板自动刷新。
    val warningThreshold = ConnectionStore.connections.value
        .firstOrNull { it.id == effectiveId }
        ?.let { ibmMQConfigFromOptions(it.options).queueDepthWarningThreshold }
        ?: IbmMQConfig.DEFAULT_QUEUE_DEPTH_WARNING_THRESHOLD

    var data by remember(effectiveId) { mutableStateOf<TopologyData?>(null) }
    // 至少完成过一轮慢扫：区分「还在连 / 扫」与「扫完了但确实没有业务对象」。
    var scannedOnce by remember(effectiveId) { mutableStateOf(false) }
    // 列表页带入的焦点对象名：在 LaunchedEffect（副作用上下文）里 consume——
    // 放在 remember 计算块的话，组合被丢弃/重试时焦点会被消费掉却没应用到图上。
    var focusName by remember(effectiveId) { mutableStateOf<String?>(null) }

    /** 从慢扫缓存重建；**空图不落地**——保持加载态或保留旧图（刷新保留旧数据的语义）。 */
    fun rebuild() {
        val next = buildKuiverFromCache(effectiveId, warningThreshold)
        if (next.kuiver.nodes.isNotEmpty()) {
            data = next
        } else {
            Log.debug(TOPOLOGY_LOG_TAG, "topology rebuild skipped (empty graph): connection=$effectiveId")
        }
    }

    /** 强制刷新：显式触发一次对象慢扫（扫完经 OBJECTS_SCANNED 驱动重建）。 */
    val scope = rememberCoroutineScope()
    fun rescan() {
        Log.debug(TOPOLOGY_LOG_TAG, "topology rescan requested: connection=$effectiveId")
        scope.launch {
            runCatchingCancellable { IbmMQModule.serviceOrNull()?.scanObjects(effectiveId) }
                .onFailure {
                    Log.warn(
                        TOPOLOGY_LOG_TAG,
                        "topology rescan failed: connection=$effectiveId error=${it.message ?: it::class.simpleName}",
                    )
                }
        }
    }

    // 订阅先于动作（防丢事件）；on(scope = this) 随 LaunchedEffect 离开自动取消。
    LaunchedEffect(effectiveId) {
        // 消费焦点也放进副作用上下文（与数据流同处，时序上早于 rebuild 即可）。
        focusName = TopologyFocusHolder.consume(effectiveId)?.rootName
        val bus = CoreModule.eventBusOrNull
        bus?.on(IbmMQEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == effectiveId) {
                scannedOnce = true
                rebuild()
            }
        }
        // 隐式连接建立 → 仅当还没有图（缓存为空）时补一拍慢扫；热缓存不打扰。
        bus?.on(IbmMQEvents.CONNECTED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == effectiveId && data == null) {
                runCatchingCancellable { IbmMQModule.serviceOrNull()?.scanObjects(effectiveId) }
                    .onFailure {
                        Log.warn(
                            TOPOLOGY_LOG_TAG,
                            "topology initial scan (after connect) failed: connection=$effectiveId " +
                                "error=${it.message ?: it::class.simpleName}",
                        )
                    }
            }
        }

        // 缓存优先：只读缓存出图；缓存为空才继续连 / 扫。
        rebuild()
        if (data == null) {
            if (IbmMQModule.connectionOrNull(effectiveId)?.status == ConnectionStatus.Connected) {
                runCatchingCancellable { IbmMQModule.serviceOrNull()?.scanObjects(effectiveId) }
                    .onFailure {
                        Log.warn(
                            TOPOLOGY_LOG_TAG,
                            "topology initial scan failed: connection=$effectiveId " +
                                "error=${it.message ?: it::class.simpleName}",
                        )
                    }
            } else {
                // 未连接：隐式连接（fire-and-forget），连上后由上方 CONNECTED 分支接管扫描。
                Log.debug(TOPOLOGY_LOG_TAG, "topology implicit connect requested: connection=$effectiveId")
                IbmMQModule.connectAsync(effectiveId)
            }
        }
    }

    // 没有可用连接：明确告知用户「请选择连接」，而非无限转圈 / 误报空数据。
    if (!hasConnection) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("ibm_mq_page_placeholder"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val current = data
    if (current == null && !scannedOnce) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    text = stringResource("ibm_mq_topology_loading"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        return
    }
    if (current == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("ibm_mq_topology_empty"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    MqTopologyContent(
        connectionId = effectiveId,
        data = current,
        focusName = focusName,
        onFocusApplied = { focusName = null },
        onRescan = ::rescan,
        modifier = modifier,
    )
}

/**
 * 业务行为装配：双击节点开属性对话框（无属性的虚拟节点 Snackbar 提示），数据交给
 * 通用 [TopologyCanvas] 渲染。列布局 provider 见 [columnLayoutProvider]。
 */
@Composable
private fun MqTopologyContent(
    connectionId: String,
    data: TopologyData,
    focusName: String?,
    onFocusApplied: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nodeTypes = data.nodeTypes

    // 对象属性对话框：**双击节点**打开。打开走统一的 rememberObjectPropertiesDialogController；
    // 无属性可看的节点（rqmgr: 虚拟节点等）弹 Snackbar 提示而非静默无反应。
    // 回调经 rememberUpdatedState 读**最新** nodeTypes——此前直接捕获首帧 map，
    // OBJECTS_SCANNED 重建图后新增的节点双击会静默失效。
    val propertiesController = rememberObjectPropertiesDialogController(connectionId)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val virtualNodeHint = stringResource("ibm_mq_topology_no_properties")
    val currentTypes by rememberUpdatedState(nodeTypes)

    TopologyCanvas(
        data = data,
        layoutProvider = columnLayoutProvider,
        // 节点可拖拽（与 Kafka 拓扑同步启用）：固定列仍是默认布局，拖动只是会话内
        // 覆盖（kuiver manualPositions，内存态），切走 / 关闭 Tab 即恢复默认布局。
        nodeDragEnabled = true,
        onRescan = onRescan,
        onNodeDoubleClick = { nodeId ->
            val type = currentTypes[nodeId]?.toMqObjectType()
            if (type != null) {
                propertiesController.open(type, nodeId)
            } else {
                scope.launch { snackbarHostState.showSnackbar(virtualNodeHint) }
            }
        },
        modifier = modifier,
        // REMOTE_QMGR 虚拟节点 id 带 `rqmgr:` 前缀（避免与真实对象重名），显示时剥掉。
        displayName = { it.removePrefix(REMOTE_QMGR_PREFIX) },
        fallbackType = TopologyNodeType.LOCAL_QUEUE,
        focusName = focusName,
        onFocusApplied = onFocusApplied,
        snackbarHostState = snackbarHostState,
    )

    ObjectPropertiesDialogHost(propertiesController)
}
