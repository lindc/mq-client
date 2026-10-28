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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.log.Log
import com.mqclient.rabbitmq.RabbitMqFetchedMessage
import com.mqclient.uicommon.dialog.MessageDetailDialog
import com.mqclient.uicommon.dialog.MessageDetailSectionTitle
import com.mqclient.uicommon.dialog.MessageHeaderRow
import com.mqclient.uicommon.i18n.stringResource

/**
 * 详情弹窗链路的时间戳埋点。
 *
 * 为什么要它：「打开单个消息详情要 40s、第二个 60s+」且**递增** —— 而拉取本身很快、
 * 正文解码与解释器实测都是毫秒级、字体首次解析也只有 204ms。剩下的每一跳都很便宜，
 * 那就必须靠**实测**而不是继续猜：每跳各打一行带毫秒的时间戳，事后相减就知道 40 秒
 * 落在哪一段（点击 → 组合 → 首帧 → 解码 → 渲染 → 销毁）。
 *
 * 同时记窗口数（`Window.getWindows().size`）：如果是「每次开弹窗新建一个窗口而旧的
 * 没销毁」，这个数字会单调上涨，正好解释「越开越慢」。
 */
internal val detailUiLog = Log.withTag("ui.detail")

/** 当前进程里 AWT 窗口数；取不到（非 AWT 后端）返回 -1。 */
internal fun awtWindowCount(): Int = runCatching { java.awt.Window.getWindows().size }.getOrDefault(-1)

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
    // 弹窗生命周期的首尾各记一行（见 detailUiLog 的说明）。
    val composedAt = remember { System.currentTimeMillis() }
    LaunchedEffect(Unit) {
        detailUiLog.info(
            "detail composed: bodyBytes=${message.body.size} " +
                "sinceCompose=${System.currentTimeMillis() - composedAt}ms windows=${awtWindowCount()}",
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            detailUiLog.info(
                "detail disposed: lifetime=${System.currentTimeMillis() - composedAt}ms " +
                    "windows=${awtWindowCount()}",
            )
        }
    }

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
