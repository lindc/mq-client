package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.messaging.DmpmqmsgFormat
import com.mqclient.ibmmq.messaging.MessageDetail
import com.mqclient.ibmmq.messaging.MessageOperationResult
import com.mqclient.ibmmq.messaging.MessageSummary
import com.mqclient.core.log.Log
import com.mqclient.uicommon.ThemedSnackbarHost
import com.mqclient.uicommon.dialog.MessageDetailContent
import com.mqclient.uicommon.dialog.MessageDetailFooterActions
import com.mqclient.uicommon.dialog.MessageDetailErrorView
import com.mqclient.uicommon.dialog.MessageDetailLoadingView
import com.mqclient.uicommon.dialog.MessageHeaderRow
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.dialog.rememberMessageDetailUiState
import com.mqclient.uicommon.file.saveFileBytes
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    data class Ready(val detail: MessageDetail) : DetailUiState {
        override val label: String get() = "Ready"
    }
}

/**
 * 本宿主的详情链路日志（tag `ui.detail`，与 ui-common / RabbitMQ 同一条流水）。
 *
 * 现场日志已经证明过它的价值：`msg detail completed duration=68ms` 与
 * `detail loaded total=2773ms` 之间空着 7.5 秒 —— 一眼看出「数据早回来了、界面没动」，
 * 直接把结论钉到「状态回不到组合」而不是「MQ 慢」。窗口数（[awtWindowCount]）则用来
 * 验证「三态各 new 一个窗口」这个结构问题没有复发（正常应恒定，不随开关次数上涨）。
 */
private val detailLog = Log.withTag("ui.detail")

/** 当前进程的 AWT 窗口数；取不到（非 AWT 后端）返回 -1。 */
private fun awtWindowCount(): Int = runCatching { java.awt.Window.getWindows().size }.getOrDefault(-1)

/**
 * 消息详情对话框（浏览消息表格「详细」按钮打开）：**一个**窗口、壳内切三态。
 *
 * 结构很重要，不要改回「三态各 new 一个 `ObjectDetailDialog`」：每次翻状态都销毁一个
 * Tao 原生窗口再建一个新的，代价是 Ready 之后还要再等一次窗口创建 + 组合（现场实测
 * 7.5 秒），而且窗口一销毁就把 `LaunchedEffect` 的协程一起取消 —— 同一条消息被取两次
 * 就是这么来的。就绪态的内容与底部按钮都从公共的
 * [com.mqclient.uicommon.dialog.MessageDetailUiState] 读：
 * [com.mqclient.uicommon.dialog.MessageDetailContent] 负责渲染，
 * [com.mqclient.uicommon.dialog.MessageDetailFooterActions] 负责下载 / 复制 / 关闭。
 *
 * IBM 侧只保留数据获取与 dmpmqmsg **导出**；Header 插槽渲染 MQMD 字段列表，
 * 底部额外操作为「导出 dmpmqmsg」。删除消息不走详情对话框，入口在消息列表的
 * 行操作；**dmpmqmsg 导入也不在这里** —— 它是队列级的批量发送动作（看的是「要发一批」，
 * 与当前这一条无关），入口在队列消息处理对话框的「浏览消息」工具栏。
 *
 * 数据经 service 的 getMessageDetail 按 MessageId 现查（独立 browse 句柄，纯浏览不消费）。
 */
