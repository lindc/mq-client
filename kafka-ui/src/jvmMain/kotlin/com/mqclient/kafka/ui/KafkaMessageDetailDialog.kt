package com.mqclient.kafka.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.kafka.KafkaMessageDetail
import com.mqclient.kafka.KafkaMessageSummary
import com.mqclient.kafka.KafkaModule
import com.mqclient.core.log.Log
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.MessageDetailContent
import com.mqclient.uicommon.dialog.MessageDetailFooterActions
import com.mqclient.uicommon.dialog.MessageDetailErrorView
import com.mqclient.uicommon.dialog.MessageDetailLoadingView
import com.mqclient.uicommon.dialog.MessageDetailSectionTitle
import com.mqclient.uicommon.dialog.MessageHeaderRow
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.dialog.rememberMessageDetailUiState
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.table.formatTimestamp
import kotlinx.coroutines.CancellationException

/** Kafka 消息无编码 / CCSID 元数据，公共组件默认 0 / 1208（UTF-8 中性值）即原口径，不另传。 */

/** 详情对话框的三态：加载中 / 失败（带原因，可重试）/ 就绪。 */
private sealed interface DetailUiState {
    /** 日志用的短名（`[ui.detail] state -> ...`）。 */
    val label: String

    data object Loading : DetailUiState {
        override val label: String get() = "Loading"
    }

    data class Failed(val reason: String) : DetailUiState {
        override val label: String get() = "Failed"
    }

    data class Ready(val detail: KafkaMessageDetail) : DetailUiState {
        override val label: String get() = "Ready"
    }
}

/**
 * 本宿主的详情链路日志（tag `ui.detail`，与 IBM MQ / ui-common / RabbitMQ 同一条流水）。
 *
 * 它把一次打开拆成「状态翻转」与「取数耗时」两段：`state -> Ready (elapsed=..)` 说明数据
 * 早就回来了，若界面仍旧停在加载，瓶颈就在「回组合」而不是 broker。窗口数
 * （[awtWindowCount]）用来验证「三态各 new 一个窗口」的结构问题没有复发 —— 正常应恒定，
 * 不随开关次数上涨。
 */
private val detailLog = Log.withTag("ui.detail")

/** 当前进程的 AWT 窗口数；取不到（非 AWT 后端）返回 -1。 */
private fun awtWindowCount(): Int = runCatching { java.awt.Window.getWindows().size }.getOrDefault(-1)


