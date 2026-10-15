package com.mqclient.kafka.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mqclient.core.AdapterUI
import com.mqclient.core.ModuleI18nTable
import com.mqclient.kafka.KafkaModule
import com.mqclient.uicommon.i18n.stringResource
import mq_client.kafka_ui.generated.resources.Res

/**
 * Kafka 模块的 UI 入口（AdapterUI SPI）：与 ibm-mq 的 IbmMqUI 同构 —— 主页面只放
 * 占位文案，真实内容经 [contentFor] 按导航项（Tab 的 objectType）贡献；连接 / 断开
 * 等操作挂在侧边栏连接条目的右键菜单（见 KafkaModule 的 connectAsync / disconnectAsync）。
 */
class KafkaUI : AdapterUI {
    override val serviceId = "kafka"
    override val title = "Kafka"

    /** 模块私有 i18n 表（files/i18n/kafka_ui_<lang>.json）。 */
    override val i18nTables: List<ModuleI18nTable> = listOf(
        ModuleI18nTable(base = "kafka_ui") { language ->
            Res.readBytes("files/i18n/kafka_ui_$language.json").decodeToString()
        },
    )

    override val content: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("kafka_ui_page_placeholder"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /**
     * 模块贡献真实内容的导航项：`topics`（Topic 列表）/ `groups`（Consumer Group
     * 列表）。模块未初始化（拿不到连接上下文）时回落 null —— 宿主显示「coming
     * soon」占位页，极端时序下不崩、不破 Tab。
     */
    override fun contentFor(objectType: String, connectionId: String): (@Composable () -> Unit)? {
        return when (objectType) {
            "topics" ->
                if (KafkaModule.connectionOrNull(connectionId) == null) {
                    null
                } else {
                    {
                        TopicListPanel(
                            connectionId = connectionId,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            "groups" ->
                if (KafkaModule.connectionOrNull(connectionId) == null) {
                    null
                } else {
                    {
                        ConsumerGroupListPanel(
                            connectionId = connectionId,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            else -> null
        }
    }
}
