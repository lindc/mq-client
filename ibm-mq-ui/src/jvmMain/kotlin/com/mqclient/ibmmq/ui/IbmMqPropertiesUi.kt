package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionPropertiesUi
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.QmgrProperties
import com.mqclient.ibmmq.QmgrStatus
import com.mqclient.uicommon.PropertyRow
import com.mqclient.uicommon.PropertySection
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException

/**
 * IBM MQ 的连接属性面板贡献（SPI，见 [ConnectionPropertiesUi]）：展示**队列管理器
 * 本身**的属性（PCF 查询），不是连接配置。
 *
 * 数据查询走 [IbmMQModule.serviceOrNull] 直接调 ibm-mq-service —— 与连接表单同一
 * 姿势（面板归模块，宿主只给容器）。加载 / 错误 / 重试都是面板自己的状态。
 */
class IbmMqPropertiesUi : ConnectionPropertiesUi {
    override val serviceId = "ibm-mq"

    @Composable
    override fun Content(connection: ConnectionInfo) {
        QmgrPropertiesPanel(connectionId = connection.id)
    }
}

@Composable
private fun QmgrPropertiesPanel(connectionId: String) {
    var properties by remember { mutableStateOf<QmgrProperties?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    // 重试计数：变更它触发 LaunchedEffect 重跑（连接 id 不变时是唯一的重开入口）。
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(connectionId, retryKey) {
        loading = true
        error = null
        val service = IbmMQModule.serviceOrNull()
        if (service == null) {
            error = stringResource("qmgr_error_module_unavailable")
        } else {
            try {
                properties = service.queryQmgrProperties(connectionId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                properties = null
                error = t.message ?: t::class.simpleName ?: "unknown"
            }
        }
        loading = false
    }

    when {
        loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        error != null -> ErrorState(
            message = error.orEmpty(),
            onRetry = { retryKey++ },
            modifier = Modifier.fillMaxSize(),
        )

        properties != null -> QmgrPropertiesContent(
            properties = properties!!,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 属性正文：基本信息 / 运行状态 / 配置参数三节。滚动归本面板（见 ConnectionPropertiesUi 契约）。 */
@Composable
private fun QmgrPropertiesContent(properties: QmgrProperties, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        PropertySection(title = stringResource("qmgr_section_basic")) {
            PropertyRow(stringResource("qmgr_label_name"), properties.name)
            PropertyRow(stringResource("qmgr_label_version"), properties.version)
            PropertyRow(stringResource("qmgr_label_platform"), properties.platform)
            PropertyRow(stringResource("qmgr_label_command_level"), properties.commandLevel.toString())
        }
        PropertySection(title = stringResource("qmgr_section_status")) {
            PropertyRow(stringResource("qmgr_label_status"), properties.status?.let { statusLabel(it) } ?: "—")
            PropertyRow(stringResource("qmgr_label_start_time"), properties.startTime ?: "—")
        }
        PropertySection(title = stringResource("qmgr_section_config")) {
            PropertyRow(stringResource("qmgr_label_ccsid"), properties.ccsidName)
            PropertyRow(stringResource("qmgr_label_max_msg_length"), properties.maxMsgLength.toString())
            PropertyRow(stringResource("qmgr_label_dead_letter_q"), properties.deadLetterQueue)
        }
    }
}

/** 查询失败态：错误信息 + 重试。 */
@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource("qmgr_load_failed").format(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.padding(vertical = 8.dp))
        Button(onClick = onRetry) { Text(stringResource("common_retry")) }
    }
}

/** QMGR 运行状态的文案（i18n key 归 UI，service 只给枚举）。 */
@Composable
private fun statusLabel(status: QmgrStatus): String = when (status) {
    QmgrStatus.STARTING -> stringResource("qmgr_status_starting")
    QmgrStatus.RUNNING -> stringResource("qmgr_status_running")
    QmgrStatus.QUIESCING -> stringResource("qmgr_status_quiescing")
    QmgrStatus.STANDBY -> stringResource("qmgr_status_standby")
}
