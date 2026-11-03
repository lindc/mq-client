@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.CoreModule
import com.mqclient.core.event.EventNames
import com.mqclient.mqui.metric.MetricCard
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.rabbitmq.RabbitMqEvents
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.RabbitMqNodeSnapshot
import com.mqclient.uicommon.AppTooltip
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import java.util.Locale

/** 风险队列的积压阈值（条）：有消费者但积压超限 → 健康度 Banner 风险告警项。 */
internal const val BACKLOG_THRESHOLD = 1_000L

/** 项目既定的语义告警橙（与 Kafka lag 染色同一约定，不随主题翻转）。 */
private val WARNING_ORANGE = RabbitMqWarningOrange

/** 项目既定的语义健康绿（与 Kafka Controller 徽章同一约定，不随主题翻转）。 */
private val HEALTH_GREEN = RabbitMqRunningGreen

/** 项目既定的语义异常红（节点未运行，与上面两个同一套约定，不随主题翻转）。 */
private val NODE_DOWN_RED = RabbitMqDangerRed

/** 一次缓存汇总的结果（纯数据）；null = 数据未到齐（卡片 "-" 占位 + Banner 加载态）。 */
data class RabbitMqOverviewSummary(
    val queueCount: Int,
    val exchangeCount: Int,
    val totalMessages: Long,
    val messagesReady: Long,
    val messagesUnacknowledged: Long,
    val totalConsumers: Int,
    val problemCount: Int,
    val riskCount: Int,
)

/**
 * 概览页（导航项 `overview` 的 Tab 内容）：Dashboard 式指标卡片布局 ——
 * 顶部 vhost Chip + 集群信息行，4 张指标卡（队列 / 交换机 / 消息 / 消费者）横排，
 * 下方健康度 Banner。数据全部来自扫描缓存（不直查管理 API）。
 *
 * **加载态（Connected 即渲染骨架）**：summary == null 时卡片数字显示 "-"、Banner
 * 显示灰色「正在获取集群状态…」—— 不渲染 0 值、不误报「健康 / 没有队列」；
 * 数据随快扫（~1s）与慢扫（~5s）分步到齐后平滑切换真值。
 *
 * 健康度判定（基于快扫的 queueStatus 计数，缺计数的队列跳过不误判）：
 *  - **异常队列**：`messages > 0 && consumers == 0` → 橙色 Banner（可点击 → 跳队列
 *    Tab 并自动开启「只看异常队列」过滤）；
 *  - **风险队列**：`messages > 阈值 && consumers > 0` → 告警第二行；
 *  - vhost 没有任何队列 → 中性灰 Banner（不做消费者判定，防误报）；
 *  - 其余 → 绿色「系统运行健康」。
 */
