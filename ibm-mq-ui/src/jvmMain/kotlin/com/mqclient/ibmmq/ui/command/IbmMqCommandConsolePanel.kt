package com.mqclient.ibmmq.ui.command

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.command.MqscCommandExecutor
import com.mqclient.ibmmq.command.MqscCommandParser
import com.mqclient.ibmmq.command.MqscSyntaxHighlighter
import com.mqclient.mqui.command.CommandConsoleConfig
import com.mqclient.mqui.command.CommandConsolePanel
import com.mqclient.mqui.command.CommandConsoleViewModel

/**
 * IBM MQ 命令控制台：把本模块的 MQSC 实现（解析 / 高亮 / 执行）组装进 mq-ui 的
 * 通用 [CommandConsolePanel]。一个实例绑定一条连接。
 *
 * 调用方需保证模块已初始化（[IbmMQModule.mqscExecutorOrNull] 非 null）——
 * 未就绪时这里直接不渲染，宿主侧（IbmMqUI.contentFor）已先做同样检查。
 */
@Composable
fun IbmMqCommandConsolePanel(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val mqscExecutor = IbmMQModule.mqscExecutorOrNull() ?: return

    val config = remember(connectionId, mqscExecutor) {
        CommandConsoleConfig(
            title = "MQSC",
            fileExtension = "mqsc",
            syntaxHighlighter = MqscSyntaxHighlighter(),
            commandParser = MqscCommandParser(),
            commandExecutor = MqscCommandExecutor(connectionId, mqscExecutor),
        )
    }
    val scope = rememberCoroutineScope()
    val state = remember(connectionId, config) {
        CommandConsoleViewModel(config, scope)
    }

    CommandConsolePanel(
        config = config,
        state = state,
        modifier = modifier,
    )
}
