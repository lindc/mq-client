package com.mqclient.kafka.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.kafka.KafkaMessageDetail
import com.mqclient.kafka.KafkaMessageSummary
import com.mqclient.kafka.KafkaModule
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.MessageDetailDialog
import com.mqclient.uicommon.dialog.MessageDetailErrorView
import com.mqclient.uicommon.dialog.MessageDetailLoadingView
import com.mqclient.uicommon.dialog.MessageDetailSectionTitle
import com.mqclient.uicommon.dialog.MessageHeaderRow
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.file.saveFileBytes
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.table.formatTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Kafka 消息无编码 / CCSID 元数据，公共组件默认 0 / 1208（UTF-8 中性值）即原口径，不另传。 */

/** 详情对话框的三态：加载中 / 失败（带原因，可重试）/ 就绪。 */
private sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Failed(val reason: String) : DetailUiState
    data class Ready(val detail: KafkaMessageDetail) : DetailUiState
}

/**
 * 消息详情对话框（浏览表格「详情」按钮打开）：三态外壳 —— 就绪态交给公共
 * [MessageDetailDialog]（正文/Header 双 Tab + 解释器区，ui-common 统一渲染），
 * 加载 / 失败态用普通 [ObjectDetailDialog] + 公共 Loading / Error 视图。Kafka 侧
 * 只保留数据获取与下载；Header 插槽渲染元数据小节 + 消息 Headers 小节。**不做
 * 删除**（Kafka 没有按消息删除的概念）。
 *
 * 数据经 service 的 readMessageDetail 按 (partition, offset) 现查（assign + seek，
 * 不提交 offset）；浏览到打开之间消息可能被 retention 清除 —— 失败态给出可读原因。
 */
@Composable
internal fun KafkaMessageDetailDialog(
    connectionId: String,
    topicName: String,
    summary: KafkaMessageSummary,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val positionKey = "${summary.partition}:${summary.offset}"
    var uiState by remember(positionKey) { mutableStateOf<DetailUiState>(DetailUiState.Loading) }
    var retryKey by remember(positionKey) { mutableIntStateOf(0) }

    LaunchedEffect(positionKey, retryKey) {
        uiState = DetailUiState.Loading
        uiState = try {
            val service = KafkaModule.messageServiceOrNull()
                ?: throw IllegalStateException(stringResource("kafka_ui_send_service_not_ready"))
            DetailUiState.Ready(service.readMessageDetail(connectionId, topicName, summary.partition, summary.offset))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DetailUiState.Failed(kafkaErrorText(e))
        }
    }

    // 本宿主自己的 snackbar 反馈（下载等），声明在当前对话框 content 内 —— compose
    // Dialog 的窗口归属随声明组合走，挂到外层会被本对话框压住（ObjectDetailDialog KDoc）。
    val hostOverlay: @Composable () -> Unit = {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
            ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
    when (val state = uiState) {
        is DetailUiState.Loading -> ObjectDetailDialog(
            title = stringResource("kafka_ui_msg_detail_title").withArg(summary.partition, summary.offset),
            onDismiss = onDismiss,
            minWidth = 720.dp,
            minHeight = 480.dp,
        ) {
            MessageDetailLoadingView(
                text = stringResource("kafka_ui_msg_browse_loading"),
                modifier = Modifier.fillMaxSize(),
            )
            hostOverlay()
        }
        is DetailUiState.Failed -> ObjectDetailDialog(
            title = stringResource("kafka_ui_msg_detail_title").withArg(summary.partition, summary.offset),
            onDismiss = onDismiss,
            minWidth = 720.dp,
            minHeight = 480.dp,
        ) {
            MessageDetailErrorView(
                reason = state.reason,
                onRetry = { retryKey++ },
                modifier = Modifier.fillMaxSize(),
            )
            hostOverlay()
        }
        is DetailUiState.Ready -> {
            val detail = state.detail
            MessageDetailDialog(
                title = stringResource("kafka_ui_msg_detail_title").withArg(summary.partition, summary.offset),
                rawBody = detail.valueBytes,
                onDismiss = onDismiss,
                subtitleText = "$topicName [${summary.partition}:${summary.offset}] · " +
                    formatTimestamp(summary.timestamp),
                headerContent = { KafkaHeaderContent(detail = detail) },
                downloadBaseName = "msg_" + detail.topic + "_p" + detail.partition + "_o" + detail.offset,
                minWidth = 720.dp,
                minHeight = 480.dp,
                nestedContent = hostOverlay,
            )
        }
    }
}

/** Header 插槽：元数据小节（Topic/Partition/Offset/…）+ 消息 Headers 小节。 */
@Composable
private fun KafkaHeaderContent(detail: KafkaMessageDetail) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        MessageHeaderRow(stringResource("kafka_ui_msg_detail_h_topic"), detail.topic)
        MessageHeaderRow(stringResource("kafka_ui_msg_detail_h_partition"), detail.partition.toString())
        MessageHeaderRow(stringResource("kafka_ui_msg_detail_h_offset"), detail.offset.toString())
        MessageHeaderRow(stringResource("kafka_ui_msg_detail_h_timestamp"), formatTimestamp(detail.timestamp))
        MessageHeaderRow(stringResource("kafka_ui_msg_detail_h_timestamp_type"), detail.timestampType)
        detail.keyBytes?.let { keyBytes ->
            MessageHeaderRow(
                stringResource("kafka_ui_msg_detail_h_key"),
                MessageCharsets.decode(keyBytes, DETAIL_CCSID),
            )
            MessageHeaderRow(
                stringResource("kafka_ui_msg_detail_h_key_size"),
                detail.keySize?.toString() ?: "-",
            )
        }
        MessageHeaderRow(stringResource("kafka_ui_msg_detail_h_value_size"), detail.valueSize.toString())

        // ---- 消息 Headers 小节 ----
        Spacer(Modifier.height(12.dp))
        MessageDetailSectionTitle(
            text = if (detail.headers.isEmpty()) {
                stringResource("kafka_ui_msg_detail_headers_empty")
            } else {
                stringResource("kafka_ui_msg_detail_h_message_headers").withArg(detail.headers.size)
            },
        )
        Spacer(Modifier.height(4.dp))
        for (header in detail.headers) {
            val value = header.value
                ?.let { MessageCharsets.decode(it, DETAIL_CCSID) }
                ?: "(null)"
            MessageHeaderRow(header.key, value)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 与公共组件默认口径一致的 CCSID 常量（Kafka 消息无编码元数据，按 UTF-8 解）。 */
private const val DETAIL_CCSID = 1208
