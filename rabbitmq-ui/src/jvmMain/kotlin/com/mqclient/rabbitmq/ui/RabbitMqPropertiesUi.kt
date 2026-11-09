package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionPropertiesUi
import com.mqclient.core.CoreModule
import com.mqclient.rabbitmq.RabbitMqClusterInfoSnapshot
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.RabbitMqNodeSnapshot
import com.mqclient.rabbitmq.RabbitMqEvents
import com.mqclient.uicommon.dialog.MessageDetailSectionTitle
import com.mqclient.uicommon.PropertyItem
import com.mqclient.uicommon.PropertyTableSection
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * RabbitMQ 的连接属性面板贡献（SPI，见 [ConnectionPropertiesUi]）：展示 **broker
 * 本身**的属性（RabbitMQ 版本 / 管理插件版本 / 集群名 / 初始节点）与集群节点列表，
 * 不是连接配置。
 *
 * 数据源：慢扫缓存（[RabbitMqModule.serviceOrNull] 的 cachedClusterInfo /
 * cachedNodes 只读口）—— 打开时缓存为空则触发一轮 scanNow 并轮询等待（最多 8s，
 * 超时给错误 + 重试）；就绪后订阅 OBJECTS_SCANNED 事件随慢扫自动刷新。加载 /
 * 错误 / 重试是面板自己的状态（与 IBM 的 QmgrPropertiesPanel 同一模式）。
 */
class RabbitMqPropertiesUi : ConnectionPropertiesUi {
    override val serviceId = "rabbitmq"

    @Composable
    override fun Content(connection: ConnectionInfo) {
        RabbitMqConnectionPropertiesPanel(connectionId = connection.id)
    }
}

@Composable
internal fun RabbitMqConnectionPropertiesPanel(connectionId: String) {
    var clusterInfo by remember(connectionId) { mutableStateOf<RabbitMqClusterInfoSnapshot?>(null) }
    var nodes by remember(connectionId) { mutableStateOf<List<RabbitMqNodeSnapshot>>(emptyList()) }
    var loading by remember(connectionId) { mutableStateOf(true) }
    var error by remember(connectionId) { mutableStateOf<String?>(null) }
    var retryKey by remember(connectionId) { mutableStateOf(0) }

    // 冷缓存兜底：触发一轮 scanNow 并轮询缓存（最多 8s）；就绪后随慢扫事件自动刷新。
    LaunchedEffect(connectionId, retryKey) {
        loading = true
        error = null
        val service = RabbitMqModule.serviceOrNull()
        if (service == null) {
            error = stringResource("rabbitmq_ui_conn_props_no_service")
        } else {
            try {
                withTimeout(8_000) {
                    if (service.cachedClusterInfo(connectionId) == null ||
                        service.cachedNodes(connectionId).isEmpty()
                    ) {
                        RabbitMqModule.scanNow(connectionId)
                    }
                    while (service.cachedClusterInfo(connectionId) == null ||
                        service.cachedNodes(connectionId).isEmpty()
                    ) {
                        delay(400)
                    }
                }
                clusterInfo = service.cachedClusterInfo(connectionId)
                nodes = service.cachedNodes(connectionId)
            } catch (e: TimeoutCancellationException) {
                error = stringResource("rabbitmq_ui_conn_props_timeout")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                error = t.message ?: t::class.simpleName ?: "unknown"
            }
        }
        loading = false
    }

    // 慢扫完成事件 → 重读缓存（面板存续期间实时刷新）。
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RabbitMqEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) {
                val service = RabbitMqModule.serviceOrNull() ?: return@on
                clusterInfo = service.cachedClusterInfo(connectionId)
                nodes = service.cachedNodes(connectionId)
            }
        }
    }

    when {
        loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        error != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource("rabbitmq_ui_refresh"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable { retryKey++ }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        else -> ReadyPane(clusterInfo = clusterInfo, nodes = nodes)
    }
}

@Composable
private fun ReadyPane(clusterInfo: RabbitMqClusterInfoSnapshot?, nodes: List<RabbitMqNodeSnapshot>) {
    // 宽度由宿主（ConnectionPropertiesDialog）统一夹到 min(W, max(0.8W, 900dp)) 并居中，
    // 与队列属性对话框（ObjectDetailDialog）同一规范 —— 面板内**不再二次限宽**（否则
    // 会被夹到 900dp，比宿主内容区窄出一截），Broker 表与集群节点行天然同宽。
    // 滚动容器保持满宽（滚动条 / 命中区域）；左右 20dp 边距与队列属性对话框一致。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Column {
            PropertyTableSection(
                maxWidth = Dp.Infinity,
                title = stringResource("rabbitmq_ui_conn_props_broker"),
                items = listOf(
                    PropertyItem(stringResource("rabbitmq_ui_conn_props_version"), clusterInfo?.rabbitmqVersion),
                    PropertyItem(stringResource("rabbitmq_ui_conn_props_mgmt_version"), null, copyable = false),
                    PropertyItem(stringResource("rabbitmq_ui_conn_props_cluster_name"), clusterInfo?.clusterName),
                    PropertyItem(stringResource("rabbitmq_ui_conn_props_initial_node"), clusterInfo?.node),
                ),
            )
            Spacer(Modifier.height(12.dp))
            MessageDetailSectionTitle(
                text = stringResource("rabbitmq_ui_conn_props_nodes").withArg(nodes.size),
            )
            Spacer(Modifier.height(4.dp))
            nodes.forEachIndexed { index, node ->
                NodeRow(node, index)
            }
            if (nodes.isEmpty()) {
                Text(
                    text = stringResource("rabbitmq_ui_conn_props_no_nodes"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 节点行：状态色点（未运行红 / 告警橙 / 健康绿）+ 名称 + 类型 · 状态 · 运行时长 · 内存。 */
@Composable
private fun NodeRow(node: RabbitMqNodeSnapshot, index: Int) {
    val dotColor = when {
        !node.running -> RabbitMqDangerRed
        node.memAlarm || node.diskFreeAlarm -> RabbitMqWarningOrange
        else -> RabbitMqRunningGreen
    }
    val statusText = when {
        !node.running -> stringResource("rabbitmq_ui_conn_props_node_offline")
        node.memAlarm || node.diskFreeAlarm -> stringResource("rabbitmq_ui_overview_node_alarm")
        else -> stringResource("rabbitmq_ui_overview_node_status_ok")
    }
    val zebra = index % 2 == 1
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (zebra) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(8.dp))
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = listOf(node.type, statusText, formatUptime(node.uptime), formatMem(node)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(300.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 毫秒运行时长 → "3d 4h 5m"（天 / 小时为 0 时省略对应段）。 */
private fun formatUptime(ms: Long): String {
    val minutes = ms / 60_000
    val d = minutes / 1_440
    val h = (minutes % 1_440) / 60
    val m = minutes % 60
    return buildString {
        if (d > 0) append(d).append("d ")
        if (h > 0 || d > 0) append(h).append("h ")
        append(m).append("m")
    }.trim()
}

/** 内存占用 → "1.2G / 4.0G"（limit 未知只显示占用）。 */
private fun formatMem(node: RabbitMqNodeSnapshot): String {
    fun gb(b: Long) = String.format(java.util.Locale.ROOT, "%.1fG", b / (1024.0 * 1024.0 * 1024.0))
    return if (node.memLimit > 0) gb(node.memUsed) + " / " + gb(node.memLimit) else gb(node.memUsed)
}
