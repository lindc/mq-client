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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.state.LoadState
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.rocketmq.RocketMqGroupDetailSnapshot
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.RocketMqOffsetRowSnapshot
import com.mqclient.rocketmq.RocketMqProgressState
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
 * —— service 层**局部降级**：基础配置（订阅组配置，不依赖 %RETRY% 路由）与成员/
 * 订阅 best-effort 恒尽力填充；进度失败（CODE 17：重试 Topic 无路由，组从未消费 /
 * POP 模式 / 未开自动创建的必然现象）不阻塞概览，只在进度 Tab 显示浅黄警告卡片。
 * 原始错误码（CODE: 17 DESC: ...）不进 UI，只在 service 层日志留痕。
 *
 * 整页 Error 仅兜底硬失败（模块未初始化 / 未连接 / clusterInfo 失败）；cause 只在
 * 有独立根因时传入 ErrorView（灰字），杜绝「红字灰字同文」的重复展示。
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
            detail = LoadState.Error(
                message = failureText(e),
                // 只在有独立根因时挂（无则不渲染灰字行，避免红灰同文重复）。
                cause = e.cause,
            )
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
                                ProgressTab(state.data, onRetry = { retryKey++ })
                            }
                        }
                    }
                }
                else -> {
                    // Loading / 硬失败：不渲染 Tab 骨架。ErrorView 主位 = service 层
                    // 转好的友好文案；灰字只在 cause 与主文案不同源时出现。
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
            PropertyRow(
                stringResource("rocketmq_ui_group_props_consume_enable"),
                booleanText(detail.consumeEnable),
            )
            PropertyRow(
                stringResource("rocketmq_ui_group_props_orderly"),
                booleanText(detail.orderly),
            )
            PropertyRow(stringResource("rocketmq_ui_group_props_mode"), consumeModeText(detail.consumeMode))
            PropertyRow(
                stringResource("rocketmq_ui_group_props_subscriptions"),
                if (detail.subscriptions.isEmpty()) {
                    stringResource("rocketmq_ui_group_props_no_subscriptions")
                } else {
                    detail.subscriptions.joinToString("、")
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        PropertySection(title = stringResource("rocketmq_ui_group_props_section_metrics")) {
            if (detail.progressState == RocketMqProgressState.OK) {
                PropertyRow(stringResource("rocketmq_ui_group_props_total_lag"), detail.totalLag.toString())
                PropertyRow(
                    stringResource("rocketmq_ui_group_props_members"),
                    (detail.memberCount ?: 0).toString(),
                )
            } else {
                Text(
                    text = stringResource("rocketmq_ui_group_props_metrics_unavailable"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 三态布尔 → 人类可读（null = 查询失败，显示未知）。 */
@Composable
private fun booleanText(value: Boolean?): String = when (value) {
    true -> stringResource("rocketmq_ui_group_props_value_enabled")
    false -> stringResource("rocketmq_ui_group_props_value_disabled")
    null -> stringResource("rocketmq_ui_group_props_value_unknown")
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
private fun ProgressTab(
    detail: RocketMqGroupDetailSnapshot,
    onRetry: () -> Unit,
) {
    when (detail.progressState) {
        RocketMqProgressState.OK -> {
            if (detail.rows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource("rocketmq_ui_group_props_no_rows"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
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
        }
        // CODE 17 / 其他失败：浅黄警告卡片（局部降级），原始错误码不进 UI。
        else -> {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                ProgressWarningBanner(state = detail.progressState, onRetry = onRetry)
            }
        }
    }
}

/**
 * 进度不可用的警告卡片：NO_ROUTE = 浅黄（tertiaryContainer）+ 路由缺失的翻译
 * 副文案；FAILED = 中性灰卡 + 通用失败副文案。均带重试按钮，零原始错误码。
 */
@Composable
private fun ProgressWarningBanner(
    state: RocketMqProgressState,
    onRetry: () -> Unit,
) {
    val noRoute = state == RocketMqProgressState.RETRY_TOPIC_NO_ROUTE
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (noRoute) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = if (noRoute) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource("rocketmq_ui_group_props_progress_unavailable"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (noRoute) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (noRoute) {
                            "rocketmq_ui_group_props_no_route_sub"
                        } else {
                            "rocketmq_ui_group_props_failed_sub"
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (noRoute) {
                        MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onRetry) {
                    Text(stringResource("common_retry"))
                }
            }
        }
    }
}

/** 进度行：斑马纹 + lag>0 橙色醒目；数字 monospace。 */
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
            fontWeight = if (row.lag > 0) FontWeight.Medium else null,
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
