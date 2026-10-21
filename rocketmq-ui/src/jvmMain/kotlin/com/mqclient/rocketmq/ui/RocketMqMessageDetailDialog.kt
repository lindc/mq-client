package com.mqclient.rocketmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.rocketmq.RocketMqMessage
import com.mqclient.uicommon.dialog.MessageDetailDialog
import com.mqclient.uicommon.dialog.MessageDetailSectionTitle
import com.mqclient.uicommon.dialog.MessageHeaderRow
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * RocketMQ 消息详情对话框（浏览表格「详情」按钮打开）：body 全量随浏览一起拿到
 * （见 [RocketMqMessage] KDoc），**无需像 Kafka 那样按位置二次现查** —— 直接进入
 * 公共 [MessageDetailDialog]（正文 / 属性双 Tab + 解释器区，ui-common 统一渲染）。
 * 属性插槽渲染元数据小节 + 用户属性小节。**不做删除**（RocketMQ 没有按消息删除的
 * 概念）。
 */
@Composable
internal fun RocketMqMessageDetailDialog(
    message: RocketMqMessage,
    onDismiss: () -> Unit,
) {
    MessageDetailDialog(
        title = stringResource("rocketmq_ui_msg_detail_title").withArg(message.queueId, message.offset),
        rawBody = message.body,
        onDismiss = onDismiss,
        subtitleText = "${message.topic} [q${message.queueId}:${message.offset}] · " +
            rmsgFormatTimestamp(message.storeTimestamp),
        headerContent = { RocketMqHeaderContent(message) },
        downloadBaseName = "msg_" + message.topic + "_q" + message.queueId + "_o" + message.offset,
        minWidth = 720.dp,
        minHeight = 480.dp,
    )
}

/** 属性插槽：元数据小节（Topic/Queue/Offset/MsgId/…）+ 用户属性小节。 */
@Composable
private fun RocketMqHeaderContent(message: RocketMqMessage) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        MessageHeaderRow(stringResource("rocketmq_ui_msg_detail_h_topic"), message.topic)
        MessageHeaderRow(stringResource("rocketmq_ui_msg_detail_h_queue"), message.queueId.toString())
        MessageHeaderRow(stringResource("rocketmq_ui_msg_detail_h_offset"), message.offset.toString())
        MessageHeaderRow(stringResource("rocketmq_ui_msg_detail_h_msgid"), message.msgId)
        message.tags?.let { MessageHeaderRow(stringResource("rocketmq_ui_msg_detail_h_tags"), it) }
        message.keys?.let { MessageHeaderRow(stringResource("rocketmq_ui_msg_detail_h_keys"), it) }
        MessageHeaderRow(stringResource("rocketmq_ui_msg_detail_h_born_time"), rmsgFormatTimestamp(message.bornTimestamp))
        MessageHeaderRow(stringResource("rocketmq_ui_msg_detail_h_store_time"), rmsgFormatTimestamp(message.storeTimestamp))
        MessageHeaderRow(stringResource("rocketmq_ui_msg_detail_h_born_host"), message.bornHost)
        MessageHeaderRow(stringResource("rocketmq_ui_msg_detail_h_body_size"), rmsgFormatSize(message.valueSize.toLong()))

        // ---- 用户属性小节 ----
        Spacer(Modifier.height(12.dp))
        MessageDetailSectionTitle(
            text = if (message.userProperties.isEmpty()) {
                stringResource("rocketmq_ui_msg_detail_props_empty")
            } else {
                stringResource("rocketmq_ui_msg_detail_h_user_props").withArg(message.userProperties.size)
            },
        )
        Spacer(Modifier.height(4.dp))
        for ((key, value) in message.userProperties) {
            MessageHeaderRow(key, value)
        }
        Spacer(Modifier.height(12.dp))
    }
}
