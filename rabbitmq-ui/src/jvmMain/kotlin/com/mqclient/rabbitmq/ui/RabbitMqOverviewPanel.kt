package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.runtime.remember
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
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/** 风险队列的积压阈值（条）：有消费者但积压超限 → 健康度 Banner 风险告警项。 */
internal const val BACKLOG_THRESHOLD = 1_000L

/** 项目既定的语义告警橙（与 Kafka lag 染色同一约定，不随主题翻转）。 */
private val WARNING_ORANGE = Color(0xFFED6C02)

/** 项目既定的语义健康绿（与 Kafka Controller 徽章同一约定，不随主题翻转）。 */
private val HEALTH_GREEN = Color(0xFF2E7D32)

/**
 * 概览页（导航项 `overview` 的 Tab 内容）：Dashboard 式指标卡片布局 ——
 * 顶部 vhost Chip + 集群信息行，4 张指标卡（队列 / 交换机 / 消息 / 消费者）横排，
 * 下方健康度 Banner。数据全部来自扫描缓存（不直查管理 API）。
 *
 * 健康度判定（基于快扫的 queueStatus 计数，缺计数的队列跳过不误判）：
 *  - **异常队列**：`messages > 0 && consumers == 0`（有堆积但没人消费）→ 橙色 Banner，
 *    文案可点击 → 跳队列 Tab 并自动开启「只看异常队列」过滤；
 *  - **风险队列**：`messages > 阈值 && consumers > 0`（有消费者但积压超限）→ 告警第二行；
 *  - vhost 没有任何队列 → 中性灰 Banner（不做消费者判定，防误报）；
 *  - 其余 → 绿色「系统运行健康」。
 * Loading 阶段（两类扫描都还没产出数据）走全页加载占位 —— Banner 不渲染，
 * 杜绝「全 0 数据显示健康」的误报。
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

    LoadStateContent(
        state = state.loadState,
        onRetry = { state.retry() },
        loadingMessage =
            if (connectionStatus == ConnectionStatus.Connecting) {
                stringResource("rabbitmq_ui_connecting")
            } else {
                stringResource("rabbitmq_ui_overview_loading")
            },
        modifier = modifier.fillMaxSize(),
    ) { _ ->
        val service = RabbitMqModule.serviceOrNull()
        val config = RabbitMqModule.connectionOrNull(connectionId)?.config
        val queues = service?.cachedQueues(connectionId).orEmpty()
        val exchanges = service?.cachedExchanges(connectionId).orEmpty()
        val status = service?.cachedQueueStatus(connectionId).orEmpty()
        val clusterInfo = service?.cachedClusterInfo(connectionId)
        val totalMessages = status.values.sumOf { it.messages }
        val totalConsumers = status.values.sumOf { it.consumers }
        val backlog = totalMessages > BACKLOG_THRESHOLD

        // 健康度分类（按队列，基于快扫计数；慢扫名单有但快扫未覆盖的队列不计入）。
        val problemQueues = status.values.filter { it.messages > 0 && it.consumers == 0 }
        val riskQueues = status.values.filter { it.messages > BACKLOG_THRESHOLD && it.consumers > 0 }
        val warnings = buildList {
            if (problemQueues.isNotEmpty()) {
                add(
                    stringResource("rabbitmq_ui_overview_health_backlog_no_consumers")
                        .withArg(problemQueues.size),
                )
            }
            if (riskQueues.isNotEmpty()) {
                add(
                    stringResource("rabbitmq_ui_overview_health_risk")
                        .withArg(riskQueues.size, BACKLOG_THRESHOLD),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            // ---- 顶部：vhost Chip + 集群信息 ----
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
                    value = queues.size.toString(),
                    valueColor = metricValueColor(zero = queues.isEmpty()),
                    onClick = { requestObjectTab(connectionId, "queues") },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                MetricCard(
                    icon = Icons.Default.CallSplit,
                    label = stringResource("rabbitmq_ui_overview_exchange_count"),
                    value = exchanges.size.toString(),
                    valueColor = metricValueColor(zero = exchanges.isEmpty()),
                    onClick = { requestObjectTab(connectionId, "exchanges") },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                MetricCard(
                    icon = Icons.Default.Email,
                    label = stringResource("rabbitmq_ui_overview_total_messages"),
                    value = totalMessages.toString(),
                    valueColor = metricValueColor(
                        zero = totalMessages == 0L,
                        alert = backlog,
                    ),
                    footer = stringResource("rabbitmq_ui_overview_messages_footer")
                        .withArg(
                            status.values.sumOf { it.messagesReady }.toString(),
                            status.values.sumOf { it.messagesUnacknowledged }.toString(),
                        ),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                MetricCard(
                    icon = Icons.Default.Groups,
                    label = stringResource("rabbitmq_ui_overview_total_consumers"),
                    value = totalConsumers.toString(),
                    valueColor = metricValueColor(zero = totalConsumers == 0),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }

            // ---- 健康度 Banner：异常橙（可点击带过滤）/ 空 vhost 中性灰 / 健康绿 ----
            Spacer(Modifier.height(24.dp))
            when {
                queues.isEmpty() -> HealthBanner(
                    accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = Icons.Default.Info,
                    text = stringResource("rabbitmq_ui_overview_health_empty"),
                    details = emptyList(),
                    onClick = null,
                )
                warnings.isNotEmpty() -> HealthBanner(
                    accent = WARNING_ORANGE,
                    icon = Icons.Default.Warning,
                    text = warnings.first(),
                    details = warnings.drop(1),
                    onClick = {
                        RabbitMqUiHints.requestProblemQueueFilter(connectionId)
                        requestObjectTab(connectionId, "queues")
                    },
                )
                else -> HealthBanner(
                    accent = HEALTH_GREEN,
                    icon = Icons.Default.CheckCircle,
                    text = stringResource("rabbitmq_ui_overview_health_ok"),
                    details = emptyList(),
                    onClick = null,
                )
            }
        }
    }
}

/** 指标数字的颜色：告警橙 / 零值标灰 / 默认主题色（图标 tint 同步，视觉成组）。 */
@Composable
private fun metricValueColor(zero: Boolean, alert: Boolean = false): Color = when {
    alert -> WARNING_ORANGE
    zero -> MaterialTheme.colorScheme.onSurfaceVariant
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
 * 健康度状态横幅：accent 决定语义（橙 = 告警可点击、绿 = 健康、灰 = 中性），底色/
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
