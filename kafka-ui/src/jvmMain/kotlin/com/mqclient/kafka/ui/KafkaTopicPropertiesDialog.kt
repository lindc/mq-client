package com.mqclient.kafka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.KafkaTopicConfigSnapshot
import com.mqclient.kafka.KafkaTopicOffsetRange
import com.mqclient.kafka.KafkaTopicSnapshot
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Topic 属性对话框（主题卡片 ⓘ / 双击打开）：概览 / 配置 / 分区三个 Tab + 底部删除。
 *
 * 数据混合来源：概览与分区表读**慢扫缓存**（[KafkaTopicSnapshot]，零 RPC）；配置项
 * （describeConfigs）与分区位移（listOffsets earliest/latest）**打开时实时查询**——
 * 全集群 configs+offsets 塞慢扫是大集群下的显著负载，唯一消费方是本对话框。
 *
 * 删除：底部 danger 按钮（内部主题隐藏——概览徽标即提示）→ AlertDialog 二次确认
 * （名称 + 分区数 + 不可恢复警示）→ deleteTopics → 成功关对话框 + scanNow 刷新列表。
 * 按钮 / 确认框布局与 KafkaGroupDetailDialog 同一套约定。
 */
@Composable
fun KafkaTopicPropertiesDialog(
    connectionId: String,
    topicName: String,
    onDismiss: () -> Unit,
    /** 删除成功后回调（宿主刷新列表 + Snackbar 提示）。 */
    onDeleted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val snapshot = remember(topicName) {
        KafkaModule.serviceOrNull()?.cachedTopics(connectionId)?.firstOrNull { it.name == topicName }
    }
    var configs by remember { mutableStateOf<LoadState<List<KafkaTopicConfigSnapshot>>>(LoadState.Idle) }
    var offsets by remember { mutableStateOf<LoadState<Map<Int, KafkaTopicOffsetRange>>>(LoadState.Idle) }
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var deleteConfirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            val service = KafkaModule.serviceOrNull() ?: error("服务未初始化")
            configs = LoadState.Loading
            configs = runCatching {
                LoadState.Success(service.getTopicConfigs(connectionId, topicName))
            }.getOrElse { LoadState.Error(failureText(it)) }
            offsets = LoadState.Loading
            val partitions = snapshot?.partitions?.map { it.id } ?: emptyList()
            offsets = runCatching {
                LoadState.Success(service.getTopicOffsets(connectionId, topicName, partitions))
            }.getOrElse { LoadState.Error(failureText(it)) }
        }
    }
    LaunchedEffect(connectionId, topicName) { load() }

    fun delete() {
        scope.launch {
            val service = KafkaModule.serviceOrNull() ?: error("服务未初始化")
            deleting = true
            try {
                service.deleteTopic(connectionId, topicName)
                deleting = false
                onDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deleting = false
                snackbar.showSnackbar(stringResource("kafka_ui_topic_props_delete_failed").withArg(failureText(e)))
            }
        }
    }

    ObjectDetailDialog(
        title = topicName,
        onDismiss = onDismiss,
        minWidth = 680.dp,
        minHeight = 440.dp,
        footerActions = {
            // 内部主题（__consumer_offsets 等）禁止删除：按钮直接隐藏，概览徽标即提示。
            if (snapshot != null && !snapshot.internal) {
                PressableButton(onClick = { deleteConfirm = true }, danger = true) {
                    Text(stringResource("kafka_ui_topic_props_delete"))
                }
            }
            Spacer(Modifier.weight(1f))
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_close")) }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                snapshot == null -> {
                    Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource("kafka_ui_topic_props_not_in_cache"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }) {
                            Text(stringResource("kafka_ui_topic_props_tab_overview"), modifier = Modifier.padding(12.dp))
                        }
                        Tab(selected = tab == 1, onClick = { tab = 1 }) {
                            Text(stringResource("kafka_ui_topic_props_tab_configs"), modifier = Modifier.padding(12.dp))
                        }
                        Tab(selected = tab == 2, onClick = { tab = 2 }) {
                            Text(
                                text = stringResource("kafka_ui_topic_props_tab_partitions")
                                    .withArg(snapshot.partitions.size),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    when (tab) {
                        0 -> OverviewTab(snapshot, offsets, Modifier.weight(1f).fillMaxSize())
                        1 -> ConfigsTab(configs, Modifier.weight(1f).fillMaxSize())
                        else -> PartitionsTab(snapshot, offsets, search, { search = it }, Modifier.weight(1f).fillMaxSize())
                    }
                }
            }
            SnackbarHost(hostState = snackbar)
        }

        // ---- 删除二次确认：**必须声明在本外壳 content lambda 内** —— compose Dialog
        // 的窗口归属随声明组合走，声明在外层会挂到主窗口、被本对话框压住（实测坑，
        // 见外壳 KDoc）。----
        if (deleteConfirm) {
            AlertDialog(
                onDismissRequest = { deleteConfirm = false },
                title = { Text(stringResource("kafka_ui_topic_props_delete_confirm_title")) },
                text = {
                    Text(
                        text = stringResource("kafka_ui_topic_props_delete_confirm_text")
                            .withArg(topicName, snapshot?.partitions?.size ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    PressableButton(
                        onClick = {
                            deleteConfirm = false
                            delete()
                        },
                        enabled = !deleting,
                        danger = true,
                    ) {
                        Text(
                            text = stringResource(
                                if (deleting) "kafka_ui_topic_props_deleting" else "kafka_ui_topic_props_delete_confirm",
                            ),
                        )
                    }
                },
                dismissButton = {
                    PressableButton(
                        onClick = { deleteConfirm = false },
                        enabled = !deleting,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { Text(stringResource("common_cancel")) }
                },
            )
        }
    }
}

/** 概览 Tab 的 label 固定宽度（最长 4 个汉字 + 呼吸感；过长自动换行）。 */
private val OVERVIEW_LABEL_WIDTH = 80.dp

/**
 * 概览 Tab 一行：label 固定宽在左（次要色），value 紧随其后左对齐占满余量（正常色）。
 * 刻意不用 ui-common 的 PropertyRow（value 右对齐到边缘，label/value 距离过远）。
 */
@Composable
private fun OverviewFieldRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(OVERVIEW_LABEL_WIDTH),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 概览 Tab：主题名独占一行；其余两栏（分区数/消息总数 | 副本因子/内部主题）。 */
@Composable
private fun OverviewTab(
    snapshot: KafkaTopicSnapshot,
    offsets: LoadState<Map<Int, KafkaTopicOffsetRange>>,
    modifier: Modifier = Modifier,
) {
    // 消息总数 = 各分区 (latest - earliest) 合计；随位移实时加载，加载中显示占位。
    val messageTotal = (offsets as? LoadState.Success)?.data
        ?.values
        ?.sumOf { (it.latest - it.earliest).coerceAtLeast(0L) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OverviewFieldRow(
            label = stringResource("kafka_ui_topic_create_field_name"),
            value = snapshot.name,
            monospace = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverviewFieldRow(
                    label = stringResource("kafka_ui_topic_props_overview_partitions"),
                    value = snapshot.partitions.size.toString(),
                )
                OverviewFieldRow(
                    label = stringResource("kafka_ui_topic_props_overview_messages"),
                    value = messageTotal?.let(::formatLag) ?: "…",
                    monospace = true,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverviewFieldRow(
                    label = stringResource("kafka_ui_topic_props_overview_replicas"),
                    value = snapshot.partitions.firstOrNull()?.replicaIds?.size?.toString() ?: "-",
                )
                OverviewFieldRow(
                    label = stringResource("kafka_ui_topic_internal"),
                    value = if (snapshot.internal) {
                        stringResource("kafka_ui_topic_props_overview_internal_yes")
                    } else {
                        stringResource("kafka_ui_topic_props_overview_internal_no")
                    },
                )
            }
        }
    }
}

/** 配置 Tab：常用配置（默认值标灰）+ 非默认覆盖项，两列 Key/Value。 */
@Composable
private fun ConfigsTab(state: LoadState<List<KafkaTopicConfigSnapshot>>, modifier: Modifier = Modifier) {
    Column(modifier) {
        when (state) {
            is LoadState.Idle, is LoadState.Loading -> {
                Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource("kafka_ui_topic_props_configs_loading"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            is LoadState.Error -> {
                Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource("kafka_ui_topic_props_configs_failed").withArg(state.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            is LoadState.Success -> {
                val commonKeys = listOf(
                    "retention.ms", "retention.bytes", "cleanup.policy", "compression.type",
                    "max.message.bytes", "min.insync.replicas", "segment.bytes",
                )
                val byKey = state.data.associateBy { it.key }
                val commonEntries = commonKeys.mapNotNull { byKey[it] }
                val overridden = state.data
                    .filter { it.key !in commonKeys && !it.isDefault }
                    .sortedBy { it.key }
                Column(modifier = Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) {
                    ConfigSection(stringResource("kafka_ui_topic_props_configs_common"))
                    commonEntries.forEach { entry ->
                        ConfigRow(entry, dimmed = entry.isDefault)
                    }
                    HorizontalDivider()
                    ConfigSection(stringResource("kafka_ui_topic_props_configs_nondefault"))
                    if (overridden.isEmpty()) {
                        Text(
                            text = stringResource("kafka_ui_topic_props_configs_empty"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    } else {
                        overridden.forEach { entry -> ConfigRow(entry, dimmed = false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigSection(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/** 配置行：Key / Value 两列；默认值标灰（常用配置区），非默认正常色。 */
@Composable
private fun ConfigRow(entry: KafkaTopicConfigSnapshot, dimmed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.key,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = entry.value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (dimmed) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(thickness = 0.5.dp)
}

/** 分区 Tab：搜索 + 表（Partition / Leader / Replicas / ISR / 最早位移 / 末端位移）。 */
@Composable
private fun PartitionsTab(
    snapshot: KafkaTopicSnapshot,
    offsets: LoadState<Map<Int, KafkaTopicOffsetRange>>,
    search: String,
    onSearchChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trimmed = search.trim()
    val partitions = if (trimmed.isBlank()) {
        snapshot.partitions
    } else {
        snapshot.partitions.filter { p ->
            p.id.toString().contains(trimmed) ||
                (p.leaderId?.toString() ?: "").contains(trimmed) ||
                p.replicaIds.joinToString(",").contains(trimmed) ||
                p.isrIds.joinToString(",").contains(trimmed)
        }
    }
    Column(modifier) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            CompactSearchField(
                value = search,
                onValueChange = onSearchChange,
                placeholder = stringResource("kafka_ui_topic_props_search"),
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            DetailHeaderCell(stringResource("kafka_ui_group_detail_col_partition"), Modifier.weight(0.5f))
            DetailHeaderCell(stringResource("kafka_ui_topic_props_col_leader"), Modifier.weight(0.6f))
            DetailHeaderCell(stringResource("kafka_ui_topic_props_col_replicas"), Modifier.weight(1f))
            DetailHeaderCell(stringResource("kafka_ui_broker_detail_col_isr"), Modifier.weight(0.8f))
            DetailHeaderCell(stringResource("kafka_ui_topic_props_col_earliest"), Modifier.weight(0.7f))
            DetailHeaderCell(stringResource("kafka_ui_topic_props_col_latest"), Modifier.weight(0.7f))
        }
        HorizontalDivider()
        if (partitions.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource("kafka_ui_topic_props_no_partitions"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            partitions.forEach { p ->
                val range = (offsets as? LoadState.Success)?.data?.get(p.id)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DetailDataCell(p.id.toString(), Modifier.weight(0.5f), monospace = true)
                    DetailDataCell(
                        p.leaderId?.toString() ?: "-",
                        Modifier.weight(0.6f),
                        monospace = true,
                        color = p.leaderId?.let { MaterialTheme.colorScheme.onSurface }
                            ?: MaterialTheme.colorScheme.error,
                    )
                    DetailDataCell(p.replicaIds.joinToString(", "), Modifier.weight(1f), monospace = true)
                    DetailDataCell(p.isrIds.joinToString(", "), Modifier.weight(0.8f), monospace = true)
                    DetailDataCell(range?.let { formatLag(it.earliest) } ?: "…", Modifier.weight(0.7f), monospace = true)
                    DetailDataCell(range?.let { formatLag(it.latest) } ?: "…", Modifier.weight(0.7f), monospace = true)
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
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = color,
        modifier = if (width != null) modifier.width(width) else modifier,
    )
}

/** 失败原因：解 ExecutionException 包装（与其他调用处同一简化版）。 */
private fun failureText(error: Throwable): String =
    (error as? java.util.concurrent.ExecutionException)?.cause?.message
        ?: error.message
        ?: error::class.simpleName
        ?: "unknown"
