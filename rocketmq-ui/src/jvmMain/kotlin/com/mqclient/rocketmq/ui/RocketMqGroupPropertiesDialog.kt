package com.mqclient.rocketmq.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.state.LoadState
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.rocketmq.RocketMqGroupDetailSnapshot
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.RocketMqOffsetRowSnapshot
import com.mqclient.rocketmq.RocketMqProgressSource
import com.mqclient.rocketmq.RocketMqProgressState
import com.mqclient.uicommon.DangerButton
import com.mqclient.uicommon.PropertyItem
import com.mqclient.uicommon.PropertyTableSection
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.failureText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/** 积压告警色（>0 橙，与消费组卡片 Lag 告警同口径）。 */
private val LagOrange = Color(0xFFED6C02)

/**
 * 消费组详情**端到端**硬超时（毫秒）。
 *
 * 底层 RocketMQ 阻塞调用（`examineBrokerClusterInfo` / `examineSubscriptionGroupConfig` /
 * `examineConsumerConnectionInfo` / `examineConsumeStats`）经 socket 超时兜底，但若 Broker
 * 卡死 / 网络重试，单次调用就能挂到 connectionTimeout（默认 30s）。详情对话框绝不允许
 * 永久转圈，所以这里再罩一层协程级硬上限：无论底层怎么挂起，最多这么久必须退出 Loading
 * （落到 Error + 重试，而不是无限 spinner）。各子查询自身另有 4s 超时做局部降级。
 */
