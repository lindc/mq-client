package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.starrocks.cluster.ClusterNodeInfo
import com.mqclient.starrocks.cluster.ClusterNodeRole
import com.mqclient.starrocks.cluster.StarRocksClusterService
import com.mqclient.uicommon.PropertyItem
import com.mqclient.uicommon.PropertyTableSection
import com.mqclient.uicommon.dialog.DialogSizeClass
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 节点属性对话框的展示状态。
 *
 * [refreshing] / [stale] 是"先用快照秒开、再后台刷新"的两态：
 *  - 打开瞬间用拓扑快照里的数据（零等待）；
 *  - 后台调 [StarRocksClusterService.loadNode] 刷新该节点；
 *  - 刷新失败（节点刚被摘掉 / 权限变化）→ [stale] = true，面板给一行"信息可能已过期"，
 *    **不弹错误框**（看属性看到一半弹红框是纯粹的骚扰）。
 */
data class StarRocksNodeDetailState(
    val node: ClusterNodeInfo,
    val refreshing: Boolean = false,
    val stale: Boolean = false,
)

/**
 * 「打开节点属性对话框」的统一入口（与 IBM MQ 的 `ObjectPropertiesDialogController`
 * 同构）：拓扑双击只调 [open]，对话框内容由 [StarRocksClusterService] 提供。
 */
@Stable
class StarRocksNodeDetailController(private val connectionId: String) {

    var state by mutableStateOf<StarRocksNodeDetailState?>(null)
        private set

    /** 打开属性对话框：立刻用快照数据出窗口，再后台刷新这一个节点。 */
    fun open(node: ClusterNodeInfo, scope: CoroutineScope) {
        state = StarRocksNodeDetailState(node = node, refreshing = true)
        scope.launch {
            val fresh = runCatching {
                StarRocksClusterService.loadNode(connectionId, node.nodeId)
            }.getOrNull()
            // 期间用户可能又双击了别的节点：只更新同一个 nodeId，避免串台。
            val current = state?.takeIf { it.node.nodeId == node.nodeId } ?: return@launch
            state = current.copy(
                node = fresh ?: current.node,
                refreshing = false,
                stale = fresh == null,
            )
        }
    }

    fun close() {
        state = null
    }
}

/** 按连接创建并记住控制器（连接 id 变化即重建，状态不跨连接泄漏）。 */
@Composable
fun rememberStarRocksNodeDetailController(connectionId: String): StarRocksNodeDetailController =
    remember(connectionId) { StarRocksNodeDetailController(connectionId) }

/**
 * 节点属性对话框：复用 ui-common 的 [ObjectDetailDialog] 外壳（nucleus 自绘标题栏 +
 * ESC + 统一尺寸规范），内容按 [ClusterNodeRole] 分三节：
 * 基本信息（类型 / IP / 各端口）、资源使用（仅 BE / CN）、状态与心跳。
 *
 * 字段一律"有值才渲染"（Service 层字段可空，缺列不补假 0）。
 */
