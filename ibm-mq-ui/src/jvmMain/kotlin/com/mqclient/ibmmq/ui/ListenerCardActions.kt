package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.ui.ai.askAiCardAction
import com.mqclient.mqui.objectlist.CardAction

/**
 * 构造 IBM MQ 监听器卡片上的操作按钮。
 *
 * 按钮布局（监听器卡片没有拓扑前置，「询问 AI」即图标区第一个；运维动作随后、
 * 属性按钮恒在尾位）：
 *  - **询问 AI**（可选）：宿主提供 AI 入口时显示（监听器全部类型都是入口对象）；
 *  - **启动 / 停止**：按监听器状态条件追加（见下方规则）；
 *  - **属性**：恒在最后，与各对象类型的尾按钮位置一致。
 *
 * 启动 / 停止规则（监听器全部类型都支持启停，没有「连接型」隐藏）：
 *  - 运行态（RUNNING）→ 只显示【停止】（危险操作，调用方接二次确认）；
 *  - 停止 / 未启动态（NOT_RUNNING / 无状态行 = 已定义但未启动）→ 只显示【启动】；
 *  - 中间态（STARTING / STOPPING）/ 未知态（UNKNOWN）→ 启动、停止都显示但禁用，
 *    分别提示「操作进行中」/「状态未知，暂时无法操作」。
 *
 * 【启动】直接执行；【停止】是危险操作（[CardAction.isDangerous]），由调用方接
 * [DangerousCommandDialog] 做二次确认（命令文本由面板拼装）。
 */
object ListenerCardActions {

    /** 运行态：仅允许停止。 */
    internal val RUNNING_LIKE = setOf("RUNNING")

    /** 停止 / 未启动态：允许启动（null = 已定义但无状态行，即未启动）。 */
    internal val STOPPED_LIKE = setOf("NOT_RUNNING")

    /** 中间态：启停都禁用。 */
    private val BUSY_LIKE = setOf("STARTING", "STOPPING")

    /**
     * 构造监听器卡片的操作按钮列表。
     *
     * @param snapshot 监听器快照。
     * @param onOpenProperties 打开监听器属性 Dialog 的回调。
     * @param onStart 启动监听器的回调（仅当卡片显示【启动】按钮时触发）。
     * @param onStop 停止监听器的回调（仅当卡片显示【停止】按钮时触发；危险操作，调用方二次确认）。
     * @param onAskAi 发起「询问 AI」的回调（宿主提供 AI 入口时显示；null = 不显示）。
     */
    fun create(
        snapshot: MqObjectSnapshot,
        onOpenProperties: () -> Unit,
        onStart: () -> Unit = {},
        onStop: () -> Unit = {},
        onAskAi: (() -> Unit)? = null,
    ): List<CardAction> {
        val actions = mutableListOf<CardAction>()

        // 「询问 AI」绝对最左（图标区第一个位置，与队列 / 通道面板的拼接顺序一致）。
        onAskAi?.let { actions += askAiCardAction(it) }

        when (val status = snapshot.listenerStatus) {
            in RUNNING_LIKE -> actions += stopAction(onStop)
            in STOPPED_LIKE, null -> actions += startAction(onStart)
            in BUSY_LIKE -> {
                actions += startAction(onStart, enabled = false, tooltip = "ibm_mq_operation_busy")
                actions += stopAction(onStop, enabled = false, tooltip = "ibm_mq_operation_busy")
            }
            else -> {
                // UNKNOWN 等未识别状态：信息不足，禁用并提示。
                actions += startAction(onStart, enabled = false, tooltip = "ibm_mq_operation_unknown_state")
                actions += stopAction(onStop, enabled = false, tooltip = "ibm_mq_operation_unknown_state")
            }
        }

        // 属性按钮恒在尾位。
        actions += CardAction(
            id = "listener.properties",
            icon = Icons.Default.Info,
            tooltip = "ibm_mq_listener_action_properties",
            onClick = onOpenProperties,
        )
        return actions
    }

    private fun startAction(onClick: () -> Unit, enabled: Boolean = true, tooltip: String = "ibm_mq_listener_action_start") =
        CardAction(
            id = "listener.start",
            icon = Icons.Default.PlayArrow,
            tooltip = tooltip,
            enabled = enabled,
            onClick = onClick,
        )

    private fun stopAction(onClick: () -> Unit, enabled: Boolean = true, tooltip: String = "ibm_mq_listener_action_stop") =
        CardAction(
            id = "listener.stop",
            icon = Icons.Default.Stop,
            tooltip = tooltip,
            enabled = enabled,
            isDangerous = true,
            onClick = onClick,
        )
}
