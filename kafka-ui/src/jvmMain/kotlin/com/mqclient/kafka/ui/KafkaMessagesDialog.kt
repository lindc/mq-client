package com.mqclient.kafka.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * Topic 消息处理对话框（主题卡片 ✉ / 双击打开）：外框复用 [ObjectDetailDialog]
 * （自绘标题栏 + 底部关闭 + 可 resize），与 ibm-mq 的 QueueMessagesDialog 同构。
 *
 * 内容区两个 tab：浏览消息（[KafkaMessageBrowseTab]）/ 发送消息（[KafkaMessageSendTab]）。
 * 发送状态（[KafkaSendMessagesState]）与发送协程 scope **挂在 dialog 层** —— 切 Tab
 * 不打断发送、回来还能看到结果；发送进行中关闭对话框经 onCloseRequest 拦截，先弹确认。
 *
 * @param partitionCount topic 分区数（起点选择区的 Partition 下拉用）；取不到缓存快照时传
 *   null，下拉只显示「全部分区」。
 */
@Composable
fun KafkaMessagesDialog(
    connectionId: String,
    topicName: String,
    partitionCount: Int?,
    onDismiss: () -> Unit,
) {
    val sendScope = rememberCoroutineScope()
    val sendState = remember(connectionId, topicName) {
        KafkaSendMessagesState(connectionId, topicName, sendScope)
    }
    var confirmCloseWhileSending by remember { mutableStateOf(false) }

    ObjectDetailDialog(
        title = stringResource("kafka_ui_messages_title").withArg(topicName),
        onDismiss = onDismiss,
        onCloseRequest = {
            if (sendState.busy) confirmCloseWhileSending = true else onDismiss()
        },
    ) {
        var selectedTab by remember { mutableIntStateOf(0) }
        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource("kafka_ui_messages_tab_browse")) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource("kafka_ui_messages_tab_send")) },
                )
            }
            when (selectedTab) {
                0 -> KafkaMessageBrowseTab(
                    connectionId = connectionId,
                    topicName = topicName,
                    partitionCount = partitionCount,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> KafkaMessageSendTab(
                    state = sendState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 发送中关闭确认：**声明在本外壳 content lambda 内**——compose Dialog 的窗口
        // 归属随声明组合走，挂到外层会被本对话框压住（见 ObjectDetailDialog KDoc）。
        if (confirmCloseWhileSending) {
            AlertDialog(
                onDismissRequest = { confirmCloseWhileSending = false },
                text = { Text(stringResource("kafka_ui_send_close_confirm_text")) },
                confirmButton = {
                    PrimaryButton(onClick = onDismiss) { Text(stringResource("kafka_ui_send_close_confirm_ok")) }
                },
                dismissButton = {
                    SecondaryButton(
                        onClick = { confirmCloseWhileSending = false },
                    ) { Text(stringResource("common_cancel")) }
                },
            )
        }
    }
}
