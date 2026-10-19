package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.rabbitmq.RabbitMqFetchedMessage
import com.mqclient.uicommon.dialog.MessageDetailDialog
import com.mqclient.uicommon.dialog.MessageDetailSectionTitle
import com.mqclient.uicommon.dialog.MessageHeaderRow
import com.mqclient.uicommon.i18n.stringResource

/**
 * AMQP 消息详情对话框（队列消息列表「详情」入口）：全部 UI（正文/Header 双 Tab +
 * 解释器区 + 底部复制正文）交给公共 [MessageDetailDialog]（ui-common 统一渲染，
 * 外观与 IBM MQ / Kafka 一致）；本文件只保留 Header 插槽 —— AMQP BasicProperties
 * 投影 + 自定义 Headers 键值表两个小节。encoding / CCSID 用公共默认 0 / 1208
 * （AMQP 只有 contentEncoding 字符串，第一版统一按 UTF-8）。
 */
@Composable
fun RabbitMqMessageDetailDialog(
    message: RabbitMqFetchedMessage,
    onDismiss: () -> Unit,
) {
    MessageDetailDialog(
        title = stringResource("rabbitmq_ui_msg_detail_title"),
        rawBody = message.body,
        onDismiss = onDismiss,
        headerContent = { RabbitMqHeaderContent(message = message) },
    )
}

/** Header 插槽：AMQP BasicProperties 投影小节 + 自定义 Headers 小节。 */
@Composable
private fun RabbitMqHeaderContent(message: RabbitMqFetchedMessage) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        MessageDetailSectionTitle(text = stringResource("rabbitmq_ui_msg_detail_props"))
        Spacer(Modifier.height(4.dp))
        MessageHeaderRow("message id", message.messageId)
        MessageHeaderRow(
            "timestamp",
            message.timestamp?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(it) },
        )
        MessageHeaderRow(
            "delivery mode",
            stringResource(if (message.persistent) "rabbitmq_ui_msg_persistent" else "rabbitmq_ui_msg_transient"),
        )
        MessageHeaderRow("content type", message.contentType)
        MessageHeaderRow("content encoding", message.contentEncoding)
        MessageHeaderRow("priority", message.priority?.toString())
        MessageHeaderRow("expiration", message.expiration)
        MessageHeaderRow("correlation id", message.correlationId)
        MessageHeaderRow("reply to", message.replyTo)

        Spacer(Modifier.height(12.dp))
        MessageDetailSectionTitle(text = stringResource("rabbitmq_ui_msg_detail_headers"))
        Spacer(Modifier.height(4.dp))
        if (message.headers.isEmpty()) {
            Text(
                text = stringResource("rabbitmq_ui_msg_detail_no_headers"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            message.headers.forEach { (k, v) ->
                MessageHeaderRow(k, v)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
