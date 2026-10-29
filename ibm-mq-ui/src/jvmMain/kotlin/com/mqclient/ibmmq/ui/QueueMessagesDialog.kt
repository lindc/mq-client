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
 * 发送状态（[SendMessagesState]）、导入状态（[DmpmqmsgImportState]）与各自的协程 scope
 * **都挂在 dialog 层**——切 Tab 不打断发送 / 导入，回来还能看到结果。任一动作进行中关闭
 * 对话框经 onCloseRequest 拦截，先弹确认。
 *
 * dmpmqmsg 导入放在这一层而不是消息详情对话框：**导入是队列级的批量发送动作**
 *（导入的是一批待发消息，与列表里选中的某一条无关）。入口在「浏览消息」工具栏。
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
    // 导入成功后让浏览 Tab 重新加载（新消息的 MessageId 原列表里没有）。
    var browseReloadKey by remember { mutableIntStateOf(0) }
    val importState = rememberDmpmqmsgImportState(connectionId, queueName) { browseReloadKey++ }

    // 关闭确认的文案：发送中 / 导入中各有各的说法（null = 当前没有进行中的动作）。
    var closeConfirmText by remember { mutableStateOf<String?>(null) }

    ObjectDetailDialog(
        title = stringResource("ibm_mq_queue_messages_title").withArg(queueName),
        onDismiss = onDismiss,
        onCloseRequest = {
            closeConfirmText = when {
                sendState.busy -> stringResource("ibm_mq_msg_send_close_confirm")
                importState.busy -> stringResource("ibm_mq_msg_import_close_confirm")
                else -> null
            }
            if (closeConfirmText == null) onDismiss()
        },
    ) {
        var selectedTab by remember { mutableIntStateOf(0) }
        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource("ibm_mq_queue_messages_tab_browse")) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource("ibm_mq_queue_messages_tab_send")) },
                )
            }
            when (selectedTab) {
                0 -> MessageBrowseTab(
                    connectionId = connectionId,
                    queueName = queueName,
                    modifier = Modifier.fillMaxSize(),
                    importState = importState,
                    reloadKey = browseReloadKey,
                )
                else -> MessageSendTab(
                    state = sendState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 导入的浮层（结果提示 + 预览 / fallback 对话框）与关闭确认都**声明在本外壳
        // content lambda 内**——compose Dialog 的窗口归属随声明组合走，挂到外层会被本
        // 对话框压住（见 ObjectDetailDialog KDoc）。
        DmpmqmsgImportHost(importState)
        closeConfirmText?.let { message ->
            ConfirmCloseWhileBusyDialog(
                message = message,
                onConfirm = onDismiss,
                onDismiss = { closeConfirmText = null },
            )
        }
    }
}