@Composable
fun RabbitMqOverviewPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { RabbitMqOverviewState(connectionId) }
    val connectionStatus = rememberRabbitMqConnectionStatus(connectionId)
    LaunchedEffect(connectionId) { state.load() }
    LaunchedEffect(connectionId, connectionStatus) { state.onStatusChanged(connectionStatus) }
    // 扫描事件驱动：慢扫（对象名单）/ 快扫（计数）任一完成都重读缓存汇总。
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RabbitMqEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onObjectsScanned()
        }
    }
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RabbitMqEvents.STATUS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onStatusScanned()
        }
    }

    // 缓存汇总（refreshTick 变化 / 连接状态变化时重算；null = 数据未到齐）。
    var summary by remember(connectionId) { mutableStateOf<RabbitMqOverviewSummary?>(null) }
    LaunchedEffect(state.loadState, state.refreshTick, connectionId) {
        summary = computeSummary(connectionId)
    }

    LoadStateContent(
        state = state.loadState,
        onRetry = { state.retry() },
        loadingMessage = stringResource("rabbitmq_ui_connecting"),
        modifier = modifier.fillMaxSize(),
    ) { _ ->
        val config = RabbitMqModule.connectionOrNull(connectionId)?.config
        val clusterInfo = RabbitMqModule.serviceOrNull()?.cachedClusterInfo(connectionId)
        val s = summary

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            // ---- 顶部：vhost Chip + 集群信息（静态配置，常显）----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Text(
                        text = stringResource("rabbitmq_ui_overview_vhost_chip")
                            .withArg(config?.vhost ?: "-"),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                clusterInfo?.let { info ->
                    Text(
                        text = stringResource("rabbitmq_ui_overview_cluster_line")
                            .withArg(info.clusterName.ifBlank { "-" }, info.rabbitmqVersion.ifBlank { "-"}),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            // ---- 指标卡片区：4 张横排等高（IntrinsicSize.Max 拉齐到最高卡）----
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetricCard(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = stringResource("rabbitmq_ui_overview_queue_count"),
                    value = s?.queueCount?.toString() ?: PLACEHOLDER,
                    valueColor = metricValueColor(placeholder = s == null, zero = s?.queueCount == 0),
                    onClick = { requestObjectTab(connectionId, "queues") },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                MetricCard(
                    icon = Icons.Default.CallSplit,
                    label = stringResource("rabbitmq_ui_overview_exchange_count"),
                    value = s?.exchangeCount?.toString() ?: PLACEHOLDER,
                    valueColor = metricValueColor(placeholder = s == null, zero = s?.exchangeCount == 0),
                    onClick = { requestObjectTab(connectionId, "exchanges") },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                MetricCard(
                    icon = Icons.Default.Email,
                    label = stringResource("rabbitmq_ui_overview_total_messages"),
                    value = s?.totalMessages?.toString() ?: PLACEHOLDER,
                    valueColor = metricValueColor(
                        placeholder = s == null,
                        zero = s?.totalMessages == 0L,
                        alert = (s?.totalMessages ?: 0L) > BACKLOG_THRESHOLD,
                    ),
                    footer = s?.let {
                        stringResource("rabbitmq_ui_overview_messages_footer")
                            .withArg(it.messagesReady.toString(), it.messagesUnacknowledged.toString())
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                MetricCard(
                    icon = Icons.Default.Groups,
                    label = stringResource("rabbitmq_ui_overview_total_consumers"),
                    value = s?.totalConsumers?.toString() ?: PLACEHOLDER,
                    valueColor = metricValueColor(placeholder = s == null, zero = s?.totalConsumers == 0),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }

            // ---- 健康度 Banner：加载灰 / 空 vhost 中性灰 / 异常橙（可点击）/ 健康绿 ----
            Spacer(Modifier.height(24.dp))
            when {
                s == null -> HealthBanner(
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = Icons.Default.Info,
                    text = stringResource("rabbitmq_ui_overview_status_loading"),
                    details = emptyList(),
                    onClick = null,
                )
                s.queueCount == 0 -> HealthBanner(
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = Icons.Default.Info,
                    text = stringResource("rabbitmq_ui_overview_health_empty"),
                    details = emptyList(),
                    onClick = null,
                )
                s.problemCount > 0 || s.riskCount > 0 -> {
                    val warnings = buildList {
                        if (s.problemCount > 0) {
                            add(
                                stringResource("rabbitmq_ui_overview_health_backlog_no_consumers")
                                    .withArg(s.problemCount),
                            )
                        }
                        if (s.riskCount > 0) {
                            add(
                                stringResource("rabbitmq_ui_overview_health_risk")
                                    .withArg(s.riskCount, BACKLOG_THRESHOLD),
                            )
                        }
                    }
                    HealthBanner(
                        accent = WARNING_ORANGE,
                        icon = Icons.Default.Warning,
                        text = warnings.first(),
                        details = warnings.drop(1),
                        onClick = {
                            RabbitMqUiHints.requestProblemQueueFilter(connectionId)
                            requestObjectTab(connectionId, "queues")
                        },
                    )
                }
                else -> HealthBanner(
                    accent = HEALTH_GREEN,
                    icon = Icons.Default.CheckCircle,
                    text = stringResource("rabbitmq_ui_overview_health_ok"),
                    details = emptyList(),
                    onClick = null,
                )
            }

            // ---- 集群节点区块（慢扫数据，随 OBJECTS_SCANNED 刷新；未就绪不占位）----
            val nodes = RabbitMqModule.serviceOrNull()?.cachedNodes(connectionId).orEmpty()
            if (nodes.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                NodesSection(nodes)
            }
        }
    }
}

/** 数据未到齐时指标卡的数字占位符。 */
private const val PLACEHOLDER = "-"

/**
 * 从扫描缓存汇总概览指标；缓存全空（快扫/慢扫都还没落地）返回 null。
 * **队列数 = 慢扫名单 ∪ 快扫计数的并集**：快扫先到时队列数立即出真值，
 * 不被慢扫的 5s 起步延迟拖成 0。
 */
private fun computeSummary(connectionId: String): RabbitMqOverviewSummary? {
    val service = RabbitMqModule.serviceOrNull()
    val queues = service?.cachedQueues(connectionId).orEmpty()
    val exchanges = service?.cachedExchanges(connectionId).orEmpty()
    val status = service?.cachedQueueStatus(connectionId).orEmpty()
    if (queues.isEmpty() && exchanges.isEmpty() && status.isEmpty()) return null
    val problemQueues = status.values.filter { it.messages > 0 && it.consumers == 0 }
    val riskQueues = status.values.filter { it.messages > BACKLOG_THRESHOLD && it.consumers > 0 }
    return RabbitMqOverviewSummary(
        queueCount = (queues.map { it.name }.toSet() + status.keys).size,
        exchangeCount = exchanges.size,
        totalMessages = status.values.sumOf { it.messages },
        messagesReady = status.values.sumOf { it.messagesReady },
        messagesUnacknowledged = status.values.sumOf { it.messagesUnacknowledged },
        totalConsumers = status.values.sumOf { it.consumers },
        problemCount = problemQueues.size,
        riskCount = riskQueues.size,
    )
}

/**
 * 指标数字的颜色：告警橙 / 占位与零值标灰 / 默认主题色（图标 tint 同步，视觉成组）。
 */
@Composable
private fun metricValueColor(placeholder: Boolean, zero: Boolean, alert: Boolean = false): Color = when {
    alert -> WARNING_ORANGE
    placeholder || zero -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}

/** 面板发起「打开对象 Tab」请求（宿主 MainWindow 订阅后 onSelect + openTab）。 */
private fun requestObjectTab(connectionId: String, objectType: String) {
    CoreModule.eventBusOrNull?.emit(
        name = EventNames.OBJECT_TAB_OPEN_REQUESTED,
        payload = mapOf("connectionId" to connectionId, "objectType" to objectType),
        source = "rabbitmq",
    )
}

/**
 * 健康度状态横幅：accent 决定语义（橙 = 告警可点击、绿 = 健康、灰 = 中性/加载），底色/
 * 边框为语义色低透明度，与指标卡的告警色同一套约定（不随主题翻转）。
 * [text] 主文案；[details] 为附加行（如风险队列第二条告警）；[onClick] 非空时整条
 * Banner 可点击（hover 边框加深提示）。
 */
@Composable
private fun HealthBanner(
    accent: Color,
    icon: ImageVector,
    text: String,
    details: List<String>,
    onClick: (() -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(
            1.dp,
            accent.copy(alpha = if (onClick != null && hovered) 0.5f else 0.3f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource, enabled = onClick != null)
            .pointerInput(onClick) {
                if (onClick != null) detectTapGestures(onTap = { onClick() })
            },
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = accent,
                )
                details.forEach { detail ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * 集群节点区块：横向平铺的节点状态小卡片（与上方指标卡同一套视觉语言）。
 * 布局：FlowRow 每行最多 4 张、weight(1f) 等宽，节点多时自动换行。
 * 健康判定（优先级从高到低，与 Snapshot 的 KDoc 约定一致）：
 *  - `!running` → 红 ⚠「未运行」；
 *  - `memAlarm / diskFreeAlarm` → 橙 ⚠「内存 / 磁盘告警」（RabbitMQ 会阻塞发布者）；
 *  - 其余 → 绿点「健康」。
 * 悬停（Tooltip）显示 Uptime 与内存占用 —— 开发人员够用即可，不做节点详情页。
 */
@Composable
private fun NodesSection(nodes: List<RabbitMqNodeSnapshot>) {
    Column {
        Text(
            text = stringResource("rabbitmq_ui_overview_nodes_title").withArg(nodes.size),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = 4,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            nodes.forEach { node ->
                AppTooltip(tooltip = { NodeTooltipContent(node) }, delayMillis = 400) {
                    NodeCard(node, Modifier.weight(1f))
                }
            }
        }
    }
}

/** 节点健康色：未运行红 / 资源告警橙 / 健康绿（语义色不随主题翻转，同文件约定）。 */
private fun nodeStatusColor(node: RabbitMqNodeSnapshot): Color = when {
    !node.running -> NODE_DOWN_RED
    node.memAlarm || node.diskFreeAlarm -> WARNING_ORANGE
    else -> HEALTH_GREEN
}

/**
 * 单个节点卡片：第一行 Dns 图标 + 节点名（加粗）；第二行 Disc/Ram Tag + 健康状态。
 * 容器对齐 MetricCard（12dp 圆角 + surface 底 + outlineVariant 细边框），但不可点击
 * 故无 hover 抬升；内边距 12dp。
 */
@Composable
private fun NodeCard(node: RabbitMqNodeSnapshot, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Text(
                        text = node.type.replaceFirstChar { it.uppercaseChar() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                NodeStatus(node)
            }
        }
    }
}

/** 第二行右侧的健康状态：绿点「健康」/ 橙 ⚠「内存·磁盘告警」/ 红 ⚠「未运行」。 */
@Composable
private fun NodeStatus(node: RabbitMqNodeSnapshot) {
    val color = nodeStatusColor(node)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            !node.running -> {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp),
                )
                NodeStatusText(stringResource("rabbitmq_ui_overview_node_status_down"), color)
            }
            node.memAlarm || node.diskFreeAlarm -> {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp),
                )
                NodeStatusText(
                    text = when {
                        node.memAlarm && node.diskFreeAlarm ->
                            stringResource("rabbitmq_ui_overview_node_alarm_both")
                        node.memAlarm -> stringResource("rabbitmq_ui_overview_node_alarm_mem")
                        else -> stringResource("rabbitmq_ui_overview_node_alarm_disk")
                    },
                    color = color,
                )
            }
            else -> {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                NodeStatusText(stringResource("rabbitmq_ui_overview_node_status_ok"), color)
            }
        }
    }
}

