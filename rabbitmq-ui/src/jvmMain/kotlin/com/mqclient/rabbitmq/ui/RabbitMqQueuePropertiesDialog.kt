package com.mqclient.rabbitmq.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.mqui.state.LoadState
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.RabbitMqQueueDetailSnapshot
import com.mqclient.uicommon.DangerButton
import com.mqclient.uicommon.PropertyItem
import com.mqclient.uicommon.PropertyTable
import com.mqclient.uicommon.PropertyTableSection
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.failureText
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 死信类 argument 的判定前缀（description 行标「死信相关」）。 */
private const val DEAD_LETTER_PREFIX = "x-dead-letter"

/** 限制类 argument：TTL / 长度 / 过期 / 优先级（description 行标「资源限制」）。 */
private val LIMIT_KEYS = setOf("x-message-ttl", "x-max-length", "x-expires", "x-max-priority")

/**
 * 详情加载的 UI 层兜底超时，覆盖整条详情（概览 / 参数 / 消费者三个 Tab 同源一次 GET）。
 *
 * **为什么比 HttpClient 的 5s（[com.mqclient.rabbitmq.RabbitMqAdminClient.DETAIL_TIMEOUT_MS]）
 * 更长**：两道超时管不同的事。客户端那道是请求本身的截止时间，到点抛带「请求超时」
 * 文案的异常，用户看到的是具体原因；这道只是「协程层绝不无限等下去」的最后一道保险
 * （防 service 层在请求之外卡住，例如取 context / 切线程）。所以刻意让它晚 1s 触
 * 发，让更具体的那个文案先说话 —— 两道都设 5s 的话，谁先到不确定，提示会在
 * 「请求超时」和笼统的「数据加载超时」之间摇摆。
 */
private const val DETAIL_TIMEOUT_MS = 6_000L

/**
 * 消费者表格单次渲染行数上限。
 *
 * 大数据消费者队列（数千个 consumer）的 `consumer_details` 既大又没必要整表呈现：
 * 排查问题看前几十行就够，超出的用一行提示告知总数。配上 LazyColumn，开表的
 * 组合与布局成本与这个上限有关，与真实消费者数无关。
 */
private const val MAX_RENDERED_CONSUMERS = 200

/**
 * 队列属性对话框（队列卡片 ⓘ / 拓扑双击 queue 入口）：ObjectDetailDialog 外壳 +
 * PrimaryTabRow 三个 Tab（概览 / 参数 / 消费者），与 RabbitMqSendMessageDialog
 * 同款 Tab。
 *
 * 数据：打开即实时拉详情（[RabbitMqModule] service queueDetail —— 慢扫快照没有
 * arguments 与 queue_type），LoadState 三态。**三个 Tab 共用这一次请求**：消费者明细
 * 就在详情的 `consumer_details` 字段里（管理 API 没有「某队列的消费者」端点），
 * 所以消费者 Tab 没有自己的加载态、也不会再卡在转圈上。
 *
 * 版式：概览与参数统一走 ui-common 的 [PropertyTableSection] / [PropertyTable]
 * （斑马纹、Key 左对齐、值可拖选、行尾一键复制），与 Kafka / RocketMQ 的属性面板
 * 同一套视觉，模块内不再维护手写 Row/Column 属性行。参数里的死信 / 限制类键用
 * [PropertyItem.description] 小字标注，不再靠行首图标。
 *
 * 底部：清空队列（普通按钮 + 警示图标）与删除队列（danger 红），均二次确认
 * （AlertDialog 声明在本外壳 content lambda 内 —— 窗口归属随组合走）；执行中
 * operating 锁全部按钮。删除成功 onDismiss + [onDeleted]（面板刷新 + Snackbar）。
 */
