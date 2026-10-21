package com.mqclient.rocketmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.core.CoreModule
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.rocketmq.RocketMqBrokerSnapshot
import com.mqclient.rocketmq.RocketMqEvents
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.uicommon.PropertyRow
import com.mqclient.uicommon.PropertySection
import com.mqclient.uicommon.i18n.stringResource

/**
 * 集群概览页（导航项 `overview` 的 Tab 内容）：三段式只读属性布局（**集群信息** /
 * **Broker 列表** / **对象计数**），复用 ui-common 的 [PropertySection] 卡片 +
 * [PropertyRow] label-value 对。数据读慢扫缓存，OBJECTS_SCANNED 事件驱动刷新。
 *
 * 状态容器：`LoadStateContent` 挂 `weight(1f)`（外层**不用** verticalScroll ——
 * 滚动容器里没有剩余高度约束，Loading 会贴顶），Loading / Error 居中于整个面板。
 *
 * **LayoutProvider 的 content 在 Box scope 执行**：多个兄弟 composable 会全部
 * 堆叠在左上角（历史踩坑），content 内部必须整体包 Column。
 */
@Composable
fun RocketMqOverviewPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { RocketMqOverviewState(connectionId) }
    // 进页初始读缓存：事件先于页面订阅到达时（慢扫早已完成）没有这次调用，
    // loadState 会永远停在 Idle —— LoadStateContent 对 Idle 渲染空白。
    LaunchedEffect(connectionId) { state.refreshFromCache() }
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RocketMqEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onObjectsScanned()
        }
    }
    val namesrvAddrs = remember(connectionId) {
        // NameServer 清单来自连接配置（表单原文，分号分隔）。
        RocketMqModule.connectionOrNull(connectionId)?.config?.namesrvAddr
            ?.split(";", ",", "；", "，")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
    }

    Column(modifier = modifier.fillMaxSize()) {
        LoadStateContent(
            state = state.loadState,
            onRetry = { state.refreshFromCache() },
            loadingMessage = stringResource("rocketmq_ui_overview_loading"),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { cluster ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // ---- 集群信息：卡片化 label-value（值 + 复制按钮）----
                PropertySection(title = stringResource("rocketmq_ui_overview_cluster")) {
                    PropertyRow(
                        stringResource("rocketmq_ui_overview_cluster_name"),
                        cluster.clusterName ?: "-",
                    )
                    namesrvAddrs.forEach { addr ->
                        CopyablePropertyRow(stringResource("rocketmq_ui_overview_nameserver_addr"), addr)
                    }
                    if (namesrvAddrs.isEmpty()) {
                        Text(
                            text = stringResource("rocketmq_ui_overview_no_nameservers"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ---- Broker 列表：表头 + weight 分列 + 斑马纹 ----
                PropertySection(title = stringResource("rocketmq_ui_overview_brokers")) {
                    if (cluster.brokers.isEmpty()) {
                        Text(
                            text = stringResource("rocketmq_ui_overview_no_brokers"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        BrokerTableHeader()
                        cluster.brokers.forEachIndexed { index, broker ->
                            BrokerTableRow(broker, zebra = index % 2 == 1)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ---- 对象计数：与集群信息同款 label-value 卡片 ----
                PropertySection(title = stringResource("rocketmq_ui_overview_counts")) {
                    PropertyRow(stringResource("rocketmq_ui_nav_topics"), state.topicCount.toString())
                    PropertyRow(stringResource("rocketmq_ui_nav_groups"), state.groupCount.toString())
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** NameServer 地址行：label 定宽对齐 + 值 monospace 撑满 + 最右复制按钮（垂直居中）。 */
@Composable
private fun CopyablePropertyRow(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(value)) },
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource("rocketmq_ui_overview_copy_address"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Broker 表头：名称 / ID / 角色 / 地址，权重 3 : 1 : 1.5 : 4（行内全部 weight，无定宽）。 */
@Composable
private fun BrokerTableHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = stringResource("rocketmq_ui_overview_col_name"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(3f),
        )
        Text(
            text = stringResource("rocketmq_ui_overview_col_id"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource("rocketmq_ui_overview_col_role"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.5f),
        )
        Text(
            text = stringResource("rocketmq_ui_overview_col_addr"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(4f),
        )
    }
    HorizontalDivider(modifier = Modifier.fillMaxWidth(), thickness = 0.5.dp)
}

/**
 * Broker 数据行：斑马纹背景（偶数行浅色），地址过长省略号截断（窗口缩窄不崩塌）。
 * 角色列：Master 主色 / Slave 次要色（文字徽章）。
 */
@Composable
private fun BrokerTableRow(broker: RocketMqBrokerSnapshot, zebra: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (zebra) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                } else {
                    Color.Transparent
                },
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = broker.brokerName,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(3f),
        )
        Text(
            text = broker.brokerId.toString(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = broker.role,
            style = MaterialTheme.typography.bodySmall,
            color = if (broker.role == "Master") {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1.5f),
        )
        Text(
            text = broker.address,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(4f),
        )
    }
}
