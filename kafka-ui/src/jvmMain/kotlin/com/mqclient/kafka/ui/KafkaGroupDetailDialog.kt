package com.mqclient.kafka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.mqclient.kafka.KafkaGroupDetailSnapshot
import com.mqclient.kafka.KafkaGroupResetTo
import com.mqclient.kafka.KafkaModule
import com.mqclient.mqui.state.LoadState
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.dialog.DateTimePickerDialog
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * 消费组详情对话框（复用 [ObjectDetailDialog] 底座）：概览行 + 成员 / 位移两个 Tab，
 * 底部操作 = 重置位移（earliest / latest / 指定时间点三选一确认）+ 删除消费组
 * （仅空组可用）+ 关闭。
 *
 * 数据**实时查询**（[com.mqclient.kafka.KafkaScanService.getGroupDetail]，与 MQ 集群
 * 关系面板同一哲学 —— 详情要新鲜数据）；重置 / 删除成功后重拉详情或关闭并触发一轮
 * 慢扫让列表尽快反映变化。
 */
@Composable
fun KafkaGroupDetailDialog(
    connectionId: String,
    groupId: String,
    onDismiss: () -> Unit,
) {
    var detail by remember { mutableStateOf<LoadState<KafkaGroupDetailSnapshot>>(LoadState.Idle) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }
    // 重置确认框：只管开关；档位与时间输入是弹窗自己的临时状态（每次打开重置）。
    var resetOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            detail = LoadState.Loading
            detail = runCatching {
                val service = KafkaModule.serviceOrNull() ?: error("服务未初始化")
                LoadState.Success(service.getGroupDetail(connectionId, groupId))
            }.getOrElse { LoadState.Error(failureText(it)) }
        }
    }
    LaunchedEffect(connectionId, groupId) { load() }

    fun reset(to: KafkaGroupResetTo) {
        scope.launch {
            val service = KafkaModule.serviceOrNull() ?: error("服务未初始化")
            runCatching { service.resetGroupOffsets(connectionId, groupId, to) }
                .onSuccess { count ->
                    val doneKey = if (to is KafkaGroupResetTo.AtTimestamp) {
                        "kafka_ui_group_detail_reset_done_timestamp"
                    } else {
                        "kafka_ui_group_detail_reset_done"
                    }
                    snackbar.showSnackbar(stringResource(doneKey).withArg(count))
                    load()
                }
                .onFailure { snackbar.showSnackbar(failureText(it)) }
        }
    }

    fun delete() {
        scope.launch {
            val service = KafkaModule.serviceOrNull() ?: error("服务未初始化")
            runCatching { service.deleteGroup(connectionId, groupId) }
                .onSuccess {
                    snackbar.showSnackbar(stringResource("kafka_ui_group_detail_delete_done"))
                    // 组已删除：关详情并触发一轮慢扫，让列表尽快反映变化。
                    KafkaModule.scanNow(connectionId)
                    onDismiss()
                }
                .onFailure { snackbar.showSnackbar(failureText(it)) }
        }
    }

    ObjectDetailDialog(
        title = groupId,
        onDismiss = onDismiss,
        minWidth = 640.dp,
        minHeight = 420.dp,
        footerActions = {
            // MQ 约定：操作按钮在左，弹性 Spacer 占位，「关闭」独占右侧。
            val ready = (detail as? LoadState.Success)?.data
            // 主操作（主色）：比关闭突出。重置要求组无活跃成员（EMPTY/DEAD）—— 与
            // 服务端前置检查双保险（活跃消费者会重新提交位移覆盖重置结果）。
            PressableButton(
                onClick = { resetOpen = true },
                enabled = ready != null &&
                    (ready.state.equals("EMPTY", ignoreCase = true) || ready.state.equals("DEAD", ignoreCase = true)),
            ) {
                Text(stringResource("kafka_ui_group_detail_action_reset"))
            }
            // 危险操作：与删除连接 / 危险命令同一 danger 档（error 底 + onError 字）。
            PressableButton(
                onClick = { deleteConfirm = true },
                enabled = ready != null && ready.state.equals("EMPTY", ignoreCase = true),
                danger = true,
            ) {
                Text(stringResource("kafka_ui_group_detail_action_delete"))
            }
            Spacer(Modifier.weight(1f))
            // 次级（surfaceVariant）：与各确认框取消键同款。
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(stringResource("common_close"))
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                when (val s = detail) {
                    is LoadState.Idle, is LoadState.Loading -> {
                        Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = stringResource("kafka_ui_group_detail_loading"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    is LoadState.Error -> {
                        Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource("kafka_ui_group_detail_failed").withArg(s.message),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = { load() }) {
                                    Text(stringResource("kafka_ui_refresh"))
                                }
                            }
                        }
                    }
                    is LoadState.Success<KafkaGroupDetailSnapshot> -> {
                        val d = s.data
                        GroupOverviewRow(d)
                        HorizontalDivider()
                        TabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }) {
                                Text(
                                    text = stringResource("kafka_ui_group_detail_tab_members")
                                        .withArg(d.members.size),
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                            Tab(selected = tab == 1, onClick = { tab = 1 }) {
                                Text(
                                    text = stringResource("kafka_ui_group_detail_tab_offsets")
                                        .withArg(d.offsets.size),
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                        when (tab) {
                            0 -> MemberTable(d, Modifier.weight(1f).fillMaxSize())
                            else -> OffsetTable(d, Modifier.weight(1f).fillMaxSize())
                        }
                    }
                }
            }
            ThemedSnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }

        // ---- 重置位移确认：earliest / latest / 指定时间点单选 + 警示 ----
        if (resetOpen) {
            // 弹窗临时状态：每次打开重新开始（默认 latest，与旧行为一致）。
            var mode by remember { mutableStateOf(ResetMode.LATEST) }
            // 时间档选中的时刻：默认当前时间（分钟粒度），可清除（null = 确认键禁用）。
            var pickedTime by remember {
                mutableStateOf<LocalDateTime?>(
                    LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES),
                )
            }
            var showTimePicker by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { resetOpen = false },
                title = { Text(stringResource("kafka_ui_group_detail_action_reset")) },
                text = {
                    Column {
                        Text(
                            text = stringResource("kafka_ui_group_detail_reset_confirm_text").withArg(groupId),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        ResetOptionRow(
                            label = stringResource("kafka_ui_group_detail_reset_to_earliest"),
                            selected = mode == ResetMode.EARLIEST,
                            onSelect = { mode = ResetMode.EARLIEST },
                        )
                        ResetOptionRow(
                            label = stringResource("kafka_ui_group_detail_reset_to_latest"),
                            selected = mode == ResetMode.LATEST,
                            onSelect = { mode = ResetMode.LATEST },
                        )
                        ResetOptionRow(
                            label = stringResource("kafka_ui_group_detail_reset_to_timestamp"),
                            selected = mode == ResetMode.TIMESTAMP,
                            onSelect = { mode = ResetMode.TIMESTAMP },
                        )
                        // 时间档的回显行：仅选中时出现；点击弹两步选择器（MQ 浏览同款）。
                        if (mode == ResetMode.TIMESTAMP) {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PressableButton(
                                    onClick = { showTimePicker = true },
                                    standardMinWidth = false,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ) {
                                    Text(
                                        text = pickedTime?.format(RESET_TIME_DISPLAY_FORMATTER)
                                            ?: stringResource("kafka_ui_group_detail_reset_time_not_set"),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                if (pickedTime != null) {
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { pickedTime = null },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                            // 未来时间不拦截：该时刻之后无数据的分区由 service 的 -1
                            // 兜底重置到 latest（diag 实测），这里只做轻提示。
                            Text(
                                text = stringResource("kafka_ui_group_detail_reset_time_future_hint"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {
                    // 重置会覆盖位点：确认键走 danger 档（同 MQ ChannelResetSeqnumDialog）。
                    PressableButton(
                        onClick = {
                            resetOpen = false
                            when (mode) {
                                ResetMode.EARLIEST -> reset(KafkaGroupResetTo.Earliest)
                                ResetMode.LATEST -> reset(KafkaGroupResetTo.Latest)
                                ResetMode.TIMESTAMP -> pickedTime?.let {
                                    // 本地时区 → epoch millis（与浏览 Tab 的时间起点同款换算）。
                                    reset(
                                        KafkaGroupResetTo.AtTimestamp(
                                            it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                                        ),
                                    )
                                }
                            }
                        },
                        danger = true,
                        enabled = mode != ResetMode.TIMESTAMP || pickedTime != null,
                    ) { Text(stringResource("kafka_ui_group_detail_reset_confirm")) }
                },
                dismissButton = {
                    PressableButton(
                        onClick = { resetOpen = false },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { Text(stringResource("common_cancel")) }
                },
            )

            // 时间选择器：与重置确认框同为兄弟节点声明的 AlertDialog（窗口归属随
            // 所在组合走，见 KafkaMessagesDialog 的 KDoc 规范），叠在其上。
            if (showTimePicker) {
                DateTimePickerDialog(
                    title = stringResource("kafka_ui_group_detail_reset_to_timestamp"),
                    initial = pickedTime,
                    onConfirm = {
                        pickedTime = it
                        showTimePicker = false
                    },
                    onDismiss = { showTimePicker = false },
                )
            }
        }

        // ---- 删除确认 ----
        if (deleteConfirm) {
            AlertDialog(
                onDismissRequest = { deleteConfirm = false },
                title = { Text(stringResource("kafka_ui_group_detail_action_delete")) },
                text = {
                    Text(
                        text = stringResource("kafka_ui_group_detail_delete_confirm_text").withArg(groupId),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    PressableButton(
                        onClick = {
                            deleteConfirm = false
                            delete()
                        },
                        danger = true,
                    ) { Text(stringResource("kafka_ui_group_detail_action_delete")) }
                },
                dismissButton = {
                    PressableButton(
                        onClick = { deleteConfirm = false },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { Text(stringResource("common_cancel")) }
                },
            )
        }
    }

}

/** 概览行：状态（色点 + 文字）/ 成员数 / 总 Lag（千分位）/ 订阅 Topic 数。 */
@Composable
private fun GroupOverviewRow(snapshot: KafkaGroupDetailSnapshot) {
    val style = groupStateStyle(snapshot.state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态：label + 色点 + 长文案（带语境说明，如「空组（无活跃成员）」）。
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1.2f)) {
            Text(
                text = stringResource("kafka_ui_group_detail_overview_state"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "●",
                color = style.color,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = style.detailText,
                style = MaterialTheme.typography.bodyMedium,
                color = style.color,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MetricCell(
            label = stringResource("kafka_ui_group_members").withArg(snapshot.members.size),
            value = snapshot.members.size.toString(),
            modifier = Modifier.weight(0.5f),
        )
        MetricCell(
            label = stringResource("kafka_ui_group_detail_overview_total_lag"),
            value = formatLag(snapshot.totalLag),
            modifier = Modifier.weight(0.7f),
            monospace = true,
        )
        MetricCell(
            label = stringResource("kafka_ui_group_detail_overview_subscribed"),
            value = snapshot.subscribedTopics.size.toString(),
            modifier = Modifier.weight(0.5f),
        )
    }
}

/** 成员表：成员 ID / 客户端 ID / 主机 / 分配分区。 */
@Composable
private fun MemberTable(snapshot: KafkaGroupDetailSnapshot, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (snapshot.members.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource("kafka_ui_group_detail_no_members"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            DetailCell(stringResource("kafka_ui_group_detail_col_member_id"), Modifier.weight(1.2f), bold = true)
            DetailCell(stringResource("kafka_ui_group_detail_col_client_id"), Modifier.weight(1f), bold = true)
            DetailCell(stringResource("kafka_ui_group_detail_col_host"), Modifier.weight(1f), bold = true)
            DetailCell(stringResource("kafka_ui_group_detail_col_partitions"), Modifier.weight(1.2f), bold = true)
        }
        HorizontalDivider()
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            snapshot.members.forEach { member ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DetailCell(member.memberId, Modifier.weight(1.2f), monospace = true)
                    DetailCell(member.clientId, Modifier.weight(1f), monospace = true)
                    DetailCell(member.host, Modifier.weight(1f), monospace = true)
                    // 分配分区列表可能很长：显示「t1-0, t1-1, …」单行省略，完整名单看位移 Tab。
                    DetailCell(
                        member.partitions.joinToString(", ") { "${it.topic}-${it.partition}" },
                        Modifier.weight(1.2f),
                        monospace = true,
                    )
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

/** 位移表：Topic / Partition / 当前位移 / 末端位移 / Lag / 所属成员。 */
@Composable
private fun OffsetTable(snapshot: KafkaGroupDetailSnapshot, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (snapshot.offsets.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource("kafka_ui_group_detail_no_offsets"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            DetailCell(stringResource("kafka_ui_group_detail_col_topic"), Modifier.weight(1.1f), bold = true)
            DetailCell(stringResource("kafka_ui_group_detail_col_partition"), width = COL_PARTITION, bold = true)
            DetailCell(stringResource("kafka_ui_group_detail_col_current"), width = COL_OFFSET, bold = true)
            DetailCell(stringResource("kafka_ui_group_detail_col_latest"), width = COL_OFFSET, bold = true)
            DetailCell(stringResource("kafka_ui_group_detail_col_lag"), width = COL_LAG, bold = true)
            DetailCell(stringResource("kafka_ui_group_detail_col_owner"), Modifier.weight(1f), bold = true)
        }
        HorizontalDivider()
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            snapshot.offsets.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DetailCell(row.topic, Modifier.weight(1.1f), monospace = true)
                    DetailCell(row.partition.toString(), width = COL_PARTITION, monospace = true)
                    DetailCell(row.currentOffset?.let(::formatLag) ?: "-", width = COL_OFFSET, monospace = true)
                    DetailCell(formatLag(row.latestOffset), width = COL_OFFSET, monospace = true)
                    // Lag 染色：0 次要色，非 0 警示橙（详情页没有阈值上下文，非 0 即突出）。
                    DetailCell(
                        formatLag(row.lag),
                        width = COL_LAG,
                        monospace = true,
                        color = if (row.lag > 0L) GroupStatusStyle.REBALANCE else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DetailCell(row.ownerId ?: "-", Modifier.weight(1f), monospace = true)
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

/** 表格单元格（同 MQ MemberTable 风格）。 */
@Composable
private fun DetailCell(
    text: String,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp? = null,
    bold: Boolean = false,
    monospace: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = color,
        modifier = if (width != null) modifier.width(width) else modifier,
    )
}

/** 重置档位的单选行。 */
@Composable
private fun ResetOptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 重置确认框的档位三选（service 侧 [KafkaGroupResetTo] 的 UI 投影）。 */
private enum class ResetMode { EARLIEST, LATEST, TIMESTAMP }

/** 时间档回显格式（选择器粒度到分钟，与浏览 Tab 的起点时间同款）。 */
private val RESET_TIME_DISPLAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 失败原因：解 ExecutionException 包装（与表单 failureMessage 同思路的简化版）。 */
private fun failureText(error: Throwable): String =
    (error as? java.util.concurrent.ExecutionException)?.cause?.message
        ?: error.message
        ?: error::class.simpleName
        ?: "unknown"

private val COL_PARTITION = 80.dp
private val COL_OFFSET = 110.dp
private val COL_LAG = 100.dp