/** 状态小字（labelSmall，语义色，单行截断防挤压 Tag）。 */
@Composable
private fun NodeStatusText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

/** 提示内容：Uptime + 内存占用（memLimit 未知时省略内存行）+ 告警补充说明（外壳样式走 AppTooltip）。 */
@Composable
private fun NodeTooltipContent(node: RabbitMqNodeSnapshot) {
    Column {
            Text(
                text = stringResource("rabbitmq_ui_overview_node_tooltip_uptime")
                    .withArg(formatUptime(node.uptime)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (node.memLimit > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource("rabbitmq_ui_overview_node_tooltip_memory")
                        .withArg(formatBytes(node.memUsed), formatBytes(node.memLimit)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (node.memAlarm || node.diskFreeAlarm) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource("rabbitmq_ui_overview_node_alarm"),
                    style = MaterialTheme.typography.bodySmall,
                    color = WARNING_ORANGE,
                )
            }
        }
}

/** 毫秒运行时长 → "3d 4h 5m"（天数/小时为 0 时省略对应段）。 */
private fun formatUptime(ms: Long): String {
    val minutes = ms / 60_000
    val days = minutes / (24 * 60)
    val hours = minutes % (24 * 60) / 60
    val mins = minutes % 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (days > 0 || hours > 0) append("${hours}h ")
        append("${mins}m")
    }.trim()
}

/** 字节 → 人类可读（GB / MB / KB，Locale.ROOT 保证小数点不随系统语言漂移）。 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= (1L shl 30) -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= (1L shl 20) -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= (1L shl 10) -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