private const val GROUP_DETAIL_TIMEOUT_MS = 5_000L

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
    onDeleted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var detail by remember(groupName) {
        mutableStateOf<LoadState<RocketMqGroupDetailSnapshot>>(LoadState.Loading)
    }
    var tab by remember(groupName) { mutableIntStateOf(0) }
    val systemGroup = com.mqclient.rocketmq.isSystemGroup(groupName)
    var retryKey by remember(groupName) { mutableIntStateOf(0) }
    var deleteConfirm by remember(groupName) { mutableStateOf(false) }
    var deleting by remember(groupName) { mutableStateOf(false) }
    var deleteError by remember(groupName) { mutableStateOf<String?>(null) }

    LaunchedEffect(groupName, retryKey) {
        detail = LoadState.Loading
        try {
            val service = RocketMqModule.serviceOrNull()
                ?: throw IllegalStateException(stringResource("rocketmq_ui_conn_props_no_service"))
            detail = withTimeout(GROUP_DETAIL_TIMEOUT_MS) {
                LoadState.Success(service.fetchGroupDetail(connectionId, groupName))
            }
        } catch (e: TimeoutCancellationException) {
            detail = LoadState.Error(
                message = stringResource("rocketmq_ui_group_props_timeout")
                    .withArg((GROUP_DETAIL_TIMEOUT_MS / 1000).toString()),
            )
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

    fun delete() {
        val service = RocketMqModule.serviceOrNull() ?: run {
            deleteError = stringResource("rocketmq_ui_conn_props_no_service")
            return
        }
        scope.launch {
            deleting = true
            try {
                service.deleteConsumerGroup(connectionId, groupName)
                deleting = false
                deleteConfirm = false
                onDismiss()
                onDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deleting = false
                deleteError = failureText(e)
            }
        }
    }

    ObjectDetailDialog(
        title = stringResource("rocketmq_ui_group_props_title").withArg(groupName),
        onDismiss = onDismiss,
        minWidth = 680.dp,
        minHeight = 440.dp,
        footerActions = {
            // 系统保留消费组：不渲染删除按钮（service 层 isSystemGroup 防呆保留）。
            if (!systemGroup) {
                DangerButton(onClick = { deleteConfirm = true; deleteError = null }) {
                    Text(stringResource("rocketmq_ui_group_delete"))
                }
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton(
                onClick = onDismiss,
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

            // 删除二次确认（AlertDialog 独立窗口，必然在属性对话框之上）。失败
            // 原因在确认框内直出，绝不静默；成功走 onDeleted（scanNow + Snackbar）。
            if (deleteConfirm) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { if (!deleting) deleteConfirm = false },
                    title = { Text(stringResource("rocketmq_ui_group_delete_confirm_title")) },
                    text = {
                        Column {
                            Text(
                                text = stringResource("rocketmq_ui_group_delete_confirm_text").withArg(groupName),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            deleteError?.let { err ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        DangerButton(onClick = { delete() }, enabled = !deleting) {
                            Text(
                                stringResource(
                                    if (deleting) "rocketmq_ui_group_deleting" else "rocketmq_ui_group_delete",
                                ),
                            )
                        }
                    },
                    dismissButton = {
                        SecondaryButton(
                            onClick = { if (!deleting) deleteConfirm = false },
                        ) { Text(stringResource("common_cancel")) }
                    },
                )
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
        if (detail.groupMissing) {
            // 概览页也要说清楚：组在 broker 上根本没有订阅配置，下面的「未知」
            // 不是查询失败，而是它确实不存在。
            Text(
                text = stringResource("rocketmq_ui_group_props_group_missing_sub"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        PropertyTableSection(
            title = stringResource("rocketmq_ui_group_props_section_basic"),
            items = listOf(
                PropertyItem(stringResource("rocketmq_ui_group_props_name"), detail.name),
                PropertyItem(
                    stringResource("rocketmq_ui_group_props_consume_enable"),
                    booleanText(detail.consumeEnable),
                ),
                PropertyItem(
                    stringResource("rocketmq_ui_group_props_orderly"),
                    booleanText(detail.orderly),
                ),
                PropertyItem(
                    stringResource("rocketmq_ui_group_props_mode"),
                    consumeModeText(detail.consumeMode),
                ),
                PropertyItem(
                    stringResource("rocketmq_ui_group_props_subscriptions"),
                    if (detail.subscriptions.isEmpty()) {
                        stringResource("rocketmq_ui_group_props_no_subscriptions")
                    } else {
                        detail.subscriptions.joinToString("、")
                    },
                ),
            ),
        )
        Spacer(Modifier.height(12.dp))
        PropertyTableSection(
            title = stringResource("rocketmq_ui_group_props_section_metrics"),
            items = if (detail.progressState == RocketMqProgressState.OK) {
                listOf(
                    PropertyItem(stringResource("rocketmq_ui_group_props_total_lag"), detail.totalLag.toString()),
                    PropertyItem(
                        stringResource("rocketmq_ui_group_props_members"),
                        (detail.memberCount ?: 0).toString(),
                    ),
                )
            } else {
                // 进度不可用：占位行说明原因（不显示原始错误码）。
                listOf(
                    PropertyItem(
                        stringResource("rocketmq_ui_group_props_total_lag"),
                        stringResource("rocketmq_ui_group_props_metrics_unavailable"),
                        copyable = false,
                    ),
                    PropertyItem(stringResource("rocketmq_ui_group_props_members"), "0", copyable = false),
                )
            },
        )
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
internal fun ProgressTab(
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
                    if (detail.progressSource == RocketMqProgressSource.BROKER_FALLBACK) {
                        // 组级查询被 %RETRY% 路由挡住、位点改由 broker 直读取得。
                        // 必须说出来：否则用户会疑惑「刚才说查不到，现在怎么有数了」。
                        Text(
                            text = stringResource("rocketmq_ui_group_props_broker_fallback_note"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = PROGRESS_ROW_HPADDING, vertical = 4.dp),
                        )
                    }
                    // 表头与数据同档字号（bodyMedium）：概览里的层级差靠颜色
                    // （Key 列 onSurfaceVariant / Value 列 onSurface）表达，不靠字号。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PROGRESS_ROW_HPADDING, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProgressHeaderCell(stringResource("rocketmq_ui_group_props_col_broker"), Modifier.weight(2.5f))
                        ProgressHeaderCell(stringResource("rocketmq_ui_group_props_col_topic"), Modifier.weight(2.5f))
                        ProgressHeaderCell(stringResource("rocketmq_ui_group_props_col_queue"), Modifier.weight(1f))
                        ProgressHeaderCell(stringResource("rocketmq_ui_group_props_col_max"), Modifier.weight(1.8f))
                        ProgressHeaderCell(stringResource("rocketmq_ui_group_props_col_consumer"), Modifier.weight(1.8f))
                        ProgressHeaderCell(stringResource("rocketmq_ui_group_props_col_lag"), Modifier.weight(1.4f))
                    }
                    Spacer(Modifier.height(8.dp))
                    // LazyColumn：大组（百级 queue）也只组合可见行，滚动无压力。
                    // 行距 8dp / 斑马纹 / 40dp 行高与 PropertyTable 逐参数一致。
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(detail.rows) { index, row ->
                            OffsetRow(row, zebra = index % 2 == 1)
                        }
                    }
                }
            }
        }
        // CODE 17 / 其他失败：浅黄警告卡片（局部降级），原始错误码不进 UI。
        else -> {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                ProgressWarningBanner(detail = detail, onRetry = onRetry)
            }
        }
    }
}

/**
 * 进度不可用的警告卡片：**按「哪个环节缺东西」分档**，外加统一的「重试」。
 *
 * 分档规则见 [progressHint]（纯函数、有单测）：组不存在 / 无权限 / 从未有消费者
 * 上线 / CODE 17 / CODE 206 / 未知失败各一句自己的话。之前只有两档，而「其他」
 * 的副文案是「查询失败，可点击重试，或检查服务端连接与权限」—— 对用户没有任何
 * 判断价值。
 *
 * 配色：能自行恢复的「还没数据」几档用浅黄提示色；权限与未知失败用中性灰
 * （不是红色 —— 详情对话框本体是好的，只有这一 Tab 缺数据）。
 */
@Composable
private fun ProgressWarningBanner(
    detail: RocketMqGroupDetailSnapshot,
    onRetry: () -> Unit,
) {
    val hint = progressHint(detail)
    val accent = hint == ProgressHint.GROUP_MISSING ||
        hint == ProgressHint.NO_MEMBERS ||
        hint == ProgressHint.RETRY_TOPIC_NO_ROUTE ||
        hint == ProgressHint.NO_CONSUME_STATS ||
        hint == ProgressHint.TEMP_GROUP
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (accent) {
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
                tint = if (accent) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(progressHintTitleKey(hint)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (accent) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(Modifier.height(4.dp))
                // 超时等带键的失败：展示时点翻译（缺键回落英文兜底 progressError）。
                val progressErrorText = detail.progressErrorKey?.let { key ->
                    stringResource(key).takeIf { resolved -> resolved != key }
                        ?.withArg(*detail.progressErrorArgs.toTypedArray())
                } ?: detail.progressError
                Text(
                    text = progressHintBody(hint, progressErrorText),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accent) {
                        MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // 未知失败把原始摘要单独列出来（标题里已经说了「失败」，这里只补事实）。
                if (hint == ProgressHint.FAILED) {
                    progressErrorText?.takeIf { it.isNotBlank() }?.let { raw ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = raw,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                SecondaryButton(onClick = onRetry) {
                    Text(stringResource("common_retry"))
                }
            }
        }
    }
}

/** 各档的标题。 */
private fun progressHintTitleKey(hint: ProgressHint): String = when (hint) {
    ProgressHint.GROUP_MISSING -> "rocketmq_ui_group_props_progress_group_missing"
    ProgressHint.NO_PERMISSION -> "rocketmq_ui_group_props_progress_denied"
    ProgressHint.NO_MEMBERS -> "rocketmq_ui_group_props_progress_no_members"
    ProgressHint.RETRY_TOPIC_NO_ROUTE,
    ProgressHint.NO_CONSUME_STATS,
    ProgressHint.FAILED,
    ProgressHint.TEMP_GROUP,
    -> "rocketmq_ui_group_props_progress_unavailable"
}

/** 各档的副文案。 */
@Composable
private fun progressHintBody(hint: ProgressHint, error: String?): String = when (hint) {
    ProgressHint.GROUP_MISSING -> stringResource("rocketmq_ui_group_props_group_missing_sub")
    ProgressHint.NO_PERMISSION -> stringResource("rocketmq_ui_group_props_permission_sub")
    ProgressHint.NO_MEMBERS -> stringResource("rocketmq_ui_group_props_no_members_sub")
    ProgressHint.RETRY_TOPIC_NO_ROUTE -> stringResource("rocketmq_ui_group_props_no_route_sub")
    ProgressHint.NO_CONSUME_STATS -> stringResource("rocketmq_ui_group_props_no_stats_sub")
    ProgressHint.TEMP_GROUP -> stringResource("rocketmq_ui_group_props_progress_temp_group_sub")
    ProgressHint.FAILED -> {
        val detail = error?.trim()?.takeIf(String::isNotEmpty)
        if (detail != null) {
            stringResource("rocketmq_ui_group_props_failed_detail").withArg(detail)
        } else {
            stringResource("rocketmq_ui_group_props_failed_sub")
        }
    }
}

/**
 * 进度行：视觉**逐参数对齐概览的 [com.mqclient.uicommon.PropertyTable]** ——
 * 40dp 最小行高、奇数行斑马纹（surfaceVariant@30%）+ 4dp 圆角、10/8 内边距、
 * 正文 bodyMedium。lag > 0 用橙色（这一页唯一要一眼抓住的信息）。
 *
 * 强调色取代了原来的 `fontWeight = Medium`：主题是单字重字体，`FontWeight` 会被
 * Skia 伪粗（见 Theme.kt 的 FontSynthesis.None），颜色区分已经够清楚。
 */
@Composable
private fun OffsetRow(row: RocketMqOffsetRowSnapshot, zebra: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 测试钩子在 heightIn / padding 之前：语义节点的 bounds 是「此处之后
            // 全部修饰符占据的区域」，放在 padding 之后量到的是内容区高度。
            .testTag(if (zebra) PROGRESS_ZEBRA_ROW_TAG else PROGRESS_PLAIN_ROW_TAG)
            .heightIn(min = PROGRESS_ROW_MIN_HEIGHT)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (zebra) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                } else {
                    Color.Transparent
                },
            )
            .padding(horizontal = PROGRESS_ROW_HPADDING, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgressCell(row.broker, Modifier.weight(2.5f))
        ProgressCell(row.topic, Modifier.weight(2.5f))
        ProgressCell(row.queueId.toString(), Modifier.weight(1f))
        ProgressCell(row.maxOffset.toString(), Modifier.weight(1.8f))
        ProgressCell(row.consumerOffset.toString(), Modifier.weight(1.8f))
        ProgressCell(
            text = row.lag.toString(),
            modifier = Modifier.weight(1.4f),
            color = if (row.lag > 0) LagOrange else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ------------------------------ 进度表零件 ------------------------------ */

/** 进度表行高与 PropertyTable 一致（40dp）。 */
internal val PROGRESS_ROW_MIN_HEIGHT = 40.dp

/** 进度表行内水平内边距：与 PropertyTable 的行内 10dp 同值，两块文字左边缘才对得齐。 */
internal val PROGRESS_ROW_HPADDING = 10.dp

/** 斑马纹行 / 普通行的测试标签。 */
internal const val PROGRESS_ZEBRA_ROW_TAG = "progress_zebra_row"
internal const val PROGRESS_PLAIN_ROW_TAG = "progress_plain_row"

/** 表头单元格：bodyMedium + 次要色 —— 与 PropertyTable 的 Key 列同一档。 */
@Composable
private fun ProgressHeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 数据单元格：bodyMedium + 主色 —— 与 PropertyTable 的 Value 列同一档。 */
@Composable
private fun ProgressCell(
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
