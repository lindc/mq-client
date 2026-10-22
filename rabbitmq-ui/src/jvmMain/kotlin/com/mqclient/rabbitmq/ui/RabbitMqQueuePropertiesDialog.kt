package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.state.LoadState
import androidx.compose.material.icons.filled.Circle
import com.mqclient.core.CoreModule
import com.mqclient.core.event.EventNames
import com.mqclient.rabbitmq.RabbitMqConsumerSnapshot
import com.mqclient.rabbitmq.RabbitMqQueueDetailSnapshot
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 死信类 argument：行首红色 Error 图标 + error 文字 —— 排查死信时最关心的键。 */
private val DEAD_LETTER_KEYS = setOf("x-dead-letter-exchange", "x-dead-letter-routing-key")

/** 限制类 argument：行首警告橙图标（TTL / 长度 / 过期 / 优先级）。 */
private val LIMIT_KEYS = setOf("x-message-ttl", "x-max-length", "x-expires", "x-max-priority")

private val ArgsKeyError = Color(0xFFD32F2F)

/**
 * 队列属性对话框（队列卡片 ⓘ / 拓扑双击 queue 入口）：ObjectDetailDialog 外壳 +
 * PrimaryTabRow 三个 Tab（概览 / 参数 / 消费者），与 RabbitMqSendMessageDialog
 * 同款 Tab。
 *
 * 数据：打开即实时拉详情（[RabbitMqModule] service queueDetail —— 慢扫快照没有
 * arguments 与 queue_type），LoadState 三态。消费者 Tab 选中时才懒加载（避免打开
 * 对话框就多打一次 HTTP，同新鲜数据哲学）。只读展示（RabbitMQ 不支持改队列参数）。
 *
 * 参数 Tab：死信类参数值渲染为链接 —— x-dead-letter-exchange 命中慢扫缓存里的
 * 交换机时点击跳交换机页开属性；x-dead-letter-routing-key 命中缓存队列名时跳
 * 队列页开属性（找不到则纯文本不可点）。
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
    var consumersState by remember(queueName) { mutableStateOf<LoadState<List<RabbitMqConsumerSnapshot>>>(LoadState.Idle) }
    var purgeConfirm by remember(queueName) { mutableStateOf(false) }
    var deleteConfirm by remember(queueName) { mutableStateOf(false) }
    var operating by remember(queueName) { mutableStateOf(false) }

    fun load() {
        scope.launch {
            detailState = LoadState.Loading
            detailState = runCatching {
                val service = RabbitMqModule.serviceOrNull() ?: error("服务未初始化")
                LoadState.Success(service.queueDetail(connectionId, queueName))
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                LoadState.Error(failureText(e))
            }
        }
    }

    LaunchedEffect(connectionId, queueName) { load() }

    // 消费者 Tab 懒加载：每次选中都重拉（新鲜数据哲学，一次 GET 很轻）。
    fun loadConsumers() {
        scope.launch {
            consumersState = LoadState.Loading
            consumersState = runCatching {
                val service = RabbitMqModule.serviceOrNull() ?: error("服务未初始化")
                LoadState.Success(service.queueConsumers(connectionId, queueName))
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                LoadState.Error(failureText(e))
            }
        }
    }

    LaunchedEffect(connectionId, queueName, selectedTab) {
        if (selectedTab == 2) loadConsumers()
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
        minWidth = 660.dp,
        minHeight = 560.dp,
        footerActions = {
            PressableButton(onClick = { purgeConfirm = true }, enabled = !operating, danger = true) {
                Text(stringResource("rabbitmq_ui_queue_purge"))
            }
            Spacer(Modifier.width(12.dp))
            PressableButton(onClick = { deleteConfirm = true }, enabled = !operating, danger = true) {
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
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is LoadState.Error -> {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
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
                                1 -> ArgumentsPane(connectionId, detail.arguments, onDismiss)
                                else -> ConsumersPane(consumersState, ::loadConsumers)
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
                    PressableButton(onClick = { purge() }, enabled = !operating, danger = true) {
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
                    PressableButton(onClick = { delete() }, enabled = !operating, danger = true) {
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
    // 指标行：unacked 大于积压阈值（与概览同口径 1000）→ 警告橙。
    val unackedHot = detail.messagesUnacknowledged >= BACKLOG_THRESHOLD
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource("rabbitmq_ui_queue_props_section_basic"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        // 状态：色点 + 文字（色板复用 rabbitMqQueueStateColor）。
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource("rabbitmq_ui_queue_field_state_label"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(120.dp),
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Circle,
                    contentDescription = null,
                    tint = rabbitMqQueueStateColor(detail.state) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(10.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = detail.state ?: "-",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        PropertyLine(
            stringResource("rabbitmq_ui_queue_field_type"),
            when (detail.queueType) {
                "classic" -> stringResource("rabbitmq_ui_queue_type_classic")
                "quorum" -> stringResource("rabbitmq_ui_queue_type_quorum")
                else -> detail.queueType
            },
        )
        PropertyLine(
            stringResource("rabbitmq_ui_exchange_field_durable"),
            stringResource(if (detail.durable) "rabbitmq_ui_exchange_props_yes" else "rabbitmq_ui_exchange_props_no"),
        )
        PropertyLine(
            stringResource("rabbitmq_ui_exchange_field_auto_delete"),
            stringResource(if (detail.autoDelete) "rabbitmq_ui_exchange_props_yes" else "rabbitmq_ui_exchange_props_no"),
        )
        PropertyLine(
            stringResource("rabbitmq_ui_queue_field_exclusive"),
            stringResource(if (detail.exclusive) "rabbitmq_ui_exchange_props_yes" else "rabbitmq_ui_exchange_props_no"),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource("rabbitmq_ui_queue_props_section_metrics"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        PropertyLine(stringResource("rabbitmq_ui_overview_total_messages"), detail.messages.toString())
        PropertyLine(stringResource("rabbitmq_ui_queue_field_ready"), detail.messagesReady.toString())
        PropertyLine(
            stringResource("rabbitmq_ui_queue_field_unacked"),
            detail.messagesUnacknowledged.toString(),
            valueColor = if (unackedHot) RabbitMqWarningOrange else null,
        )
        PropertyLine(stringResource("rabbitmq_ui_queue_metric_consumers"), detail.consumers.toString())
    }
}

/* ------------------------------ Tab 2：参数 ------------------------------ */

