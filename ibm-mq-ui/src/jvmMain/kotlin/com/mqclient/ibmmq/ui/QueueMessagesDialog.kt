package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource

/**
 * 队列消息处理对话框（本地队列卡片 ✉ 按钮打开）：外框复用属性对话框的
 * [ObjectDetailDialog]（自绘标题栏 + 底部关闭 + 可 resize）。
 *
 * 内容区两个 tab：浏览消息（[MessageBrowseTab]）/ 发送消息（待实装），
 * 标题走 i18n。
 */
@Composable
fun QueueMessagesDialog(
    connectionId: String,
    queueName: String,
    onDismiss: () -> Unit,
) {
    ObjectDetailDialog(
        title = stringResource("queue_messages_title").withArg(queueName),
        onDismiss = onDismiss,
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
                // 发送消息页待实装。
                else -> {}
            }
        }
    }
}
