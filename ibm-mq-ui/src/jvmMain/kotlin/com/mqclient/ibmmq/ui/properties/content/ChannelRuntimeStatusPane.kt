package com.mqclient.ibmmq.ui.properties.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.command.MqrcTranslator
import com.mqclient.ibmmq.pcf.ChannelStatusDetail
import com.mqclient.ibmmq.ui.ChannelResetSeqnumDialog
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 通道「运行状态」分组（属性对话框第三分组，实时 DISPLAY CHSTATUS）：
 *
 *  - 数据：[IbmMQService.getChannelStatus] 实时查询，不经慢扫缓存 —— 打开分组 /
 *    点刷新 / 重置序列号成功后都会重新查询；
 *  - 分层：只展示运行指标（序列号 / in-doubt / 消息数 / 字节 / 最后消息时间），
 *    定义属性留在「常规」分组；
 *  - 类型差异：发送侧（SDR/SVR/RQSTR/CLUSSDR）显示重置按钮，接收侧指标取接收
 *    视角字段，连接型（SVRCONN/CLNTCONN/MQTT/AMQP）相关字段逐行「不适用」；
 *    未知类型全部展示、缺数据显示「-」、不给重置按钮（保守默认）；
 *  - 五态：加载中（转圈）/ 加载失败（原因 + 重试）/ 字段不适用 / 通道未运行
 *    （「通道未运行」+ 数值「-」）/ 就绪；
 *  - 重置序列号：[ChannelResetSeqnumDialog] 二次确认后经 MqscExecutor 发
 *    `RESET CHANNEL … SEQNUM(n)`（沿用既有命令执行通道），失败保留对话框可重试。
 *
 * 对话框是独立窗口，够不到主窗口的 SnackbarHost（见 MessageBrowseTab 同款注释），
 * 成功提示走本面板内嵌的局部 SnackbarHost。
 */