/**
 * Kafka 消息详情对话框（浏览表格「详情」按钮打开）：**一个**窗口、壳内切三态。
 *
 * 与 IBM MQ 宿主同一套结构（`ibm-mq-ui` 的 MessageDetailDialog）：不要改回「三态各 new
 * 一个 [ObjectDetailDialog]」—— 每次翻状态都销毁一个 Tao 原生窗口再建一个新的，Ready
 * 之后还要再等一次窗口创建 + 组合（IBM 侧现场实测 7.5 秒），而且窗口一销毁就把
 * `LaunchedEffect` 的协程一起取消，同一条消息会被取两次。就绪态的内容与底部按钮都从
 * [MessageDetailUiState] 读：[MessageDetailContent] 负责渲染，
 * [MessageDetailFooterActions] 负责下载 / 复制 / 关闭。
 *
 * Kafka 侧只保留数据获取；Header 插槽渲染元数据小节 + 消息 Headers 小节。**不做删除**
 * （Kafka 没有按消息删除的概念）。
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
    // 组合时刻：所有埋点都以它为基准算相对时间（与 IBM / RabbitMQ 的 [ui.detail] 同一套）。
    val composedAt = remember { System.currentTimeMillis() }
    val snackbarHostState = remember { SnackbarHostState() }

    val positionKey = "${summary.partition}:${summary.offset}"
    var uiState by remember(positionKey) { mutableStateOf<DetailUiState>(DetailUiState.Loading) }
    var retryKey by remember(positionKey) { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        detailLog.info(
            "detail opened: topic=$topicName position=$positionKey " +
                "sinceCompose=${System.currentTimeMillis() - composedAt}ms windows=${awtWindowCount()}",
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            detailLog.info(
                "detail disposed: lifetime=${System.currentTimeMillis() - composedAt}ms " +
                    "windows=${awtWindowCount()}",
            )
        }
    }

    /**
     * 取数：直接调用。`readMessageDetail` 内部已是 `withContext(Dispatchers.IO)`，
     * 外面再包一层只是多一层调度 —— 而且 `withTimeout` 在这里不是真守卫：它杀不掉阻塞的
     * consumer.poll，超时取消的投递同样要走那条可能被饿住的队列（IBM 侧因此永久停在加载）。
     */
    LaunchedEffect(positionKey, retryKey) {
        val requestedAt = System.currentTimeMillis()
        val from = uiState.label
        if (uiState !is DetailUiState.Loading) uiState = DetailUiState.Loading
        detailLog.info("state -> Loading (from=$from) windows=${awtWindowCount()}")
        val next =
            try {
                val service = KafkaModule.messageServiceOrNull()
                    ?: throw IllegalStateException(stringResource("kafka_ui_send_service_not_ready"))
                DetailUiState.Ready(
                    service.readMessageDetail(connectionId, topicName, summary.partition, summary.offset),
                )
            } catch (e: CancellationException) {
                detailLog.info(
                    "detail load cancelled: elapsed=${System.currentTimeMillis() - requestedAt}ms",
                )
                throw e
            } catch (e: Exception) {
                DetailUiState.Failed(kafkaErrorText(e))
            }
        uiState = next
        detailLog.info(
            "state -> ${next.label} (from=Loading) " +
                "elapsed=${System.currentTimeMillis() - requestedAt}ms windows=${awtWindowCount()}",
        )
    }

    // 本宿主自己的 snackbar 反馈（下载等），声明在当前对话框 content 内 —— compose
    // Dialog 的窗口归属随声明组合走，挂到外层会被本对话框压住（ObjectDetailDialog KDoc）。
    val hostOverlay: @Composable () -> Unit = {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
            ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    val readyDetail = (uiState as? DetailUiState.Ready)?.detail
    val readyState =
        if (readyDetail != null) {
            rememberMessageDetailUiState(rawBody = readyDetail.valueBytes)
        } else {
            null
        }

    ObjectDetailDialog(
        title = stringResource("kafka_ui_msg_detail_title").withArg(summary.partition, summary.offset),
        onDismiss = onDismiss,
        minWidth = 720.dp,
        minHeight = 480.dp,
        footerActions = {
            if (readyState != null && readyDetail != null) {
                MessageDetailFooterActions(
                    state = readyState,
                    onClose = onDismiss,
                    downloadBaseName =
                        "msg_" + readyDetail.topic + "_p" + readyDetail.partition + "_o" + readyDetail.offset,
                )
            } else {
                // 加载 / 失败态没有可下载、可复制的东西，只给关闭（关闭永不被门禁拦）。
                Spacer(Modifier.weight(1f))
                SecondaryButton(onClick = onDismiss) { Text(stringResource("common_close")) }
            }
        },
    ) {
        when (val state = uiState) {
            is DetailUiState.Loading -> {
                MessageDetailLoadingView(
                    text = stringResource("kafka_ui_msg_browse_loading"),
                    modifier = Modifier.fillMaxSize(),
                )
                hostOverlay()
            }
            is DetailUiState.Failed -> {
                MessageDetailErrorView(
                    reason = state.reason,
                    onRetry = { retryKey++ },
                    modifier = Modifier.fillMaxSize(),
                )
                hostOverlay()
            }
            is DetailUiState.Ready -> {
                val ui = readyState
                if (ui != null) {
                    MessageDetailContent(
                        state = ui,
                        subtitleText = "$topicName [${summary.partition}:${summary.offset}] · " +
                            formatTimestamp(summary.timestamp),
                        headerContent = { KafkaHeaderContent(detail = state.detail) },
                        nestedContent = hostOverlay,
                    )
                }
            }
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
