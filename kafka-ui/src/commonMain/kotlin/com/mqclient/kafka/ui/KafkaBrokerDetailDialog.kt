package com.mqclient.kafka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mqclient.kafka.KafkaBrokerSnapshot
import com.mqclient.kafka.KafkaTopicSnapshot
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 一个 Broker 承载分区的行（概览统计与表格共用；纯内存过滤，无 AdminClient 调用）。
 */
internal data class BrokerPartitionRow(
    val topic: String,
    val partition: Int,
    /** true = 该 broker 是此分区的 leader；false = 仅副本（含离线副本）。 */
    val isLeader: Boolean,
    val isrIds: List<Int>,
)

/**
 * 从缓存的 topic 分区明细反查某 broker 承载的分区：`leaderId == brokerId` 记
 * Leader，`brokerId in replicaIds` 记 Follower（离线副本的 leaderId 为 null，
 * 此时只可能命中 Follower）。按 (topic, partition) 排序。
 */
internal fun brokerPartitionRows(
    brokerId: Int,
    topics: List<KafkaTopicSnapshot>,
): List<BrokerPartitionRow> =
    topics.asSequence()
        .flatMap { t -> t.partitions.map { t.name to it } }
        .mapNotNull { (topic, p) ->
            when {
                p.leaderId == brokerId -> BrokerPartitionRow(topic, p.id, isLeader = true, isrIds = p.isrIds)
                brokerId in p.replicaIds -> BrokerPartitionRow(topic, p.id, isLeader = false, isrIds = p.isrIds)
                else -> null
            }
        }
        .sortedWith(compareBy({ it.topic }, { it.partition }))
        .toList()

/**
 * Broker 详情对话框（集群概览页 Broker 行点击打开）：概览（ID / Host / Port / Rack /
 * 角色）+ 统计（Leader 数 / Follower 数 / 承载 Topic 数）+ 可搜索的分区表。
 *
 * 数据**纯缓存过滤**（[brokerPartitionRows]，[com.mqclient.kafka.KafkaScanService]
 * 慢扫已缓存全量 topic 分区明细）—— 不碰 AdminClient；布局照
 * [KafkaGroupDetailDialog] 的 ObjectDetailDialog 模式。
 */
@Composable
fun KafkaBrokerDetailDialog(
    broker: KafkaBrokerSnapshot,
    topics: List<KafkaTopicSnapshot>,
    /** 是否当前 controller（取概览快照的 controllerId，比快照字段新鲜）。 */
    isController: Boolean,
    /** 离线推断（副本引用但不在存活列表）；true 时角色行加红色标记。 */
    offline: Boolean,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val rows = remember(broker.brokerId, topics) { brokerPartitionRows(broker.brokerId, topics) }
    val trimmed = search.trim()
    val filtered =
        if (trimmed.isBlank()) rows
        else rows.filter { it.topic.contains(trimmed, ignoreCase = true) }
    val leaderCount = rows.count { it.isLeader }
    val followerCount = rows.size - leaderCount
    val topicCount = rows.map { it.topic }.distinct().size

    ObjectDetailDialog(
        title = stringResource("kafka_ui_broker_detail_title").withArg(broker.brokerId),
        onDismiss = onDismiss,
        minWidth = 640.dp,
        minHeight = 420.dp,
        footerActions = {
            SecondaryButton(
                onClick = onDismiss,
            ) { Text(stringResource("common_close")) }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 概览区：ID / Host / Port / Rack / 角色 ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    MetricCell(
                        label = stringResource("kafka_ui_cluster_col_id"),
                        value = broker.brokerId.toString(),
                        modifier = Modifier.weight(0.5f),
                    )
                    MetricCell(
                        label = stringResource("kafka_ui_cluster_col_host"),
                        value = broker.host,
                        modifier = Modifier.weight(1.6f),
                        monospace = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    MetricCell(
                        label = stringResource("kafka_ui_cluster_col_port"),
                        value = broker.port.toString(),
                        modifier = Modifier.weight(0.5f),
                    )
                    MetricCell(
                        label = stringResource("kafka_ui_cluster_col_rack"),
                        value = broker.rack ?: stringResource("kafka_ui_broker_detail_rack_unassigned"),
                        modifier = Modifier.weight(1.6f),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource("kafka_ui_cluster_col_role"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(BROLE_LABEL_WIDTH),
                    )
                    BrokerRoleCell(
                        broker = broker,
                        offline = offline,
                        width = BROLE_BADGE_WIDTH,
                        normalText = stringResource("kafka_ui_broker_detail_role_normal"),
                    )
                }
            }
            HorizontalDivider()

            // ---- 统计区：Leader 数 / Follower 数 / 承载 Topic 数 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                MetricCell(
                    label = stringResource("kafka_ui_broker_detail_stat_leader"),
                    value = leaderCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    label = stringResource("kafka_ui_broker_detail_stat_follower"),
                    value = followerCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricCell(
                    label = stringResource("kafka_ui_broker_detail_stat_topics"),
                    value = topicCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()

            // ---- 搜索 + 分区表（可滚动）----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                CompactSearchField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = stringResource("kafka_ui_broker_detail_search"),
                    modifier = Modifier.weight(1f),
                )
            }
            BrokerPartitionTable(filtered, Modifier.weight(1f).fillMaxSize())
        }
    }
}

/** 分区表：表头 + 行（Topic / Partition / 角色 / ISR），空态居中提示。 */
@Composable
private fun BrokerPartitionTable(rows: List<BrokerPartitionRow>, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (rows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource("kafka_ui_broker_detail_no_partitions"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            DetailHeaderCell(stringResource("kafka_ui_broker_detail_col_topic"), Modifier.weight(1.2f))
            DetailHeaderCell(stringResource("kafka_ui_broker_detail_col_partition"), width = COL_P_PARTITION)
            DetailHeaderCell(stringResource("kafka_ui_broker_detail_col_role"), width = COL_P_ROLE)
            DetailHeaderCell(stringResource("kafka_ui_broker_detail_col_isr"), Modifier.weight(1f))
        }
        HorizontalDivider()
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DetailDataCell(row.topic, Modifier.weight(1.2f), monospace = true)
                    DetailDataCell(row.partition.toString(), width = COL_P_PARTITION, monospace = true)
                    DetailDataCell(
                        text = stringResource(
                            if (row.isLeader) "kafka_ui_broker_detail_role_leader"
                            else "kafka_ui_broker_detail_role_follower",
                        ),
                        width = COL_P_ROLE,
                        color = if (row.isLeader) GroupStatusStyle.STABLE else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (row.isLeader) FontWeight.Medium else FontWeight.Normal,
                    )
                    DetailDataCell(row.isrIds.joinToString(", "), Modifier.weight(1f), monospace = true)
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun DetailHeaderCell(text: String, modifier: Modifier = Modifier, width: Dp? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        modifier = if (width != null) modifier.width(width) else modifier,
    )
}

@Composable
private fun DetailDataCell(
    text: String,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    monospace: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = fontWeight,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = color,
        modifier = if (width != null) modifier.width(width) else modifier,
    )
}

private val BROLE_LABEL_WIDTH = 40.dp
private val BROLE_BADGE_WIDTH = 110.dp
private val COL_P_PARTITION = 80.dp
private val COL_P_ROLE = 90.dp