@Composable
internal fun ChannelRuntimeStatusPane(
    connectionId: String,
    channelName: String,
    channelType: String?,
) {
    var state by remember(channelName) { mutableStateOf<RuntimeState>(RuntimeState.Loading) }
    var refreshTick by remember(channelName) { mutableStateOf(0) }

    // 查询在 IO（getChannelStatus 链路内部 withContext(IO)）；state 写回触发主线程重组。
    LaunchedEffect(channelName, refreshTick) {
        state = RuntimeState.Loading
        val service = IbmMQModule.serviceOrNull()
        state = when {
            service == null -> RuntimeState.Failed(stringResource("ibm_mq_channel_status_load_failed_service"))
            else -> try {
                val detail = service.getChannelStatus(connectionId, channelName)
                if (detail == null) RuntimeState.NotRunning else RuntimeState.Ready(detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // PCFException → 原因码可读文案（MQJE001 裸串太晦涩），其余取 message
                RuntimeState.Failed(MqrcTranslator.describe(e))
            }
        }
    }

    // ---- 重置序列号的状态与执行 ----
    var showResetDialog by remember { mutableStateOf(false) }
    var resetExecuting by remember { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    fun onResetConfirmed(newSeqNum: Long) {
        val executor = IbmMQModule.mqscExecutorOrNull() ?: run {
            resetError = stringResource("ibm_mq_channel_reset_no_executor")
            return
        }
        scope.launch {
            resetExecuting = true
            resetError = null
            try {
                val result = executor.executeSingle(
                    connectionId,
                    "RESET CHANNEL('$channelName') SEQNUM($newSeqNum)",
                    1,
                )
                if (result.success) {
                    showResetDialog = false
                    snackbarHostState.showSnackbar(
                        stringResource("ibm_mq_channel_reset_success").withArg(newSeqNum.toString()),
                    )
                    refreshTick++ // 重置成功后自动刷新运行状态
                } else {
                    // 失败保留对话框，显示原因码可读文案，用户可重试或取消。
                    resetError = result.errorMessage ?: "MQRC ${result.errorCode ?: "?"}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                resetError = e.message ?: e::class.simpleName ?: "unknown"
            } finally {
                resetExecuting = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 分组头：说明 + 刷新按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("ibm_mq_channel_status_header_desc"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                PressableButton(
                    onClick = { refreshTick++ },
                    standardMinWidth = false,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource("ibm_mq_channel_status_refresh"))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            when (val s = state) {
                RuntimeState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource("ibm_mq_channel_status_loading"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is RuntimeState.Failed -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource("ibm_mq_channel_status_load_failed").withArg(s.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        PressableButton(onClick = { refreshTick++ }) {
                            Text(stringResource("ibm_mq_channel_status_retry"))
                        }
                    }
                }

                RuntimeState.NotRunning -> Text(
                    text = stringResource("ibm_mq_channel_status_not_running"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is RuntimeState.Ready -> RuntimeRows(
                    detail = s.detail,
                    channelType = channelType,
                    onResetClick = {
                        resetError = null
                        showResetDialog = true
                    },
                )
            }
        }

        // 局部 Snackbar（独立窗口够不到主窗口宿主）。
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    // 重置序列号二次确认对话框。
    if (showResetDialog) {
        val currentSeq = (state as? RuntimeState.Ready)?.detail?.let { it.currentSeqNo ?: it.lastSeqNum }
        ChannelResetSeqnumDialog(
            channelName = channelName,
            currentSeqNum = currentSeq,
            executing = resetExecuting,
            errorText = resetError,
            onConfirm = ::onResetConfirmed,
            onDismiss = { if (!resetExecuting) showResetDialog = false },
        )
    }
}

/** 就绪态的指标行（按通道类型过滤：不适用 → 「不适用」，适用但缺数据 → 「-」）。 */
@Composable
private fun RuntimeRows(
    detail: ChannelStatusDetail,
    channelType: String?,
    onResetClick: () -> Unit,
) {
    val a = ChannelFieldApplicability.of(channelType)
    val na = stringResource("ibm_mq_channel_status_na")

    fun metric(applies: Boolean, raw: Long?): String = when {
        !applies -> na
        raw == null -> "-"
        else -> raw.toString()
    }

    // 通道状态（有实例行必有，理论上非空）。
    StatusRow(
        label = stringResource("ibm_mq_channel_status_prop_status"),
        value = detail.status ?: stringResource("ibm_mq_channel_status_unknown_value"),
    )

    // 当前序列号：发送侧通道显示重置按钮（接收侧由对端管理序列号，不给按钮）。
    if (a.seq || a.connectionLike) {
        StatusRow(
            label = stringResource("ibm_mq_channel_status_prop_seq"),
            value = metric(a.seq, detail.currentSeqNo),
            trailing = if (a.reset && detail.currentSeqNo != null) {
                {
                    PressableButton(
                        onClick = onResetClick,
                        standardMinWidth = false,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { Text(stringResource("ibm_mq_channel_status_prop_seq_reset")) }
                }
            } else {
                null
            },
        )
    }

    // In-doubt：发送侧语义（有未决消息 = 是，警告色）。
    if (a.inDoubt || a.connectionLike) {
        val inDoubt = detail.inDoubt
        StatusRow(
            label = stringResource("ibm_mq_channel_status_prop_indoubt"),
            value = when {
                !a.inDoubt -> na
                inDoubt == null -> "-"
                inDoubt -> stringResource("ibm_mq_channel_status_indoubt_yes")
                else -> stringResource("ibm_mq_channel_status_indoubt_no")
            },
            valueColor = if (a.inDoubt && inDoubt == true) MaterialTheme.colorScheme.error else null,
        )
    }

    // 已处理消息数 / 字节（发送侧 BYTSSENT、接收侧 BYTSRCVD，双向通道两行都出）。
    if (a.msgs || a.bytesSent || a.bytesRcvd || a.connectionLike) {
        StatusRow(
            label = stringResource("ibm_mq_channel_status_prop_msgs"),
            value = metric(a.msgs, detail.messages),
        )
        StatusRow(
            label = stringResource("ibm_mq_channel_status_prop_bytes_sent"),
            value = metric(a.bytesSent, detail.bytesSent),
        )
        StatusRow(
            label = stringResource("ibm_mq_channel_status_prop_bytes_rcvd"),
            value = metric(a.bytesRcvd, detail.bytesReceived),
        )

        // 最后消息时间：LSTMSGDA + LSTMSGTI → yyyy-MM-dd HH:mm:ss；缺任一段显示「-」。
        val lastMsg = when {
            !a.msgs -> na
            else -> formatLastMsgTime(detail.lastMsgDate, detail.lastMsgTime) ?: "-"
        }
        StatusRow(
            label = stringResource("ibm_mq_channel_status_prop_last_msg"),
            value = lastMsg,
        )
    }
}

/** 单行：左 = 指标名，右 = 值（可选尾随操作，如重置按钮）。 */
@Composable
private fun StatusRow(
    label: String,
    value: String,
    valueColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(160.dp),
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

/** LSTMSGDA(yyyyMMdd) + LSTMSGTI(HHmmss) → yyyy-MM-dd HH:mm:ss；格式不符原样拼接。 */
internal fun formatLastMsgTime(date: String?, time: String?): String? {
    if (date.isNullOrBlank() && time.isNullOrBlank()) return null
    val d = date?.takeIf { it.isNotBlank() } ?: "-"
    val t = time?.takeIf { it.isNotBlank() } ?: "-"
    val dateText = if (d.length == 8 && d.all { it.isDigit() }) {
        "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}"
    } else {
        d
    }
    val timeText = if (t.length == 6 && t.all { it.isDigit() }) {
        "${t.substring(0, 2)}:${t.substring(2, 4)}:${t.substring(4, 6)}"
    } else {
        t
    }
    return "$dateText $timeText"
}

/** 运行状态面板的内部状态机（加载 / 失败 / 未运行 / 就绪）。 */
private sealed interface RuntimeState {
    data object Loading : RuntimeState
    data class Failed(val message: String) : RuntimeState
    data object NotRunning : RuntimeState
    data class Ready(val detail: ChannelStatusDetail) : RuntimeState
}

/**
 * 运行指标的通道类型适用性：类型可读名来自慢扫快照的 channelType
 * （SENDER / RECEIVER / SERVER / REQUESTER / SVRCONN / CLUSRCVR / CLUSSDR…）。
 * 未知类型（客户端升级新增 / mapper 反查失败）按保守处理：字段照展、缺数据「-」、
 * 不给重置按钮。
 */
internal data class ChannelFieldApplicability(
    val seq: Boolean,
    val reset: Boolean,
    val inDoubt: Boolean,
    val msgs: Boolean,
    val bytesSent: Boolean,
    val bytesRcvd: Boolean,
    val connectionLike: Boolean,
) {
    companion object {
        /** 发送侧通道：持有发送序列号，可重置；in-doubt / BYTSSENT 适用。 */
        private val SENDER_LIKE = setOf("SENDER", "SERVER", "REQUESTER", "CLUSSDR")

        /** 接收侧通道：序列号由发送方管理（不提供重置）；BYTSRCVD 适用。 */
        private val RECEIVER_LIKE = setOf("RECEIVER", "CLUSRCVR")

        /** 连接型通道（无运行指标，相关字段逐行「不适用」）。 */
        private val CONNECTION_LIKE = setOf("SVRCONN", "CLNTCONN", "MQTT", "AMQP")

        fun of(channelType: String?): ChannelFieldApplicability {
            if (channelType == null) {
                // 未知类型：保守展示，绝不给重置按钮。
                return ChannelFieldApplicability(
                    seq = true, reset = false, inDoubt = true, msgs = true,
                    bytesSent = true, bytesRcvd = true, connectionLike = false,
                )
            }
            if (channelType in CONNECTION_LIKE) {
                return ChannelFieldApplicability(
                    seq = false, reset = false, inDoubt = false, msgs = false,
                    bytesSent = false, bytesRcvd = false, connectionLike = true,
                )
            }
            val isSender = channelType in SENDER_LIKE
            val isReceiver = channelType in RECEIVER_LIKE
            return ChannelFieldApplicability(
                seq = isSender || isReceiver,
                reset = isSender,
                inDoubt = isSender,
                msgs = isSender || isReceiver,
                bytesSent = isSender,
                bytesRcvd = isReceiver,
                connectionLike = false,
            )
        }
    }
}