@Composable
fun StarRocksNodeDetailDialog(
    state: StarRocksNodeDetailState,
    onDismiss: () -> Unit,
) {
    val node = state.node
    ObjectDetailDialog(
        title = node.name,
        onDismiss = onDismiss,
        sizeClass = DialogSizeClass.CONTENT,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 刷新态提示：一行灰字，不抢内容。
            if (state.refreshing || state.stale) {
                Text(
                    text = stringResource(
                        if (state.refreshing) "olap.starrocks.node.refreshing"
                        else "olap.starrocks.node.stale",
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PropertyTableSection(
                title = stringResource("olap.starrocks.node.section_basic"),
                items = buildList {
                    add(PropertyItem(stringResource("olap.starrocks.node.type"), node.role.displayName()))
                    node.extra["role"]?.let {
                        add(PropertyItem(stringResource("olap.starrocks.node.role"), it))
                    }
                    if (node.ip.isNotBlank()) add(PropertyItem("IP", node.ip))
                    // 端口按协议原名逐行展示（heartbeat_port / be_port / query_port…）。
                    node.ports.forEach { add(PropertyItem(it.label, it.port)) }
                    add(
                        PropertyItem(
                            stringResource("olap.starrocks.node.alive"),
                            stringResource(
                                if (node.alive) "olap.starrocks.overview.alive"
                                else "olap.starrocks.overview.dead",
                            ),
                        ),
                    )
                    add(PropertyItem(stringResource("olap.starrocks.node.version"), node.version))
                    // Catalog 专属：类型（hive / iceberg…）与注释。
                    node.extra["type"]?.let {
                        add(PropertyItem(stringResource("olap.starrocks.node.catalog_type"), it))
                    }
                    node.extra["comment"]?.let {
                        add(PropertyItem(stringResource("olap.starrocks.node.catalog_comment"), it))
                    }
                },
            )

            // 资源分组只对执行层（BE / CN）有意义：FE 没有盘、Catalog 什么都没有。
            if (node.role.isCompute) {
                PropertyTableSection(
                    title = stringResource("olap.starrocks.node.section_resources"),
                    items = buildList {
                        node.cpuCores?.let {
                            add(PropertyItem(stringResource("olap.starrocks.node.cpu_cores"), it.toString()))
                        }
                        node.cpuUsedPercent?.let {
                            add(PropertyItem(stringResource("olap.starrocks.node.cpu_used"), formatPercent(it)))
                        }
                        node.memUsedPercent?.let {
                            add(PropertyItem(stringResource("olap.starrocks.node.mem_used"), formatPercent(it)))
                        }
                        // 内存上限 / 已用只有 SHOW PROC '/backends' 给到（拿不到就不渲染）。
                        node.memLimitBytes?.let {
                            add(PropertyItem(stringResource("olap.starrocks.node.mem_limit"), formatBytes(it)))
                        }
                        node.memUsedBytes?.let {
                            add(PropertyItem(stringResource("olap.starrocks.node.mem_used_bytes"), formatBytes(it)))
                        }
                        node.diskTotalBytes?.let {
                            add(PropertyItem(stringResource("olap.starrocks.node.disk_total"), formatBytes(it)))
                        }
                        node.diskUsedBytes?.let {
                            add(PropertyItem(stringResource("olap.starrocks.node.disk_used"), formatBytes(it)))
                        }
                        node.diskUsedPercent?.let {
                            add(PropertyItem(stringResource("olap.starrocks.node.disk_used_pct"), formatPercent(it)))
                        }
                        node.tabletCount?.let {
                            add(PropertyItem(stringResource("olap.starrocks.node.tablets"), it.toString()))
                        }
                        node.runningQueries?.let {
                            add(PropertyItem(stringResource("olap.starrocks.node.running_queries"), it.toString()))
                        }
                    },
                )
            }

            PropertyTableSection(
                title = stringResource("olap.starrocks.node.section_state"),
                items = buildList {
                    node.status?.let {
                        add(PropertyItem(stringResource("olap.starrocks.node.status"), it))
                    }
                    node.lastStartTime?.let {
                        add(PropertyItem(stringResource("olap.starrocks.node.last_start"), it))
                    }
                    node.lastHeartbeat?.let {
                        add(PropertyItem(stringResource("olap.starrocks.node.last_heartbeat"), it))
                    }
                    node.joinTime?.let {
                        add(PropertyItem(stringResource("olap.starrocks.node.join_time"), it))
                    }
                    node.errorMessage?.let {
                        add(PropertyItem(stringResource("olap.starrocks.node.error"), it))
                    }
                }.ifEmpty {
                    // 全都没有（老版本没有这些列）时给一行占位，避免空小节。
                    listOf(PropertyItem(stringResource("olap.starrocks.node.status"), null))
                },
            )
        }
    }
}

/** 角色 → i18n 展示名（穷尽 when：新增角色不配文案即编译错误）。 */
@Composable
internal fun ClusterNodeRole.displayName(): String = stringResource(
    when (this) {
        ClusterNodeRole.FE_LEADER -> "olap.starrocks.node.role_leader"
        ClusterNodeRole.FE_FOLLOWER -> "olap.starrocks.node.role_follower"
        ClusterNodeRole.FE_OBSERVER -> "olap.starrocks.node.role_observer"
        ClusterNodeRole.BE -> "olap.starrocks.node.role_be"
        ClusterNodeRole.CN -> "olap.starrocks.node.role_cn"
        ClusterNodeRole.CATALOG -> "olap.starrocks.node.role_catalog"
    },
)
