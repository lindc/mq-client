package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mqclient.core.ContextMenuItem
import com.mqclient.core.CoreModule
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.event.IbmMQEvents
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.mqui.command.ToolbarButton
import com.mqclient.mqui.common.CompactSearchField
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.uicommon.LocalSnackbarHostState
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 占位宽度：搜索框与刷新按钮之间的间距。 */
private val ToolbarSpacing = 8.dp

/**
 * 队列列表（导航 `queues` Tab 的内容）：接 mq-ui 通用 [ObjectListPanel]。
 *
 * 数据姿势与 QMGR 属性面板一致：直接经 [IbmMQModule.serviceOrNull] 调
 * ibm-mq-service。队列快照来自**扫描缓存**（[IbmMQService.cachedObjects]，只读
 * 不碰 MQ）；「刷新」按钮触发 [IbmMQService.scanObjects] 重扫，扫描完成的事件
 * （OBJECTS / MESSAGES / SCAN_COMPLETED）驱动列表自动刷新 —— 手动与定时扫描
 * 共享同一条响应链路。
 *
 * 尚未做的事：卡片单击（无选中态）与双击（队列详情 Tab 未做）都是空动作；
 * 右键菜单目前只有「复制名称」。
 */
@Composable
fun QueueListPanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    var queues by remember(connectionId) { mutableStateOf(emptyList<MqObjectSnapshot>()) }
    var loading by remember(connectionId) { mutableStateOf(true) }
    var searchText by remember { mutableStateOf("") }

    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(connectionId) {
        val service = IbmMQModule.serviceOrNull()
        if (service != null) {
            queues = service.cachedObjects(connectionId, MqObjectType.QUEUE)
            if (queues.isEmpty()) {
                // 首次打开且缓存为空：主动扫一轮（未连接等失败安静吞掉 —— 空态即可）。
                runCatching { service.scanObjects(connectionId) }
                    .onSuccess { queues = service.cachedObjects(connectionId, MqObjectType.QUEUE) }
            }
        }
        loading = false

        // 扫描完成 → 重读缓存（三条扫描链路任一完成都刷新；payload 过滤本连接）。
        val bus = CoreModule.eventBus
        listOf(
            IbmMQEvents.OBJECTS_SCANNED,
            IbmMQEvents.MESSAGES_SCANNED,
            IbmMQEvents.SCAN_COMPLETED,
        ).forEach { name ->
            bus.on(name, this) { event ->
                if ((event.payload["connectionId"] as? String) == connectionId) {
                    queues = IbmMQModule.serviceOrNull()?.cachedObjects(connectionId, MqObjectType.QUEUE) ?: emptyList()
                }
            }
        }
    }

    fun refresh() {
        scope.launch {
            val service = IbmMQModule.serviceOrNull() ?: return@launch
            runCatching { service.scanObjects(connectionId) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    snackbarHostState.showSnackbar(
                        stringResource("queue_scan_failed").withArg(error.message ?: error::class.simpleName ?: ""),
                    )
                }
            // 成功后的缓存重载由 SCAN_COMPLETED 事件驱动（上面已订阅）。
        }
    }

    val filtered = queues.filter { q ->
        searchText.isEmpty() || q.name.contains(searchText, ignoreCase = true)
    }

    ObjectListPanel(
        items = filtered.map { it.toCardData() },
        onCardClick = { /* 无选中态（ObjectListPanel 约定），留空 */ },
        onCardDoubleClick = { /* 队列详情 Tab 未做，先无动作 */ },
        contextMenuProvider = { card ->
            listOf(
                ContextMenuItem(id = "queue.copy.name", title = "queue_copy_name") {
                    clipboard.setText(AnnotatedString(card.title))
                },
            )
        },
        toolbarContent = {
            QueueListToolbar(
                searchText = searchText,
                onSearchChange = { searchText = it },
                onRefresh = ::refresh,
                refreshing = loading,
            )
        },
        loading = loading,
        modifier = modifier,
    )
}

/** MqObjectSnapshot → ObjectCardData：状态图标按消息数二值化（有消息=绿勾活跃，无=灰点空闲）。 */
private fun MqObjectSnapshot.toCardData(): ObjectCardData {
    val active = (messageCount ?: 0) > 0
    return ObjectCardData(
        id = name,
        title = name,
        typeIcon = Icons.Filled.Inbox,
        statusIcon = if (active) Icons.Filled.CheckCircle else Icons.Filled.Circle,
        statusIconTint = if (active) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
        statusText = stringResource(if (active) "queue_status_active" else "queue_status_idle"),
        metricValue = (messageCount ?: 0).toString(),
    )
}

/** 工具栏 Slot 内容：紧凑搜索框（左，占满剩余宽度）+ 刷新按钮（右），同高 32dp。 */
@Composable
private fun QueueListToolbar(
    searchText: String,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ToolbarSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactSearchField(
            value = searchText,
            onValueChange = onSearchChange,
            placeholder = stringResource("queue_search_placeholder"),
            modifier = Modifier.weight(1f),
        )
        ToolbarButton(
            icon = Icons.Default.Refresh,
            text = stringResource("queue_refresh"),
            onClick = onRefresh,
            enabled = !refreshing,
        )
    }
}

/** stringResource 无格式化参数；%s / %1$s 占位替换（全项目约定）。 */
private fun String.withArg(arg: Any): String =
    replace("%1\$s", arg.toString()).replace("%s", arg.toString())
