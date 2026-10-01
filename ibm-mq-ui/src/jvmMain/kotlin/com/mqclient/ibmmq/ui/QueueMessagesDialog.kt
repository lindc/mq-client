package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 队列消息处理对话框（本地队列卡片 ✉ 按钮打开）：外框复用属性对话框的
 * [ObjectDetailDialog]（自绘标题栏 + 底部关闭 + 可 resize）。
 *
 * 内容区两个 tab：浏览消息（[MessageBrowseTab]）/ 发送消息（[MessageSendTab]）。
 * 发送状态（[SendMessagesState]）与发送协程 scope **挂在 dialog 层**——切 Tab
 * 不打断发送、回来还能看到结果。发送进行中关闭对话框经 onCloseRequest 拦截，
 * 先弹确认（[ConfirmCloseWhileSendingDialog]）。
 */
@Composable
fun QueueMessagesDialog(
    connectionId: String,
    queueName: String,
    onDismiss: () -> Unit,
) {
    val sendScope = rememberCoroutineScope()
    val sendState = remember(connectionId, queueName) {
        SendMessagesState(connectionId, queueName, sendScope)
    }
    var confirmCloseWhileSending by remember { mutableStateOf(false) }

    ObjectDetailDialog(
        title = stringResource("queue_messages_title").withArg(queueName),
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
                    text = { Text(stringResource("queue_messages_tab_browse")) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource("queue_messages_tab_send")) },
                )
            }
            when (selectedTab) {
                0 -> MessageBrowseTab(
                    connectionId = connectionId,
                    queueName = queueName,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> MessageSendTab(
                    state = sendState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (confirmCloseWhileSending) {
        ConfirmCloseWhileSendingDialog(
            onConfirm = onDismiss,
            onDismiss = { confirmCloseWhileSending = false },
        )
    }
}