@Composable
fun RabbitMqQueuePropertiesDialog(
    connectionId: String,
    queueName: String,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selectedTab by remember(queueName) { mutableIntStateOf(0) }
    var detailState by remember(queueName) { mutableStateOf<LoadState<RabbitMqQueueDetailSnapshot>>(LoadState.Idle) }
    var purgeConfirm by remember(queueName) { mutableStateOf(false) }
    var deleteConfirm by remember(queueName) { mutableStateOf(false) }
    var operating by remember(queueName) { mutableStateOf(false) }

    // 文案在组合里取好再进协程：suspend 块里不能调 @Composable。
    val timeoutMessage = stringResource("rabbitmq_ui_conn_props_timeout")
    val noServiceMessage = stringResource("rabbitmq_ui_conn_props_no_service")

    // 连接状态：打开瞬间可能恰好处于 Connecting / 刚恢复，错误不能粘住。
    val connectionStatus = rememberRabbitMqConnectionStatus(connectionId)

    fun load() {
        scope.launch {
            detailState = LoadState.Loading
            detailState = loadDetail(timeoutMessage) {
                val service = RabbitMqModule.serviceOrNull() ?: error(noServiceMessage)
                service.queueDetail(connectionId, queueName)
            }
        }
    }

    LaunchedEffect(connectionId, queueName) { load() }

    // 时序兜底：对话框打开时若连接正在建立（或在抖动后自动恢复），等状态落定再重拉。
    // 只在当前是错误态时触发 —— 已经拿到数据的对话框不该因为一次背景状态变化就闪。
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.Connected && detailState is LoadState.Error) {
            load()
        }
    }

    fun purge() {
        scope.launch {
            val service = RabbitMqModule.serviceOrNull() ?: error("服务未初始化")
            operating = true
            try {
                service.purgeQueue(connectionId, queueName)
                purgeConfirm = false
                operating = false
                snackbar.showSnackbar(stringResource("rabbitmq_ui_queue_purge_done"))
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                operating = false
                purgeConfirm = false
                snackbar.showSnackbar(failureText(e))
            }
        }
    }

    fun delete() {
        scope.launch {
            val service = RabbitMqModule.serviceOrNull() ?: error("服务未初始化")
            operating = true
            try {
                service.deleteQueue(connectionId, queueName)
                operating = false
                onDismiss()
                onDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                operating = false
                deleteConfirm = false
                snackbar.showSnackbar(failureText(e))
            }
        }
    }

    ObjectDetailDialog(
        title = stringResource("rabbitmq_ui_queue_props_title").withArg(queueName),
        onDismiss = onDismiss,
        footerActions = {
            DangerButton(onClick = { purgeConfirm = true }, enabled = !operating) {
                Text(stringResource("rabbitmq_ui_queue_purge"))
            }
            Spacer(Modifier.width(12.dp))
            DangerButton(onClick = { deleteConfirm = true }, enabled = !operating) {
                Text(stringResource("rabbitmq_ui_queue_delete"))
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton(
                onClick = onDismiss,
            ) { Text(stringResource("common_close")) }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                when (val s = detailState) {
                    is LoadState.Idle, is LoadState.Loading -> {
                        // weight(1f) 而不是 fillMaxSize()：显式吃掉剩余高度并在内部居中，
                        // 不依赖「刚好是唯一子项」这个巧合（与交换机属性对话框同口径）。
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    is LoadState.Error -> {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource("rabbitmq_ui_queue_props_load_failed").withArg(s.message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            SecondaryButton(onClick = { load() }) { Text(stringResource("rabbitmq_ui_refresh")) }
                        }
                    }
                    is LoadState.Success -> {
                        val detail = s.data
                        // flow 横幅：被阻塞必须第一眼看到（在 Tab 之上）。
                        if (detail.state == "flow") {
                            FlowBanner()
                            Spacer(Modifier.height(12.dp))
                        }
                        PrimaryTabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text(stringResource("rabbitmq_ui_queue_props_tab_overview")) },
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text(stringResource("rabbitmq_ui_queue_props_tab_args")) },
                            )
                            Tab(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                text = { Text(stringResource("rabbitmq_ui_queue_props_tab_consumers")) },
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (selectedTab) {
                                0 -> OverviewPane(detail)
                                1 -> ArgumentsPane(detail.arguments)
                                else -> ConsumersPane(detail, ::load)
                            }
                        }
                    }
                }
            }
            ThemedSnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }

        // ---- 清空确认（嵌套声明在本外壳 content lambda 内）----
        if (purgeConfirm) {
            AlertDialog(
                onDismissRequest = { if (!operating) purgeConfirm = false },
                title = { Text(stringResource("rabbitmq_ui_queue_purge_confirm_title")) },
                text = {
                    Text(
                        text = stringResource("rabbitmq_ui_queue_purge_confirm_text").withArg(queueName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    DangerButton(onClick = { purge() }, enabled = !operating) {
                        Text(stringResource("rabbitmq_ui_queue_purge"))
                    }
                },
                dismissButton = {
                    SecondaryButton(
                        onClick = { purgeConfirm = false },
                    ) { Text(stringResource("common_cancel")) }
                },
            )
        }

        // ---- 删除确认 ----
        if (deleteConfirm) {
            AlertDialog(
                onDismissRequest = { if (!operating) deleteConfirm = false },
                title = { Text(stringResource("rabbitmq_ui_queue_delete_confirm_title")) },
                text = {
                    Text(
                        text = stringResource("rabbitmq_ui_queue_delete_confirm_text").withArg(queueName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    DangerButton(onClick = { delete() }, enabled = !operating) {
                        Text(stringResource("rabbitmq_ui_queue_delete"))
                    }
                },
                dismissButton = {
                    SecondaryButton(
                        onClick = { deleteConfirm = false },
                    ) { Text(stringResource("common_cancel")) }
                },
            )
        }
    }
}

/* ------------------------------ Tab 1：概览 ------------------------------ */

@Composable
private fun FlowBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource("rabbitmq_ui_queue_flow_banner"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun OverviewPane(detail: RabbitMqQueueDetailSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        PropertyTableSection(
            maxWidth = Dp.Infinity,
            title = stringResource("rabbitmq_ui_queue_props_section_basic"),
            items = listOf(
                PropertyItem(stringResource("rabbitmq_ui_queue_field_state_label"), detail.state),
                PropertyItem(stringResource("rabbitmq_ui_queue_field_type"), queueTypeText(detail.queueType)),
                PropertyItem(stringResource("rabbitmq_ui_exchange_field_durable"), yesNo(detail.durable)),
                PropertyItem(stringResource("rabbitmq_ui_exchange_field_auto_delete"), yesNo(detail.autoDelete)),
                PropertyItem(stringResource("rabbitmq_ui_queue_field_exclusive"), yesNo(detail.exclusive)),
            ),
        )
        Spacer(Modifier.height(16.dp))
        PropertyTableSection(
            maxWidth = Dp.Infinity,
            title = stringResource("rabbitmq_ui_queue_props_section_metrics"),
            items = listOf(
                PropertyItem(stringResource("rabbitmq_ui_overview_total_messages"), detail.messages.toString()),
                PropertyItem(stringResource("rabbitmq_ui_queue_field_ready"), detail.messagesReady.toString()),
                PropertyItem(
                    stringResource("rabbitmq_ui_queue_field_unacked"),
                    detail.messagesUnacknowledged.toString(),
                ),
                PropertyItem(stringResource("rabbitmq_ui_queue_metric_consumers"), detail.consumers.toString()),
            ),
        )
    }
}

/** 队列类型本地化：classic / quorum 有译名，其余（stream 等）原样显示。 */
@Composable
private fun queueTypeText(queueType: String): String = when (queueType) {
    "classic" -> stringResource("rabbitmq_ui_queue_type_classic")
    "quorum" -> stringResource("rabbitmq_ui_queue_type_quorum")
    else -> queueType
}

@Composable
private fun yesNo(value: Boolean): String =
    stringResource(if (value) "rabbitmq_ui_exchange_props_yes" else "rabbitmq_ui_exchange_props_no")

/* ------------------------------ Tab 2：参数 ------------------------------ */

/**
 * 参数 Tab：arguments 直接映射成 [PropertyItem]（按 key 排序，扫一遍有固定顺序）。
 *
 * 死信类 / 限制类的标注走 [PropertyItem.description] —— 之前是行首红橙图标 + 值可
 * 点击跳转；为了复用统一表格，跳转换成了纯文本（组件不支持值点击），换来斑马纹、
 * 左对齐与一键复制，也和 Kafka / RocketMQ 的属性面板对齐。
 */
@Composable
private fun ArgumentsPane(arguments: Map<String, String>) {
    if (arguments.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("rabbitmq_ui_queue_args_empty"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        PropertyTable(
            items = arguments.entries.sortedBy { it.key }.map { (key, value) ->
                PropertyItem(
                    key = key,
                    value = value,
                    description = argumentDescription(key),
                )
            },
        )
    }
}

/** 参数说明行：死信类 / 资源限制类各一行小字，其余无说明。 */
@Composable
private fun argumentDescription(key: String): String? = when {
    key.startsWith(DEAD_LETTER_PREFIX) -> stringResource("rabbitmq_ui_queue_args_dead_letter")
    key in LIMIT_KEYS -> stringResource("rabbitmq_ui_queue_args_limit")
    else -> null
}

/* ------------------------------ Tab 3：消费者 ------------------------------ */

/**
 * 消费者 Tab：四列表格（Consumer Tag / 连接 IP / 端口 / Ack Required），数据来自
 * 本次详情响应里的 `consumer_details` —— **没有独立请求、没有独立加载态**，
 * 打开对话框那次 GET 成功还是失败，这里就一定是终态。
 *
 * 两种「空」必须分开：
 *  - [RabbitMqQueueDetailSnapshot.consumerDetails] 空且 `consumers == 0` → 真的没有消费者；
 *  - 空但 `consumers > 0` → broker 没给明细（老版本 / 关了 metrics collector），
 *    这时说「暂无消费者」就是在骗人，改为提示「明细不可用」并给一个刷新。
 */
@Composable
private fun ConsumersPane(
    detail: RabbitMqQueueDetailSnapshot,
    onRefresh: () -> Unit,
) {
    val consumers = detail.consumerDetails
    if (consumers.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (detail.consumers > 0) {
                    stringResource("rabbitmq_ui_consumers_unavailable").withArg(detail.consumers)
                } else {
                    stringResource("rabbitmq_ui_consumers_empty")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (detail.consumers > 0) {
                Spacer(Modifier.height(12.dp))
                SecondaryButton(onClick = onRefresh) { Text(stringResource("rabbitmq_ui_refresh")) }
            }
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                text = stringResource("rabbitmq_ui_consumers_col_tag"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1.4f),
            )
            Text(
                text = stringResource("rabbitmq_ui_consumers_col_host"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource("rabbitmq_ui_consumers_col_port"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.5f),
            )
            Text(
                text = stringResource("rabbitmq_ui_consumers_col_ack"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.5f),
            )
        }
        HorizontalDivider()
        // 上限 + LazyColumn：组合与布局成本与真实消费者数解耦（详见 [MAX_RENDERED_CONSUMERS]）。
        val shown = consumers.take(MAX_RENDERED_CONSUMERS)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(shown) { consumer ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = consumer.consumerTag,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1.4f),
                    )
                    Text(
                        text = consumer.peerHost ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = consumer.peerPort?.toString() ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.5f),
                    )
                    Text(
                        text = stringResource(
                            if (consumer.ackRequired) "rabbitmq_ui_exchange_props_yes" else "rabbitmq_ui_exchange_props_no",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(0.5f),
                    )
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
            if (consumers.size > shown.size) {
                item {
                    Text(
                        text = stringResource("rabbitmq_ui_consumers_truncated")
                            .withArg(consumers.size, shown.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/* ------------------------------- 加载 ------------------------------- */

/**
 * 详情加载的**唯一出口**：成功 / 失败 / 超时一律返回终态，绝不返回 Loading。
 *
 * 分支顺序是这里最要紧的部分：[TimeoutCancellationException] 是
 * [CancellationException] 的子类，必须先接住 —— 否则超时会被当成「组合已销毁」
 * 原样抛走，界面就永远停在转圈上。真正的外层取消（对话框关闭 / 宿主退出）仍然原样
 * 上抛，那时没人再看这个状态。
 */
private suspend fun loadDetail(
    timeoutMessage: String,
    block: suspend () -> RabbitMqQueueDetailSnapshot,
): LoadState<RabbitMqQueueDetailSnapshot> = try {
    LoadState.Success(withTimeout(DETAIL_TIMEOUT_MS) { block() })
} catch (e: TimeoutCancellationException) {
    LoadState.Error(timeoutMessage)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    LoadState.Error(failureText(e))
}
