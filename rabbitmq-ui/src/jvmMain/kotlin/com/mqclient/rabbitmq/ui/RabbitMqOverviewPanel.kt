package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mqclient.core.CoreModule
import com.mqclient.rabbitmq.RabbitMqEvents
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 概览页（导航项 `overview` 的 Tab 内容）：vhost / 队列数 / 交换机数 / 总消息数 /
 * 总消费者数的指标汇总，全部来自扫描缓存（不直查管理 API）；两类扫描事件任一
 * 完成都重读缓存刷新。
 */
@Composable
fun RabbitMqOverviewPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    // 数据键：事件到达时 +1 触发重读（缓存在 service 侧，面板只做汇总投影）。
    var refreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RabbitMqEvents.OBJECTS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) refreshTick++
        }
    }
    LaunchedEffect(connectionId) {
        CoreModule.eventBusOrNull?.on(RabbitMqEvents.STATUS_SCANNED, scope = this) { event ->
            if (event.payload["connectionId"] as? String == connectionId) refreshTick++
        }
    }

    val service = RabbitMqModule.serviceOrNull()
    val config = RabbitMqModule.connectionOrNull(connectionId)?.config
    val queues = service?.cachedQueues(connectionId).orEmpty()
    val exchanges = service?.cachedExchanges(connectionId).orEmpty()
    val status = service?.cachedQueueStatus(connectionId).orEmpty()
    val totalMessages = status.values.sumOf { it.messages }
    val totalConsumers = status.values.sumOf { it.consumers }

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            OverviewHeader(stringResource("rabbitmq_ui_overview_vhost"), config?.vhost ?: "-")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                MetricCell(stringResource("rabbitmq_ui_overview_queue_count"), queues.size.toString())
                MetricCell(stringResource("rabbitmq_ui_overview_exchange_count"), exchanges.size.toString())
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                MetricCell(stringResource("rabbitmq_ui_overview_total_messages"), totalMessages.toString())
                MetricCell(stringResource("rabbitmq_ui_overview_total_consumers"), totalConsumers.toString())
            }
        }
    }
}

/** 指标项：标签小字 + 数值（等宽字体，与 kafka 集群概览的 MetricCell 同一视觉）。 */
@Composable
private fun MetricCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** 概览头部：vhost 一目了然（连接配置快照，非实时查询）。 */
@Composable
private fun OverviewHeader(label: String, value: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = value,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}
