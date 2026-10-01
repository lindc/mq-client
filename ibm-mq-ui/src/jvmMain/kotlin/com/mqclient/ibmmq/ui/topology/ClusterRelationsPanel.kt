@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.mqclient.ibmmq.ui.topology

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dk.kuiver.SelectionMode
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import com.dk.kuiver.model.buildKuiver
import com.dk.kuiver.model.buildKuiverWithClassifiedEdges
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.rememberKuiverViewerState
import com.dk.kuiver.renderer.KuiverInteractionCallbacks
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.event.IbmMQEvents
import com.mqclient.ibmmq.messaging.browseErrorText
import com.mqclient.ibmmq.pcf.ClusterMember
import com.mqclient.ibmmq.pcf.ClusterRelations
import com.mqclient.ibmmq.pcf.QmgrClusterRole
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** 集群关系面板的三态：加载中 / 失败（带原因，可重试）/ 就绪。 */
private sealed interface ClusterUiState {
    data object Loading : ClusterUiState
    data class Failed(val reason: String) : ClusterUiState
    data class Ready(val relations: ClusterRelations) : ClusterUiState
}

/**
 * 集群关系面板（导航「集群关系」Tab，与拓扑图平级、独立查询路径）：本地 QMgr 的
 * 集群角色（完整仓库 / 部分仓库 / 不属于集群）+ 成员拓扑图 + 成员列表。
 *
 * 与拓扑图的分工：拓扑图是**缓存优先**的全量消息流固定列图；本面板是**实时查询**
 * （DISPLAY CLUSQMGR，成员关系要新鲜数据），且只画集群视角（本地 QMgr → 各成员）。
 *
 * 布局（纵向）：角色提示条 → Kuiver 画布（weight 2，右上角缩放控件）→ 成员表
 * （weight 1，行点击联动画布选中高亮）。
 */
