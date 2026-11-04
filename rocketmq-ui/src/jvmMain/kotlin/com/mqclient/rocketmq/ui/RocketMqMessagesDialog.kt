package com.mqclient.rocketmq.ui

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
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.PrimaryButton
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.dialog.rememberFullScreenSize
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * Topic 消息处理对话框（主题卡片 ✉ 打开）：外框复用 [ObjectDetailDialog]
 * （自绘标题栏 + 底部关闭 + 可 resize），**结构与 KafkaMessagesDialog 同构**。
 *
 * 内容区两个 tab：浏览消息（[RocketMqMessageBrowseTab]）/ 发送消息
 * （[RocketMqMessageSendTab]）。发送状态（[RocketMqSendMessagesState]）与发送
 * 协程 scope **挂在 dialog 层** —— 切 Tab 不打断发送、回来还能看到结果；发送进行中
 * 关闭对话框经 onCloseRequest 拦截，先弹确认。
 */
@Composable
fun RocketMqMessagesDialog(
    connectionId: String,
    topicName: String,
    onDismiss: () -> Unit,
) {
    val sendScope = rememberCoroutineScope()
    val sendState = remember(connectionId, topicName) {
        RocketMqSendMessagesState(connectionId, topicName, sendScope)
    }
    var confirmCloseWhileSending by remember { mutableStateOf(false) }

    // 初始 800×600；小屏用 95% 全屏做 max 封顶（max 只钳制初始尺寸，不限制手动 resize），
    // 既满足大屏舒展，又不会在小屏溢出屏幕边缘。内部浏览 LazyColumn / 发送 TextArea 已用
    // weight(1f) 吃掉剩余高度，组件可正常滚动。
    val full = rememberFullScreenSize()
    ObjectDetailDialog(
        title = stringResource("rocketmq_ui_messages_title").withArg(topicName),
        initialWidth = 800.dp,
        initialHeight = 600.dp,
        maxWidth = full.width * 0.95f,
        maxHeight = full.height * 0.95f,
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
                    text = { Text(stringResource("rocketmq_ui_messages_tab_browse")) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource("rocketmq_ui_messages_tab_send")) },
                )
            }
            when (selectedTab) {
                0 -> RocketMqMessageBrowseTab(
                    connectionId = connectionId,
                    topicName = topicName,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> RocketMqMessageSendTab(
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
                text = { Text(stringResource("rocketmq_ui_send_close_confirm_text")) },
                confirmButton = {
                    PrimaryButton(onClick = onDismiss) {
                        Text(stringResource("rocketmq_ui_send_close_confirm_ok"))
                    }
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
