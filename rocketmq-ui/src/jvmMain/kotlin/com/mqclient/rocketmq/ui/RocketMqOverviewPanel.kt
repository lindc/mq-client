package com.mqclient.rocketmq.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mqclient.core.CoreModule
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.rocketmq.RocketMqBrokerSnapshot
import com.mqclient.rocketmq.RocketMqEvents
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.ui.RocketMqOverviewState
import com.mqclient.uicommon.dialog.MessageDetailSectionTitle
import com.mqclient.uicommon.dialog.MessageHeaderRow
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 集群概览页（导航项 `overview` 的 Tab 内容）：Broker 列表（地址 / 角色）+
 * NameServer（配置的 namesrvAddr，分号分隔）+ 主题 / 消费组计数。缓存优先 +
 * OBJECTS_SCANNED 事件驱动刷新。
 */
@Composable
fun RocketMqOverviewPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val state = remember(connectionId) { RocketMqOverviewState(connectionId) }
    // 进页初始读缓存：**事件先于页面订阅到达时**（慢扫早已完成）没有这次调用，
    // loadState 会永远停在 Idle —— LoadStateContent 对 Idle 渲染空白（纯白根因）。
    LaunchedEffect(connectionId) { state.refreshFromCache() }
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RocketMqEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) state.onObjectsScanned()
        }
    }
    val namesrvAddrs = connectionId.let { id ->
        // NameServer 清单来自连接配置（表单原文，分号分隔）。
        RocketMqModule.connectionOrNull(id)?.config?.namesrvAddr
            ?.split(";", ",", "；", "，")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?: emptyList()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        LoadStateContent(
            state = state.loadState,
            onRetry = { state.refreshFromCache() },
            loadingMessage = stringResource("rocketmq_ui_overview_loading"),
            modifier = Modifier.fillMaxWidth(),
        ) { cluster ->
            // LoadStateContent 的 content 在 **Box scope** 里执行：多个兄弟
            // composable 会全部堆叠在左上角（文字重叠根因）—— 必须整体包进一个
            // Column，三段式只读属性布局（集群信息 / Broker 列表 / 对象计数）。
            Column {
                MessageDetailSectionTitle(text = stringResource("rocketmq_ui_overview_cluster"))
                Spacer(Modifier.height(4.dp))
                MessageHeaderRow(
                    stringResource("rocketmq_ui_overview_cluster_name"),
                    cluster.clusterName ?: "-",
                )
                namesrvAddrs.forEach { addr ->
                    MessageHeaderRow(stringResource("rocketmq_ui_overview_nameserver_addr"), addr)
                }
                if (namesrvAddrs.isEmpty()) {
                    Text(
                        text = stringResource("rocketmq_ui_overview_no_nameservers"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                MessageDetailSectionTitle(text = stringResource("rocketmq_ui_overview_brokers"))
                Spacer(Modifier.height(4.dp))
                cluster.brokers.forEach { broker ->
                    BrokerRow(broker)
                }
                if (cluster.brokers.isEmpty()) {
                    Text(
                        text = stringResource("rocketmq_ui_overview_no_brokers"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                MessageDetailSectionTitle(text = stringResource("rocketmq_ui_overview_counts"))
                Spacer(Modifier.height(4.dp))
                MessageHeaderRow(stringResource("rocketmq_ui_nav_topics"), state.topicCount.toString())
                MessageHeaderRow(stringResource("rocketmq_ui_nav_groups"), state.groupCount.toString())
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Broker 行：名称（monospace）+ 角色 Tag（Master 主色 / Slave 次要色）+ 地址。 */
@Composable
private fun BrokerRow(broker: RocketMqBrokerSnapshot) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = broker.brokerName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(180.dp),
            )
            Text(
                text = "ID " + broker.brokerId + " · " + broker.role,
                style = MaterialTheme.typography.bodySmall,
                color = if (broker.role == "Master") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.width(110.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = broker.address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(modifier = Modifier.fillMaxWidth(), thickness = 0.5.dp)
    }
}
