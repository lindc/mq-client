package com.mqclient.rocketmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.state.LoadState
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.rocketmq.RocketMqGroupDetailSnapshot
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.RocketMqOffsetRowSnapshot
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.PropertyRow
import com.mqclient.uicommon.PropertySection
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException

/** 积压告警色（>0 橙，与消费组卡片 Lag 告警同口径）。 */
private val LagOrange = Color(0xFFED6C02)

/**
 * 消费组属性对话框（消费组卡片 ⓘ 入口）：ObjectDetailDialog 外壳 + PrimaryTabRow
 * 两 Tab（概览 / 消费进度），骨架对齐 RocketMqTopicPropertiesDialog。
 *
 * 数据：进对话框异步现查 [com.mqclient.rocketmq.RocketMqScanService.fetchGroupDetail]
 * （成员 / 消费模式 / 订阅 + **service 层已拍平**的逐 queue 位点行），三态兜底
 * （Loading 转圈 / Error 原因 + 重试 / Success）——组从未消费等现查失败时进度
 * Tab 给明确报错，不阻塞概览 Tab 的静态部分以外的展示。只读展示，无写操作。
 */
@Composable
fun RocketMqGroupPropertiesDialog(
    connectionId: String,
    groupName: String,
    onDismiss: () -> Unit,
) {
    var detail by remember(groupName) {
        mutableStateOf<LoadState<RocketMqGroupDetailSnapshot>>(LoadState.Loading)
    }
    var tab by remember(groupName) { mutableIntStateOf(0) }
    var retryKey by remember(groupName) { mutableIntStateOf(0) }

    LaunchedEffect(groupName, retryKey) {
        detail = LoadState.Loading
        try {
            val service = RocketMqModule.serviceOrNull()
                ?: throw IllegalStateException(stringResource("rocketmq_ui_conn_props_no_service"))
            detail = LoadState.Success(service.fetchGroupDetail(connectionId, groupName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            detail = LoadState.Error(failureText(e))
        }
    }

    ObjectDetailDialog(
        title = stringResource("rocketmq_ui_group_props_title").withArg(groupName),
        onDismiss = onDismiss,
        minWidth = 680.dp,
        minHeight = 440.dp,
        footerActions = {
            Spacer(Modifier.weight(1f))
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_close")) }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = detail) {
                is LoadState.Success -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            Tab(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                text = { Text(stringResource("rocketmq_ui_group_props_tab_overview")) },
                            )
                            Tab(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                text = { Text(stringResource("rocketmq_ui_group_props_tab_progress")) },
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (tab == 0) {
                                OverviewTab(state.data)
                            } else {
                                ProgressTab(state.data)
                            }
                        }
                    }
                }
                else -> {
                    // Loading / Error：不渲染 Tab 骨架，直接给居中转圈或错误 + 重试。
                    LoadStateContent(
                        state = state,
                        onRetry = { retryKey++ },
                        loadingMessage = stringResource("rocketmq_ui_group_props_loading"),
                        modifier = Modifier.fillMaxSize(),
                    ) { }
                }
            }
        }
    }
}

/* ------------------------------ Tab 1：概览 ------------------------------ */

@Composable
private fun OverviewTab(detail: RocketMqGroupDetailSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        PropertySection(title = stringResource("rocketmq_ui_group_props_section_basic")) {
            PropertyRow(stringResource("rocketmq_ui_group_props_name"), detail.name)
            PropertyRow(stringResource("rocketmq_ui_group_props_mode"), consumeModeText(detail.consumeMode))
            PropertyRow(stringResource("rocketmq_ui_group_props_subscriptions"),
                if (detail.subscriptions.isEmpty()) {
                    stringResource("rocketmq_ui_group_props_no_subscriptions")
                } else {
                    detail.subscriptions.joinToString("、")
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        PropertySection(title = stringResource("rocketmq_ui_group_props_section_metrics")) {
            PropertyRow(stringResource("rocketmq_ui_group_props_total_lag"), detail.totalLag.toString())
            PropertyRow(stringResource("rocketmq_ui_group_props_members"), detail.memberCount.toString())
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 消费模式枚举名 → 人类可读（CLUSTERING 集群 / BROADCASTING 广播，未知原样展示）。 */
@Composable
private fun consumeModeText(raw: String): String = when (raw) {
    "CLUSTERING" -> stringResource("rocketmq_ui_group_props_mode_clustering")
    "BROADCASTING" -> stringResource("rocketmq_ui_group_props_mode_broadcasting")
    "" -> "-"
    else -> raw
}

/* ------------------------------ Tab 2：消费进度 ------------------------------ */

@Composable
private fun ProgressTab(detail: RocketMqGroupDetailSnapshot) {
    if (detail.rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("rocketmq_ui_group_props_no_rows"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(stringResource("rocketmq_ui_group_props_col_broker"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(2.5f))
            Text(stringResource("rocketmq_ui_group_props_col_topic"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(2.5f))
            Text(stringResource("rocketmq_ui_group_props_col_queue"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(stringResource("rocketmq_ui_group_props_col_max"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.8f))
            Text(stringResource("rocketmq_ui_group_props_col_consumer"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.8f))
            Text(stringResource("rocketmq_ui_group_props_col_lag"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.4f))
        }
        HorizontalDivider()
        // LazyColumn：大组（百级 queue）也只组合可见行，滚动无压力。
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(detail.rows.size) { index ->
                OffsetRow(detail.rows[index])
            }
        }
    }
}

/** 进度行：斑马纹 + lag>0 橙色醒目；数字右对齐 monospace。 */
@Composable
private fun OffsetRow(row: RocketMqOffsetRowSnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.broker, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2.5f))
        Text(row.topic, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2.5f))
        Text(row.queueId.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(row.maxOffset.toString(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.8f))
        Text(row.consumerOffset.toString(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1.8f))
        Text(
            text = row.lag.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (row.lag > 0) androidx.compose.ui.text.font.FontWeight.Medium else null,
            color = if (row.lag > 0) LagOrange else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.4f),
        )
    }
}

/** 失败原因：解 ExecutionException 包装（与其他对话框调用处同一简化版）。 */
private fun failureText(error: Throwable): String =
    (error as? java.util.concurrent.ExecutionException)?.cause?.message
        ?: error.message
        ?: error::class.simpleName
        ?: "unknown"
