package com.mqclient.rocketmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.rocketmq.RocketMqBrokerSnapshot
import com.mqclient.rocketmq.isSystemTopic
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.RocketMqTopicSnapshot
import com.mqclient.rocketmq.RocketMqTopicRouteSnapshot
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
 * Topic 属性对话框（主题列表卡片 ⓘ 入口）：ObjectDetailDialog 外壳 + PrimaryTabRow
 * 两 Tab（概览 / 路由信息），布局对齐 KafkaTopicPropertiesDialog。
 *
 * 数据两路：静态字段读慢扫缓存快照（与列表同源，零延迟）；消息类型（attributes
 * `message.type`）进对话框异步现查 examineTopicConfig（失败静默回落 Normal，不阻塞
 * 概览）。只读展示。
 *
 * 删除：底部 danger 按钮（系统主题 %RETRY% / %DLQ% / SCHEDULE_TOPIC / TBW102 /
 * rmq_sys_ 隐藏，复用 isSystemTopic）→ AlertDialog 二次确认 → deleteTopicInBroker +
 * deleteTopicInNameServer 两步清元数据 → 成功关对话框 + [onDeleted]（面板刷新 +
 * Snackbar）。失败原因在确认框内直出（不闪关）。
 */
@Composable
fun RocketMqTopicPropertiesDialog(
    connectionId: String,
    topicName: String,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snapshot = remember(topicName) {
        RocketMqModule.serviceOrNull()?.cachedTopics(connectionId)?.firstOrNull { it.name == topicName }
    }
    val systemTopic = com.mqclient.rocketmq.isSystemTopic(topicName)
    val clusterBrokers = remember(topicName) {
        RocketMqModule.serviceOrNull()?.cachedClusterInfo(connectionId)?.brokers.orEmpty()
    }
    var messageType by remember(topicName) { mutableStateOf<String?>(null) }
    var tab by remember(topicName) { mutableIntStateOf(0) }
    var deleteConfirm by remember(topicName) { mutableStateOf(false) }
    var deleting by remember(topicName) { mutableStateOf(false) }
    var deleteError by remember(topicName) { mutableStateOf<String?>(null) }

    // 消息类型现查（不阻塞概览）：attributes["message.type"]，4.x / 未设置回落默认。
    LaunchedEffect(connectionId, topicName) {
        try {
            messageType = RocketMqModule.serviceOrNull()?.fetchTopicMessageType(connectionId, topicName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            messageType = null // 静默回落：概览不因扩展属性查询失败受影响。
        }
    }

    val messageTypeText: String = when {
        topicName.startsWith("%DLQ%") -> stringResource("rocketmq_ui_topic_type_dlq")
        topicName.startsWith("%RETRY%") -> stringResource("rocketmq_ui_topic_type_retry")
        messageType.equals("FIFO", ignoreCase = true) -> stringResource("rocketmq_ui_topic_type_fifo")
        messageType.equals("DELAY", ignoreCase = true) -> stringResource("rocketmq_ui_topic_type_delay")
        messageType.equals("TRANSACTION", ignoreCase = true) -> stringResource("rocketmq_ui_topic_type_transaction")
        else -> stringResource("rocketmq_ui_topic_type_normal")
    }

    fun delete() {
        val service = RocketMqModule.serviceOrNull() ?: run {
            deleteError = stringResource("rocketmq_ui_conn_props_no_service")
            return
        }
        scope.launch {
            deleting = true
            try {
                service.deleteTopic(connectionId, topicName)
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
        title = stringResource("rocketmq_ui_topic_props_title").withArg(topicName),
        onDismiss = onDismiss,
        minWidth = 680.dp,
        minHeight = 440.dp,
        footerActions = {
            // 系统内置主题：不渲染删除按钮（service 层 isSystemTopic 第二道防呆保留）。
            if (!systemTopic) {
                DangerButton(onClick = { deleteConfirm = true }) {
                    Text(stringResource("rocketmq_ui_topic_props_delete"))
                }
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton(
                onClick = onDismiss,
            ) { Text(stringResource("common_close")) }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            var tab by remember(topicName) { mutableIntStateOf(0) }
            if (systemTopic) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp),
                ) {
                    Text(
                        text = stringResource("rocketmq_ui_topic_system_badge"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource("rocketmq_ui_topic_props_tab_overview")) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource("rocketmq_ui_topic_props_tab_routes")) },
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) {
                    OverviewTab(topicName, messageTypeText, snapshot)
                } else {
                    RoutesTab(snapshot?.routes.orEmpty(), clusterBrokers)
                }
            }
        }

        // 删除二次确认。
        if (deleteConfirm) {
            AlertDialog(
                onDismissRequest = { if (!deleting) deleteConfirm = false },
                title = { Text(stringResource("rocketmq_ui_topic_delete_confirm_title")) },
                text = {
                    Column {
                        Text(
                            text = stringResource("rocketmq_ui_topic_delete_confirm_text").withArg(topicName),
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
                                if (deleting) "rocketmq_ui_topic_deleting" else "rocketmq_ui_topic_props_delete",
                            ),
                        )
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
private fun OverviewTab(
    topicName: String,
    messageTypeText: String,
    snapshot: RocketMqTopicSnapshot?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        PropertyTableSection(
            title = stringResource("rocketmq_ui_topic_props_section_basic"),
            items = listOf(
                PropertyItem(stringResource("rocketmq_ui_topic_props_name"), topicName),
                PropertyItem(stringResource("rocketmq_ui_topic_props_message_type"), messageTypeText),
            ),
        )
        Spacer(Modifier.height(16.dp))
        val perm = snapshot?.perm ?: 0
        PropertyTableSection(
            title = stringResource("rocketmq_ui_topic_props_section_perm"),
            items = listOf(
                PropertyItem(stringResource("rocketmq_ui_topic_props_perm"), formatPerm(perm)),
            ),
        )
        Spacer(Modifier.height(16.dp))
        PropertyTableSection(
            title = stringResource("rocketmq_ui_topic_props_section_queues"),
            items = listOf(
                PropertyItem(
                    stringResource("rocketmq_ui_topic_props_read_queues"),
                    (snapshot?.readQueueNums ?: 0).toString(),
                ),
                PropertyItem(
                    stringResource("rocketmq_ui_topic_props_write_queues"),
                    (snapshot?.writeQueueNums ?: 0).toString(),
                ),
            ),
        )
        Spacer(Modifier.height(12.dp))
    }
}

/* ------------------------------ Tab 2：路由信息 ------------------------------ */

@Composable
private fun RoutesTab(
    routes: List<RocketMqTopicRouteSnapshot>,
    clusterBrokers: List<RocketMqBrokerSnapshot>,
) {
    if (routes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("rocketmq_ui_topic_props_no_routes"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        // 与概览（PropertyTable）同款视觉：斑马纹 + 4dp 圆角 + bodyMedium/onSurface +
        // 40dp 最小行高 + 10/8 内边距 + 8dp 行距，无分割线；列宽统一 weight。
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(stringResource("rocketmq_ui_topic_props_col_broker"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(2f))
            Text(stringResource("rocketmq_ui_topic_props_col_read"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(stringResource("rocketmq_ui_topic_props_col_write"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(stringResource("rocketmq_ui_topic_props_col_perm"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f))
            Text(stringResource("rocketmq_ui_topic_props_col_role"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f))
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(routes) { index, route ->
                val role = clusterBrokers
                    .firstOrNull { it.brokerName == route.brokerName }
                    ?.role ?: "-"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (index % 2 == 1) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = route.brokerName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(2f),
                    )
                    Text(route.readQueueNums.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(route.writeQueueNums.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(formatPerm(route.perm), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1.2f))
                    Text(
                        text = role,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (role == "Master") {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1.2f),
                    )
                }
            }
        }
    }
}
