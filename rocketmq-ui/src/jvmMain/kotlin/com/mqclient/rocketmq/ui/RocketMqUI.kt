package com.mqclient.rocketmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mqclient.core.AdapterUI
import com.mqclient.core.ModuleI18nTable
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.uicommon.i18n.stringResource
import mq_client.rocketmq_ui.generated.resources.Res

/**
 * RocketMQ 模块的 UI 入口（AdapterUI SPI）：与 RabbitMqUI 同构 —— 主页面只放占位
 * 文案，真实内容经 [contentFor] 按导航项（Tab 的 objectType）贡献；连接 / 断开
 * 等操作挂在侧边栏连接条目的右键菜单（见 RocketMqModule 的 connectAsync /
 * disconnectAsync）。
 */
class RocketMqUI : AdapterUI {
    override val serviceId = "rocketmq"
    override val title = "RocketMQ"

    /** 模块私有 i18n 表（files/i18n/rocketmq_ui_<lang>.json）。 */
    override val i18nTables: List<ModuleI18nTable> = listOf(
        ModuleI18nTable(base = "rocketmq_ui") { language ->
            Res.readBytes("files/i18n/rocketmq_ui_$language.json").decodeToString()
        },
    )

    override val content: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("rocketmq_ui_page_placeholder"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /**
     * 模块贡献真实内容的导航项：`topics`（主题列表）/ `groups`（消费组列表）/
     * `overview`（集群概览）。拓扑图 Phase 2 用 topic route 画，本阶段未注册。
     * 模块未初始化（拿不到连接上下文）时回落 null —— 宿主显示「coming soon」占位页。
     */
    override fun contentFor(objectType: String, connectionId: String): (@Composable () -> Unit)? {
        val connected = RocketMqModule.connectionOrNull(connectionId) != null
        return when (objectType) {
            "topics" ->
                if (connected) {
                    { RocketMqTopicsPanel(connectionId = connectionId, modifier = Modifier.fillMaxSize()) }
                } else {
                    null
                }
            "groups" ->
                if (connected) {
                    { RocketMqGroupsPanel(connectionId = connectionId, modifier = Modifier.fillMaxSize()) }
                } else {
                    null
                }
            "overview" ->
                if (connected) {
                    { RocketMqOverviewPanel(connectionId = connectionId, modifier = Modifier.fillMaxSize()) }
                } else {
                    null
                }
            "topology" ->
                if (connected) {
                    { com.mqclient.rocketmq.ui.topology.RocketMqTopologyPanel(connectionId = connectionId, modifier = Modifier.fillMaxSize()) }
                } else {
                    null
                }
            else -> null
        }
    }
}
