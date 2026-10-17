package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mqclient.core.AdapterUI
import com.mqclient.core.ModuleI18nTable
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.ui.topology.RabbitMqTopologyPanel
import com.mqclient.uicommon.i18n.stringResource
import mq_client.rabbitmq_ui.generated.resources.Res

/**
 * RabbitMQ 模块的 UI 入口（AdapterUI SPI）：与 KafkaUI 同构 —— 主页面只放占位
 * 文案，真实内容经 [contentFor] 按导航项（Tab 的 objectType）贡献；连接 / 断开
 * 等操作挂在侧边栏连接条目的右键菜单（见 RabbitMqModule 的 connectAsync /
 * disconnectAsync）。
 */
class RabbitMqUI : AdapterUI {
    override val serviceId = "rabbitmq"
    override val title = "RabbitMQ"

    /** 模块私有 i18n 表（files/i18n/rabbitmq_ui_<lang>.json）。 */
    override val i18nTables: List<ModuleI18nTable> = listOf(
        ModuleI18nTable(base = "rabbitmq_ui") { language ->
            Res.readBytes("files/i18n/rabbitmq_ui_$language.json").decodeToString()
        },
    )

    override val content: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("rabbitmq_ui_page_placeholder"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /**
     * 模块贡献真实内容的导航项：`queues`（队列列表）/ `exchanges`（交换机列表）/
     * `overview`（概览）/ `topology`（路由拓扑图）。模块未初始化（拿不到连接上下文）
     * 时回落 null —— 宿主显示「coming soon」占位页，极端时序下不崩、不破 Tab。
     */
    override fun contentFor(objectType: String, connectionId: String): (@Composable () -> Unit)? {
        val connected = RabbitMqModule.connectionOrNull(connectionId) != null
        return when (objectType) {
            "queues" ->
                if (connected) {
                    {
                        RabbitMqQueuesPanel(
                            connectionId = connectionId,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    null
                }
            "exchanges" ->
                if (connected) {
                    {
                        RabbitMqExchangesPanel(
                            connectionId = connectionId,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    null
                }
            "overview" ->
                if (connected) {
                    {
                        RabbitMqOverviewPanel(
                            connectionId = connectionId,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    null
                }
            "topology" ->
                if (connected) {
                    {
                        RabbitMqTopologyPanel(
                            connectionId = connectionId,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    null
                }
            else -> null
        }
    }
}
