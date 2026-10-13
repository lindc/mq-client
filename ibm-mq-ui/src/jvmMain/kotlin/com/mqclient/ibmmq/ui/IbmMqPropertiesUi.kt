package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.ui.properties.content.PropertyGroupsPane
import com.mqclient.ibmmq.ui.properties.resolveQmgrPropertyGroups
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException

/**
 * IBM MQ 的连接属性面板贡献（SPI，见 [ConnectionPropertiesUi]）：展示**队列管理器
 * 本身**的属性，不是连接配置。
 *
 * 与队列 / 通道等对象属性对话框同一套模式：PCF 全属性快照（[queryQmgrSnapshot]）
 * + 分组表（QmgrPropertyGroups，分页对齐 MQ Explorer 的 Queue Manager properties
 * 对话框），布局走共用的 [PropertyGroupsPane]。数据查询走 [IbmMQModule.serviceOrNull]
 * 直接调 ibm-mq-service —— 与连接表单同一姿势（面板归模块，宿主只给容器）。
 * 加载 / 错误 / 重试都是面板自己的状态。
 */
class IbmMqPropertiesUi : ConnectionPropertiesUi {
    override val serviceId = "ibm-mq"

    @Composable
    override fun Content(connection: ConnectionInfo) {
        QmgrPropertiesPanel(connectionId = connection.id)
    }
}

@Composable
internal fun QmgrPropertiesPanel(connectionId: String) {
    var snapshot by remember { mutableStateOf<MqObjectSnapshot?>(null) }
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
                snapshot = service.queryQmgrSnapshot(connectionId)
                if (snapshot == null) {
                    error = "MQCMD_INQUIRE_Q_MGR: empty response"
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                snapshot = null
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

        else -> {
            val groups = remember(snapshot) {
                resolveQmgrPropertyGroups(snapshot!!)
            }
            PropertyGroupsPane(
                groups = groups,
                defaultSelectionKey = "ibm_mq_qmgr_props_group_general",
            )
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