@Composable
fun ClusterRelationsPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    var uiState by remember(connectionId) { mutableStateOf<ClusterUiState>(ClusterUiState.Loading) }
    var retryKey by remember(connectionId) { mutableStateOf(0) }
    var selectedId by remember(connectionId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            uiState = ClusterUiState.Loading
            uiState = try {
                val relations = IbmMQModule.serviceOrNull()?.getClusterRelations(connectionId)
                    ?: throw IllegalStateException("服务未初始化")
                ClusterUiState.Ready(relations)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ClusterUiState.Failed(browseErrorText(e))
            }
        }
    }

    LaunchedEffect(connectionId, retryKey) {
        // 未连接：隐式连接（与侧栏点击同一入口），连上后由 CONNECTED 分支接管查询。
        if (IbmMQModule.connectionOrNull(connectionId)?.status != ConnectionStatus.Connected) {
            uiState = ClusterUiState.Loading
            IbmMQModule.connectAsync(connectionId)
            val bus = CoreModule.eventBusOrNull
            bus?.on(IbmMQEvents.CONNECTED, scope = this) { event ->
                if (event.payload["connectionId"] as? String == connectionId) load()
            }
        } else {
            load()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (val state = uiState) {
            is ClusterUiState.Loading -> ClusterLoadingView(Modifier.weight(1f))
            is ClusterUiState.Failed -> ClusterErrorView(
                reason = state.reason,
                onRetry = { retryKey++ },
                modifier = Modifier.weight(1f),
            )
            is ClusterUiState.Ready -> ClusterReadyPane(
                relations = state.relations,
                selectedId = selectedId,
                onSelect = { selectedId = it },
                onRescan = { retryKey++ },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 就绪态：角色提示条 + 拓扑画布（weight 2）+ 成员列表（weight 1）。 */
@Composable
private fun ClusterReadyPane(
    relations: ClusterRelations,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roleText = when (relations.role) {
        QmgrClusterRole.FULL -> stringResource("cluster_relations_role_full")
        QmgrClusterRole.PARTIAL -> stringResource("cluster_relations_role_partial")
        QmgrClusterRole.NONE -> stringResource("cluster_relations_none")
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---- 角色提示条：左侧标题，右侧角色说明（轻量样式，不抢主内容）----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource("cluster_relations_title").withArg(relations.localQmgrName),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = roleText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()

        when (relations.role) {
            // 不属于集群：到此为止，不再往下渲染。
            QmgrClusterRole.NONE -> Box(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource("cluster_relations_none"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                // ---- 集群拓扑画布（weight 2）----
                Box(modifier = Modifier.weight(2f).fillMaxSize()) {
                    ClusterCanvas(
                        relations = relations,
                        selectedId = selectedId,
                        onSelect = onSelect,
                        onRescan = onRescan,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                HorizontalDivider()
                // ---- 成员列表（weight 1）：行点击联动画布选中 ----
                MemberTable(
                    relations = relations,
                    selectedId = selectedId,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
    }
}

/** 就绪态的集群画布：kuiver 图（本地 → 各成员），右上角缩放控件复用。 */
@Composable
private fun ClusterCanvas(
    relations: ClusterRelations,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val graph = remember(relations) { buildClusterGraph(relations) }
    val (kuiver, nodeTypes, severities) = graph
    // 成员 → 边信息：按 defType 实/虚线、status 着色（本地自身行不产生边）。
    val edgeInfo = remember(relations) {
        relations.members
            .filter { it.qmgrName != relations.localQmgrName }
            .associate { member ->
                (relations.localQmgrName to member.qmgrName) to member
            }
    }

    val viewerState = rememberKuiverViewerState(
        initialKuiver = Kuiver(),
        layoutConfig = LayoutConfig.Custom(provider = clusterLayoutProvider),
    )

    var pendingFit by remember { mutableStateOf(false) }
    LaunchedEffect(kuiver) {
        viewerState.updateKuiver(kuiver)
        viewerState.relayout()
        pendingFit = true
    }
    // 行点击 / 画布点击共用同一选中态（面板层持有）。
    LaunchedEffect(selectedId) {
        if (selectedId != null && kuiver.nodes.containsKey(selectedId)) {
            viewerState.interaction.select(selectedId)
        } else {
            viewerState.interaction.clearSelection()
        }
    }
    LaunchedEffect(viewerState.layoutedKuiver, viewerState.canvasWidth) {
        if (viewerState.canvasWidth > 0.dp && viewerState.layoutedKuiver.nodes.isNotEmpty() && pendingFit) {
            viewerState.centerGraph(animated = true)
            pendingFit = false
        }
    }

    Box(modifier.fillMaxSize()) {
        KuiverViewer(
            state = viewerState,
            modifier = Modifier.fillMaxSize(),
            config = KuiverViewerConfig(
                hoverEnabled = true,
                selectionMode = SelectionMode.NONE,
                nodeDragEnabled = false,
                minScale = 0.2f,
                maxScale = 2.0f,
                fitToContent = false,
                contentPadding = 0.9f,
            ),
            callbacks = remember {
                KuiverInteractionCallbacks(
                    onNodeClick = { node -> onSelect(node.id) },
                    onCanvasClick = { onSelect(null) },
                )
            },
            nodeContent = { node ->
                val type = nodeTypes[node.id] ?: TopologyNodeType.QMGR_PARTIAL
                TopologyNodeCard(
                    // 本地节点 id 带 "local:" 前缀（与成员节点区分），显示时剥掉。
                    name = node.id.removePrefix(CLUSTER_LOCAL_PREFIX),
                    type = type,
                    isHovered = isHovered,
                    isSelected = isSelected || node.id == selectedId,
                    severity = severities[node.id] ?: NodeSeverity.NORMAL,
                )
            },
            // 边样式：实线 = 手动 CLUSSDR / 虚线 = 自动 CLUSSDRA；颜色按通道状态
            // RUNNING → 主题灰、RETRYING → 警告橙、STOPPED → 错误红。
            edgeStyle = { edge ->
                val member = edgeInfo[edge.fromId to edge.toId]
                val color = when (member?.status?.uppercase()) {
                    "RETRYING" -> Color(0xFFED6C02)
                    "STOPPED" -> scheme.error
                    else -> scheme.outlineVariant
                }
                val base = topologyEdgeStyle(
                    edge = edge,
                    normalColor = color,
                    cycleColor = color,
                )
                // 虚线 = 自动定义通道（CLUSSDRA），实线 = 手动 CLUSSDR。
                if (member?.defType == "CLUSSDRA") {
                    base.copy(dashed = true, dashLength = 8.dp, gapLength = 6.dp)
                } else {
                    base
                }
            },
        )

        TopologyZoomControls(
            onRescan = onRescan,
            onZoomIn = { viewerState.zoomIn() },
            onZoomOut = { viewerState.zoomOut() },
            onFitContent = { viewerState.centerGraph(animated = true) },
            onReset = {
                viewerState.clearManualPositions()
                viewerState.centerGraph(animated = true)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        )
    }
}

/** 成员表：6 列（队列管理器 / 所属集群 / 通道名 / 通道类型 / 仓库类型 / 状态）。 */
@Composable
private fun MemberTable(
    relations: ClusterRelations,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource("cluster_relations_members"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (relations.members.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource("cluster_relations_empty"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        // 表头
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            MemberCell(stringResource("cluster_relations_col_qmgr"), COL_QMGR, bold = true)
            MemberCell(stringResource("cluster_relations_col_cluster"), COL_CLUSTER, bold = true)
            MemberCell(stringResource("cluster_relations_col_channel"), COL_CHANNEL, bold = true)
            MemberCell(stringResource("cluster_relations_col_deftype"), COL_DEFTYPE, bold = true)
            MemberCell(stringResource("cluster_relations_col_qmtype"), COL_QMTYPE, bold = true)
            MemberCell(stringResource("cluster_relations_col_status"), COL_STATUS, bold = true)
        }
        HorizontalDivider()
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            relations.members.forEach { member ->
                val selected = member.qmgrName == selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        )
                        .clickable { onSelect(member.qmgrName) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    MemberCell(member.qmgrName, COL_QMGR)
                    MemberCell(member.clusterName, COL_CLUSTER)
                    MemberCell(member.channelName.ifBlank { "-" }, COL_CHANNEL)
                    MemberCell(clusterDefTypeLabel(member.defType), COL_DEFTYPE)
                    MemberCell(clusterQmTypeLabel(member.qmType), COL_QMTYPE)
                    MemberCell(
                        clusterStatusLabel(member.status),
                        COL_STATUS,
                        color = clusterStatusColor(member.status),
                    )
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun MemberCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    bold: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        color = color,
        modifier = Modifier.width(width),
    )
}

/** 加载中：居中转圈 + 提示。 */
@Composable
private fun ClusterLoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource("cluster_relations_loading"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 加载失败：原因（MQRC 翻译）+ 重试。 */
@Composable
private fun ClusterErrorView(reason: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource("cluster_relations_failed").withArg(reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Button(onClick = onRetry) {
                Text(stringResource("cluster_relations_retry"))
            }
        }
    }
}

/** 成员表列宽（6 列定宽，同 MessageTable 的固定列风格）。 */
private val COL_QMGR = 150.dp
private val COL_CLUSTER = 110.dp
private val COL_CHANNEL = 150.dp
private val COL_DEFTYPE = 90.dp
private val COL_QMTYPE = 80.dp
private val COL_STATUS = 90.dp

/** 集群图布局：本地 QMgr 在第 0 列，成员在第 1 列（横向星形）。 */
private val clusterLayoutProvider: com.dk.kuiver.model.layout.LayoutProvider =
    labeled@{ kuiver: Kuiver, config: com.dk.kuiver.model.layout.LayoutConfig ->
        val nodes = kuiver.nodes.values.toList()
        if (nodes.isEmpty()) return@labeled kuiver
        val positions = mutableMapOf<String, androidx.compose.ui.unit.DpOffset>()
        var y = 0f
        nodes.sortedBy { it.id }.forEach { n ->
            val col = if (n.id.startsWith(CLUSTER_LOCAL_PREFIX)) 0 else 1
            positions[n.id] = androidx.compose.ui.unit.DpOffset(
                CLUSTER_COLUMN_SPACING * col,
                androidx.compose.ui.unit.Dp(y),
            )
            y += if (col == 0) 0f else (n.dimensions?.height ?: CARD_HEIGHT.dp).value + CLUSTER_ROW_SPACING.value
        }
        val laidNodes = nodes.map { it.copy(position = positions.getValue(it.id)) }
        buildKuiverWithClassifiedEdges(laidNodes, kuiver.edges.toList())
    }

private const val CLUSTER_LOCAL_PREFIX = "local:"
private val CLUSTER_COLUMN_SPACING = 320.dp
private val CLUSTER_ROW_SPACING = 40.dp

/** 集群图构建：本地 QMGR + 每个成员一个节点（REPOS / NORMAL 不同类型色）+ 本地→成员边。 */
internal fun buildClusterGraph(relations: ClusterRelations): MqTopologyData {
    val localType = if (relations.role == QmgrClusterRole.FULL) {
        TopologyNodeType.QMGR_FULL
    } else {
        TopologyNodeType.QMGR_PARTIAL
    }
    val localId = CLUSTER_LOCAL_PREFIX + relations.localQmgrName

    val nodeTypes = linkedMapOf(localId to localType)
    val severities = linkedMapOf<String, NodeSeverity>(localId to NodeSeverity.NORMAL)
    // CLUSQMGR 结果含本地 QMgr 自身（其行描述自己的 CLUSRCVR）：图里已有本地节点，
    // 跳过，避免重复节点 + 自环边；成员表仍保留该行。
    val remoteMembers = relations.members.filter { it.qmgrName != relations.localQmgrName }
    remoteMembers.forEach { member ->
        val type = if (member.qmType == "REPOS") TopologyNodeType.QMGR_FULL else TopologyNodeType.QMGR_PARTIAL
        nodeTypes.putIfAbsent(member.qmgrName, type)
        severities.putIfAbsent(member.qmgrName, NodeSeverity.NORMAL)
    }

    val kuiver = buildKuiver {
        nodeTypes.keys.forEach { id ->
            addNode(KuiverNode(id, NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp)))
        }
        remoteMembers.forEach { member ->
            addEdge(
                KuiverEdge(
                    fromId = localId,
                    toId = member.qmgrName,
                ),
            )
        }
    }
    return MqTopologyData(kuiver, nodeTypes, severities)
}
