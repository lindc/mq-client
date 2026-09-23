package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mqclient.core.AdapterUI
import com.mqclient.core.ModuleI18nTable
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.ui.command.MqscConsolePanel
import com.mqclient.ibmmq.ui.command.MqscConsoleViewModel
import com.mqclient.uicommon.i18n.stringResource
import mq_client.ibm_mq_ui.generated.resources.Res

/**
 * IBM MQ 模块的主页面占位：连接的连接 / 断开等操作**不放这里** —— 将来挂在
 * 侧边栏连接条目的右键菜单里（入口见 [IbmMQModule.connectAsync] / [disconnectAsync]，
 * 已就绪）。本页面后续承载选中连接的队列 / 主题浏览内容。
 */
class IbmMqUI : AdapterUI {
    override val serviceId = "ibm-mq"
    override val title = "IBM MQ"

    /**
     * 本模块私有的 i18n 表（`files/i18n/ibm_mq_<lang>.json`）：导航项文案（`nav_*`）
     * 等模块字符串归自己的资源文件，加文案不需要动 ui-common 的公共表。
     * 读取必须用自己的 `Res`（compose 运行时资源按库包名隔离，见 [ModuleI18nTable]）。
     */
    override val i18nTables: List<ModuleI18nTable> = listOf(
        ModuleI18nTable(base = "ibm_mq") { language ->
            Res.readBytes("files/i18n/ibm_mq_$language.json").decodeToString()
        },
    )

    override val content: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("ibm_mq_page_placeholder"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /**
     * 「命令控制台」（objectType = `command`）由本模块贡献真实内容；其余导航项
     * 继续走宿主占位。模块未初始化（拿不到执行器）时也回落占位 —— 极端时序下
     * 不崩、不破 Tab。
     */
    override fun contentFor(objectType: String, connectionId: String): (@Composable () -> Unit)? {
        if (objectType != "command") return null
        val executor = IbmMQModule.mqscExecutorOrNull() ?: return null
        return {
            val scope = rememberCoroutineScope()
            val state = remember(connectionId) {
                MqscConsoleViewModel(connectionId, executor, scope)
            }
            MqscConsolePanel(state = state, modifier = Modifier.fillMaxSize())
        }
    }
}
