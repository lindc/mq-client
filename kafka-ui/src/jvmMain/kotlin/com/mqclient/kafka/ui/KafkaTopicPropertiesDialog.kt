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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.core.concurrent.runCatchingCancellable
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.KafkaTopicConfigSnapshot
import com.mqclient.kafka.KafkaTopicOffsetRange
import com.mqclient.kafka.KafkaTopicSnapshot
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.DangerButton
import com.mqclient.uicommon.PropertyItem
import com.mqclient.uicommon.PropertyTableSection
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.failureText
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
 *
 * 清空消息：卡片上的 🧹 动作已收进本对话框（与删除同档 danger，位于其左侧）——
 * 破坏性操作集中在属性页，卡片只留 AI / ✉️ / ⓘ（对齐 RocketMQ）。同样是二次确认，
 * 内部主题不渲染；成功后重拉位移，概览的「消息总数」即时归零。
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
    var clearConfirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            val service = KafkaModule.serviceOrNull() ?: error("服务未初始化")
            configs = LoadState.Loading
            configs = runCatchingCancellable {
                LoadState.Success(service.getTopicConfigs(connectionId, topicName))
            }.getOrElse { LoadState.Error(failureText(it)) }
            offsets = LoadState.Loading
            val partitions = snapshot?.partitions?.map { it.id } ?: emptyList()
            offsets = runCatchingCancellable {
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

    fun clearRecords() {
        scope.launch {
            clearing = true
            runCatchingCancellable {
                KafkaModule.serviceOrNull()?.clearTopicRecords(connectionId, topicName)
                    ?: error("服务未初始化")
            }.onSuccess { count ->
                clearing = false
                snackbar.showSnackbar(stringResource("kafka_ui_topic_clear_done").withArg(count))
                // 位移变了：重拉 offsets，概览的「消息总数」不留在旧值上。
                load()
            }.onFailure { error ->
                clearing = false
                // 统一出口：解包异常链 + 本地化分类（与 TopicListPanel 同源）。
                snackbar.showSnackbar(kafkaErrorText(error))
            }
        }
    }

    ObjectDetailDialog(
        title = topicName,
        onDismiss = onDismiss,
        minWidth = 680.dp,
        minHeight = 440.dp,
        footerActions = {
            // 内部主题（__consumer_offsets 等）禁止删除 / 清空：按钮直接隐藏，概览行即提示。
            if (snapshot != null && !snapshot.internal) {
                // 破坏性操作从左到右递进：清空消息 → 删除主题（最右最后一道）。
                DangerButton(onClick = { clearConfirm = true }, enabled = !deleting && !clearing) {
                    Text(stringResource("kafka_ui_topic_clear"))
                }
                DangerButton(onClick = { deleteConfirm = true }, enabled = !deleting && !clearing) {
                    Text(stringResource("kafka_ui_topic_props_delete"))
                }
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton(
                onClick = onDismiss,
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
                    DangerButton(
                        onClick = {
                            deleteConfirm = false
                            delete()
                        },
                        enabled = !deleting,
                    ) {
                        Text(
                            text = stringResource(
                                if (deleting) "kafka_ui_topic_props_deleting" else "kafka_ui_topic_props_delete_confirm",
                            ),
                        )
                    }
                },
                dismissButton = {
                    SecondaryButton(
                        onClick = { deleteConfirm = false },
                        enabled = !deleting,
                    ) { Text(stringResource("common_cancel")) }
                },
            )
        }

        // ---- 清空消息二次确认（同属外壳 content lambda 内的窗口归属约定）。----
        if (clearConfirm) {
            AlertDialog(
                onDismissRequest = { if (!clearing) clearConfirm = false },
                title = { Text(stringResource("kafka_ui_topic_clear_confirm_title")) },
                text = {
                    Text(
                        text = stringResource("kafka_ui_topic_clear_confirm_text").withArg(topicName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    DangerButton(
                        onClick = {
                            clearConfirm = false
                            clearRecords()
                        },
                        enabled = !clearing,
                    ) { Text(stringResource("kafka_ui_topic_clear_confirm")) }
                },
                dismissButton = {
                    SecondaryButton(
                        onClick = { clearConfirm = false },
                        enabled = !clearing,
                    ) { Text(stringResource("common_cancel")) }
                },
            )
        }
    }
}

/**
 * 概览 Tab：三组 [PropertyTableSection]（基础信息 / 分区配置 / 统计），与 RocketMQ
 * 主题属性页同一套视觉（斑马纹 + 左对齐 + 行尾复制）。
 *
 * 消息总数 = 各分区 (latest - earliest) 合计；随位移实时加载，加载中显示 `…` 占位
 * 且关闭复制（否则复制到的是省略号）。
 */
@Composable
internal fun OverviewTab(
    snapshot: KafkaTopicSnapshot,
    offsets: LoadState<Map<Int, KafkaTopicOffsetRange>>,
    modifier: Modifier = Modifier,
) {
    val messageTotal = (offsets as? LoadState.Success)?.data
        ?.values
        ?.sumOf { (it.latest - it.earliest).coerceAtLeast(0L) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        PropertyTableSection(
            title = stringResource("kafka_ui_topic_props_section_basic"),
            items = listOf(
                PropertyItem(stringResource("kafka_ui_topic_props_overview_name"), snapshot.name),
                PropertyItem(
                    stringResource("kafka_ui_topic_props_overview_internal_label"),
                    if (snapshot.internal) {
                        stringResource("kafka_ui_topic_props_overview_internal_yes")
                    } else {
                        stringResource("kafka_ui_topic_props_overview_internal_no")
                    },
                ),
            ),
        )
        Spacer(Modifier.height(16.dp))
        PropertyTableSection(
            title = stringResource("kafka_ui_topic_props_section_partitions"),
            items = listOf(
                PropertyItem(
                    stringResource("kafka_ui_topic_props_overview_partitions"),
                    snapshot.partitions.size.toString(),
                ),
                PropertyItem(
                    stringResource("kafka_ui_topic_props_overview_replicas"),
                    snapshot.partitions.firstOrNull()?.replicaIds?.size?.toString() ?: "-",
                ),
            ),
        )
        Spacer(Modifier.height(16.dp))
        PropertyTableSection(
            title = stringResource("kafka_ui_topic_props_section_stats"),
            items = listOf(
                PropertyItem(
                    key = stringResource("kafka_ui_topic_props_overview_messages"),
                    value = messageTotal?.let(::formatLag) ?: "…",
                    copyable = messageTotal != null,
                ),
            ),
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** 配置 Tab：常用配置（默认值行带小字说明）+ 非默认覆盖项，均走 [PropertyTableSection]。 */
@Composable
internal fun ConfigsTab(state: LoadState<List<KafkaTopicConfigSnapshot>>, modifier: Modifier = Modifier) {
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
                val defaultLabel = stringResource("kafka_ui_topic_props_configs_default_label")
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    PropertyTableSection(
                        title = stringResource("kafka_ui_topic_props_configs_common"),
                        items = commonEntries.map { entry ->
                            PropertyItem(
                                key = entry.key,
                                value = entry.value,
                                // 默认值原来靠灰字区分，PropertyItem 没有颜色通道：
                                // 改用小字说明，信息量反而更明确。
                                description = defaultLabel.takeIf { entry.isDefault },
                            )
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    PropertyTableSection(
                        title = stringResource("kafka_ui_topic_props_configs_nondefault"),
                        items = overridden.map { PropertyItem(it.key, it.value) },
                    )
                    if (overridden.isEmpty()) {
                        Text(
                            text = stringResource("kafka_ui_topic_props_configs_empty"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/**
 * 分区 Tab：搜索 + 表（Partition / Leader / Replicas / ISR / 最早位移 / 末端位移）。
 *
 * 表格视觉严格对齐 `ui-common` 的 [PropertyTable]：数据行最小 40dp、数行斑马纹
 * （`surfaceVariant@30%` + 4dp 圆角）、行距 8dp、正文 bodyMedium、无分割线 ——
 * 同一个对话框里「分区」与「配置」两个 Tab 必须长得像同一张表。表头用 labelMedium
 * + 次要色（与 RocketMQ 路由表同款）。六列均左对齐。
 */
@Composable
internal fun PartitionsTab(
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
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = PARTITION_ROW_HPADDING, vertical = 8.dp)) {
            CompactSearchField(
                value = search,
                onValueChange = onSearchChange,
                placeholder = stringResource("kafka_ui_topic_props_search"),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PARTITION_ROW_HPADDING, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PartitionHeaderCell(stringResource("kafka_ui_group_detail_col_partition"), Modifier.weight(0.5f))
            PartitionHeaderCell(stringResource("kafka_ui_topic_props_col_leader"), Modifier.weight(0.6f))
            PartitionHeaderCell(stringResource("kafka_ui_topic_props_col_replicas"), Modifier.weight(1f))
            PartitionHeaderCell(stringResource("kafka_ui_broker_detail_col_isr"), Modifier.weight(0.8f))
            PartitionHeaderCell(stringResource("kafka_ui_topic_props_col_earliest"), Modifier.weight(0.7f))
            PartitionHeaderCell(stringResource("kafka_ui_topic_props_col_latest"), Modifier.weight(0.7f))
        }
        Spacer(Modifier.height(8.dp))
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
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            // 行距 8dp：与 PropertyTable 的 spacedBy(8.dp) 一致。
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            partitions.forEachIndexed { index, p ->
                val range = (offsets as? LoadState.Success)?.data?.get(p.id)
                val zebra = index % 2 == 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 测试钩子在 heightIn / padding **之前**：语义节点的 bounds 是
                        // 「此处之后全部修饰符占据的区域」—— 放在 padding 之后会报出
                        // 内容区高度（40-16=24dp），行高断言就永远看不到真实行高。
                        .testTag(if (zebra) PARTITION_ZEBRA_ROW_TAG else PARTITION_PLAIN_ROW_TAG)
                        .heightIn(min = PARTITION_ROW_MIN_HEIGHT)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (zebra) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .padding(horizontal = PARTITION_ROW_HPADDING, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PartitionDataCell(p.id.toString(), Modifier.weight(0.5f))
                    PartitionDataCell(
                        p.leaderId?.toString() ?: "-",
                        Modifier.weight(0.6f),
                        // 分区离线（无 leader）用 error 色：这是唯一需要眼睛立刻抓住的异常。
                        color = p.leaderId?.let { MaterialTheme.colorScheme.onSurface }
                            ?: MaterialTheme.colorScheme.error,
                    )
                    PartitionDataCell(p.replicaIds.joinToString(", "), Modifier.weight(1f))
                    PartitionDataCell(p.isrIds.joinToString(", "), Modifier.weight(0.8f))
                    PartitionDataCell(range?.let { formatLag(it.earliest) } ?: "…", Modifier.weight(0.7f))
                    PartitionDataCell(range?.let { formatLag(it.latest) } ?: "…", Modifier.weight(0.7f))
                }
            }
        }
    }
}

/** 分区表行高与 PropertyTable 一致（40dp）。 */
internal val PARTITION_ROW_MIN_HEIGHT = 40.dp

/** 分区表行内水平内边距：20dp 与同对话框的搜索框 / 配置 Tab 对齐。 */
internal val PARTITION_ROW_HPADDING = 20.dp

/** 斑马纹行 / 普通行的测试标签（渲染测试用来定位行并断言行高与背景差异）。 */
internal const val PARTITION_ZEBRA_ROW_TAG = "partition_zebra_row"
internal const val PARTITION_PLAIN_ROW_TAG = "partition_plain_row"

/**
 * 表头单元格：bodyMedium + 次要色 —— 与 PropertyTable 的 **Key 列**同一档。
 *
 * 不用 `labelMedium`（12sp）：那样表头比数据小一整档，而概览里的层级差是靠
 * **颜色**（Key 列 onSurfaceVariant / Value 列 onSurface）表达的，字号两边一致。
 * 三张自绘表（Kafka 分区 / RocketMQ 路由 / RabbitMQ 绑定）统一走这一档。
 */
@Composable
private fun PartitionHeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 数据单元格：bodyMedium + 主色（PropertyTable 的 Value 列同款），单行省略。 */
@Composable
private fun PartitionDataCell(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