/**
 * 死信参数的跳转目标（链接化判定）：value 命中慢扫缓存才可点 ——
 * x-dead-letter-exchange → 交换机（默认交换机 name="" 不跳，列表里没有实体）；
 * x-dead-letter-routing-key → 仅当它恰好是某个队列的名字。
 * 判定走缓存零网络开销；找不到就纯文本，不给「点了没反应」的死链。
 */
private fun dlxLinkTarget(connectionId: String, key: String, value: String): String? {
    val service = RabbitMqModule.serviceOrNull() ?: return null
    return when {
        key == "x-dead-letter-exchange" && value.isNotEmpty() &&
            service.cachedExchanges(connectionId).any { it.name == value } -> "exchange"
        key == "x-dead-letter-routing-key" && value.isNotEmpty() &&
            service.cachedQueues(connectionId).any { it.name == value } -> "queue"
        else -> null
    }
}

/**
 * 死信链接点击的跳转：先关当前队列属性弹窗（防 UI 叠层），再发 OBJECT_TAB_OPEN_REQUESTED
 * 切主 Tab（宿主 MainWindow onSelect + openTab），最后留 focus hint 给目标面板 ——
 * 列表就绪后自动打开对象属性对话框（RabbitMqExchangesPanel / RabbitMqQueuesPanel 消费）。
 */
private fun jumpToDlxTarget(connectionId: String, kind: String, name: String, dismissCurrent: () -> Unit) {
    dismissCurrent()
    CoreModule.eventBusOrNull?.emit(
        name = EventNames.OBJECT_TAB_OPEN_REQUESTED,
        payload = mapOf(
            "connectionId" to connectionId,
            "objectType" to if (kind == "exchange") "exchanges" else "queues",
        ),
        source = "rabbitmq",
    )
    RabbitMqUiHints.requestObjectFocus(connectionId, kind, name)
}

@Composable
private fun ArgumentsPane(connectionId: String, arguments: Map<String, String>, dismissCurrent: () -> Unit) {
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
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                text = "Key",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1.2f),
            )
            Text(
                text = "Value",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            arguments.forEach { (key, value) ->
                val isDeadLetter = key in DEAD_LETTER_KEYS || key.startsWith("x-dead-letter")
                val isLimit = key in LIMIT_KEYS
                // 死信参数的值命中缓存对象 → 链接（primary 色 + 下划线 + 小手）。
                val linkTarget = if (isDeadLetter) dlxLinkTarget(connectionId, key, value) else null
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 固定图标位：有图标放图标，无图标空占位 —— 所有 key 同一垂直线。
                    Box(modifier = Modifier.width(24.dp)) {
                        if (isDeadLetter || isLimit) {
                            Icon(
                                imageVector = if (isDeadLetter) Icons.Default.Error else Icons.Default.Warning,
                                contentDescription = stringResource(
                                    if (isDeadLetter) "rabbitmq_ui_queue_args_dead_letter" else "rabbitmq_ui_queue_args_limit",
                                ),
                                tint = if (isDeadLetter) ArgsKeyError else RabbitMqWarningOrange,
                                modifier = Modifier.size(14.dp).align(Alignment.CenterStart),
                            )
                        }
                    }
                    Text(
                        text = key,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isDeadLetter) ArgsKeyError else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1.2f),
                    )
                    if (linkTarget != null) {
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .weight(1f)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { jumpToDlxTarget(connectionId, linkTarget, value, dismissCurrent) },
                        )
                    } else {
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

/* ------------------------------ Tab 3：消费者 ------------------------------ */

/**
 * 消费者 Tab：四列表格（Consumer Tag / 连接 IP / 端口 / Ack Required），数据来自
 * `GET /api/queues/{vhost}/{name}/consumers` 的实时投影。三态 —— Loading 转圈 /
 * Error（404 / 403 等由 AdminClient 统一转文案，给重试）/ 空（当前无消费者连接）。
 */
@Composable
private fun ConsumersPane(
    state: LoadState<List<RabbitMqConsumerSnapshot>>,
    onRetry: () -> Unit,
) {
    when (state) {
        is LoadState.Idle, is LoadState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is LoadState.Error -> {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(
                    text = stringResource("rabbitmq_ui_consumers_load_failed").withArg(state.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                SecondaryButton(onClick = onRetry) { Text(stringResource("rabbitmq_ui_refresh")) }
            }
        }
        is LoadState.Success -> {
            val consumers = state.data
            if (consumers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource("rabbitmq_ui_consumers_empty"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    consumers.forEach { consumer ->
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
                }
            }
        }
    }
}

/* ------------------------------- 小件 ------------------------------- */

/** 单行概览属性：左标签右值（与交换机属性对话框同一排版）。 */
@Composable
private fun PropertyLine(label: String, value: String, valueColor: Color? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 失败原因：解 ExecutionException 包装（与其他对话框调用处同一简化版）。 */
private fun failureText(error: Throwable): String =
    (error as? java.util.concurrent.ExecutionException)?.cause?.message
        ?: error.message
        ?: error::class.simpleName
        ?: "unknown"