@Composable
internal fun MessageDetailDialog(
    connectionId: String,
    queueName: String,
    summary: MessageSummary,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // 组合时刻：所有埋点都以它为基准算相对时间（与 RabbitMqMessageDetailDialog 同一套）。
    val composedAt = remember { System.currentTimeMillis() }

    var uiState by remember(summary.messageId) { mutableStateOf<DetailUiState>(DetailUiState.Loading) }
    var retryKey by remember(summary.messageId) { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        detailLog.info(
            "detail opened: queue=$queueName messageId=${summary.messageId} " +
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
     * 取数：**直接调用，不包 withContext / withTimeout**。
     *
     * `IbmMQService.getMessageDetail` 内部本来就是 `withContext(Dispatchers.IO)`，外面再包
     * 一层只是多一层调度，并不会让 MQI 更「异步」。而 `withTimeout` 在这里从来不是真守卫：
     * 它杀不掉阻塞的 MQI 调用，且取消的投递本身也要走那条被饿住的队列 —— 于是一旦队列不推进，
     * 「5 秒后报错」永远不触发，实际表现是**永久停在加载**（本次故障就是这么来的）。
     * 真正的边界在 service 里：MQGMO_NO_WAIT + OP_SCAN_BUDGET_MS 扫描预算 +
     * CancellationException 优先重抛 + finally 关句柄。
     *
     * `elapsed` 埋点保留：它是判断「慢在哪一段」的唯一依据（取数 vs 回组合）。
     */
    LaunchedEffect(summary.messageId, retryKey) {
        val requestedAt = System.currentTimeMillis()
        val from = uiState.label
        if (uiState !is DetailUiState.Loading) uiState = DetailUiState.Loading
        detailLog.info("state -> Loading (from=$from) windows=${awtWindowCount()}")
        val next =
            try {
                val r = IbmMQModule.serviceOrNull()
                    ?.getMessageDetail(connectionId, queueName, summary.messageId)
                    ?: MessageOperationResult.failure(null, "服务未初始化")
                val detail = r.detail
                if (r.success && detail != null) {
                    DetailUiState.Ready(detail)
                } else {
                    DetailUiState.Failed(r.errorMessage ?: "unknown")
                }
            } catch (e: CancellationException) {
                detailLog.info(
                    "detail load cancelled: elapsed=${System.currentTimeMillis() - requestedAt}ms",
                )
                throw e
            } catch (e: Exception) {
                DetailUiState.Failed(e.message ?: e::class.simpleName ?: "unknown")
            }
        uiState = next
        detailLog.info(
            "state -> ${next.label} (from=Loading) " +
                "elapsed=${System.currentTimeMillis() - requestedAt}ms windows=${awtWindowCount()}",
        )
    }

    /** 导出 dmpmqmsg：IO/Default 线程生成文本，FileKit 另存为。 */
    fun exportDmpmqmsg(detail: MessageDetail) {
        scope.launch {
            try {
                val text = withContext(Dispatchers.Default) {
                    val qmgr = IbmMQModule.serviceOrNull()?.cachedQueueManager(connectionId)
                    DmpmqmsgFormat.export(
                        detail = detail,
                        queueName = queueName,
                        qmgrName = qmgr?.name,
                        qmgrVersion = qmgr?.attributes?.get("MQCA_VERSION")?.toString(),
                    )
                }
                val saved = saveFileBytes(
                    "msg_${detail.messageId.take(8)}_dmpmqmsg.txt",
                    "txt",
                    text.toByteArray(Charsets.UTF_8),
                )
                if (saved != null) {
                    snackbarHostState.showSnackbar(stringResource("ibm_mq_msg_export_success").withArg(saved))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(stringResource("ibm_mq_msg_save_failed").withArg(e.message ?: ""))
            }
        }
    }

    // 本宿主自己的 snackbar 反馈（导出 / 下载 / 保存失败）。
    // 声明在当前 ObjectDetailDialog 的 content 内 —— compose Dialog 的窗口归属随声明组合走，
    // 挂到外层会被本对话框压住（见 ObjectDetailDialog KDoc）。
    //
    // dmpmqmsg **导入**不在这里：那是队列级的批量发送动作，入口在队列消息处理对话框
    // （DmpmqmsgImportState / MessageBrowseTab 工具栏）。
    val hostOverlay: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize()) {
            ThemedSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    // **单个窗口 + 壳内切三态**：不要按状态各 new 一个 ObjectDetailDialog。
    // 每次翻状态都销毁一个 Tao 原生窗口再建一个新的 —— 现场证据是 Ready 之后 7.5s
    // 才开始渲染，以及同一条消息被取两次（窗口重建把 LaunchedEffect 的协程一起取消了）。
    val readyDetail = (uiState as? DetailUiState.Ready)?.detail
    val readyState =
        if (readyDetail != null) {
            rememberMessageDetailUiState(
                rawBody = readyDetail.content,
                encoding = readyDetail.encoding,
                codedCharSetId = readyDetail.codedCharSetId,
            )
        } else {
            null
        }

    ObjectDetailDialog(
        title = stringResource("ibm_mq_msg_detail_title").withArg(summary.messageId),
        onDismiss = onDismiss,
        minWidth = 720.dp,
        minHeight = 480.dp,
        footerActions = {
            if (readyDetail != null && readyState != null) {
                FooterAction(stringResource("ibm_mq_msg_detail_export_dmpmqmsg")) {
                    exportDmpmqmsg(readyDetail)
                }
                MessageDetailFooterActions(
                    state = readyState,
                    onClose = onDismiss,
                    downloadBaseName = "msg_" + summary.messageId.take(8),
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
                    text = stringResource("ibm_mq_msg_detail_loading"),
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
                // ui 必然非空：与 readyDetail 是同一次重组里算出来的。
                val ui = readyState
                if (ui != null) {
                    MessageDetailContent(
                        state = ui,
                        subtitleText = summary.messageId,
                        headerContent = {
                            Column {
                                for ((key, value) in state.detail.headers) {
                                    MessageHeaderRow(key = stringResource(key), value = value)
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                        },
                        nestedContent = hostOverlay,
                    )
                }
            }
        }
    }
}

/** 底部操作栏的文本按钮（extraBottomActions 用），尺寸走 PrimaryButton / SecondaryButton 的全项目约定。 */
@Composable
private fun RowScope.FooterAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SecondaryButton(
        onClick = onClick,
        enabled = enabled,
    ) { Text(label) }
}
