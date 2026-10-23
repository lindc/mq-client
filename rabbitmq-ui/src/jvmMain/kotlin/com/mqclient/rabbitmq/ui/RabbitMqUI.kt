package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
        if (!connected) return null
        // 四个页都是「读同一份慢扫缓存」的视图，所以「部分数据未刷新」提示在**同一处**
        // 统一包上（contentFor 是四个面板的唯一入口）—— 各个面板里各写一份是同类
        // 提示四处漏三处的温床。
        return when (objectType) {
            "queues" -> {
                { RabbitMqPanelHost(connectionId) { RabbitMqQueuesPanel(connectionId, Modifier.fillMaxSize()) } }
            }
            "exchanges" -> {
                { RabbitMqPanelHost(connectionId) { RabbitMqExchangesPanel(connectionId, Modifier.fillMaxSize()) } }
            }
            "overview" -> {
                { RabbitMqPanelHost(connectionId) { RabbitMqOverviewPanel(connectionId, Modifier.fillMaxSize()) } }
            }
            "topology" -> {
                { RabbitMqPanelHost(connectionId) { RabbitMqTopologyPanel(connectionId, Modifier.fillMaxSize()) } }
            }
            else -> null
        }
    }
}

/**
 * 面板外壳：顶部「部分数据未刷新」提示（有的话）+ 剩余空间给面板本体。
 *
 * `weight(1f)` 而不是 `fillMaxSize()`：提示条占高后内容必须缩，不能溢出到窗口外。
 */
@Composable
private fun RabbitMqPanelHost(
    connectionId: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        RabbitMqPartialScanBanner(connectionId)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
