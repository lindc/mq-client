package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.mqui.objectlist.CardAction

/**
 * 构造 IBM MQ 通道卡片上的操作按钮。
 *
 * 按钮布局（与队列卡片同源：运维动作在前、属性按钮恒在尾位）：
 *  - **启动 / 停止**：按通道类型与状态条件追加（见下方规则）；
 *  - **属性**：恒在最后，与其他对象类型的尾按钮位置一致。
 *
 * 启动 / 停止规则：
 *  - **连接型通道**（SVRCONN / CLNTCONN / MQTT / AMQP）本身没有「启动 / 停止」语义，
 *    直接隐藏这两个按钮（按用户确认：此类通道不暴露启停）；
 *  - **接收通道（RECEIVER）**：由远端发送方发起（应答端无法主动拨出），本地 START 无意义，
 *    且集群通道（CLUSRCVR / CLUSSDR）由集群 / 通道发起器自动管理 —— 两者的启停按钮都
 *    隐藏（按用户确认）；
 *  - 其余类型：
 *      - 运行态（RUNNING / BINDING / PAUSED / REQUESTING / RETRYING）→ 只显示【停止】
 *        （RETRYING 用 `MODE(FORCE)`，仍是危险操作，走二次确认）；
 *      - 停止 / 未运行态（STOPPED / INACTIVE / 已定义但无状态行）→ 只显示【启动】；
 *      - 中间态（STARTING / STOPPING / INITIALIZING）→ 启动、停止都显示但禁用
 *        （操作进行中，避免重复触发），tooltip 提示「操作进行中」；
 *      - 未知状态 → 启动、停止都显示但禁用，tooltip 提示「状态未知，暂时无法操作」。
 *
 * 【启动】直接执行；【停止】是危险操作（[CardAction.isDangerous]），由调用方接
 * [DangerousCommandDialog] 做二次确认（命令文本由面板按类型 / 状态拼装）。
 */
object ChannelCardActions {

    private val HIDE_START_STOP = setOf(
        ChannelType.SERVER_CONN,
        ChannelType.CLIENT_CONN,
        ChannelType.MQTT,
        ChannelType.AMQP,
        // 接收通道由远端发送方发起（应答端无法主动拨出，本地 START 无意义）；
        // 集群通道由集群 / 通道发起器自动管理 —— 启停按钮都不暴露（按用户确认）。
        ChannelType.RECEIVER,
        ChannelType.CLUSTER,
    )

    /** 运行态：仅允许停止。 */
    internal val RUNNING_LIKE = setOf("RUNNING", "BINDING", "PAUSED", "REQUESTING", "RETRYING")

    /** 停止 / 未运行态：允许启动（含已定义但无状态行的「未运行」）。 */
    internal val STOPPED_LIKE = setOf("STOPPED", "INACTIVE")

    /** 中间态：启停都禁用。 */
    private val BUSY_LIKE = setOf("STARTING", "STOPPING", "INITIALIZING")

    /**
     * 构造通道卡片的操作按钮列表。
     *
     * @param snapshot 通道快照。
     * @param onOpenProperties 打开通道属性 Dialog 的回调。
     * @param onStart 启动通道的回调（仅当卡片显示【启动】按钮时触发）。
     * @param onStop 停止通道的回调（仅当卡片显示【停止】按钮时触发；危险操作，调用方二次确认）。
     */
    fun create(
        snapshot: MqObjectSnapshot,
        onOpenProperties: () -> Unit,
        onStart: () -> Unit = {},
        onStop: () -> Unit = {},
    ): List<CardAction> {
        val actions = mutableListOf<CardAction>()

        val type = ChannelIconResolver.resolveType(snapshot)
        if (type !in HIDE_START_STOP) {
            when (val status = snapshot.channelStatus) {
                in RUNNING_LIKE -> actions += stopAction(onStop)
                in STOPPED_LIKE, null -> actions += startAction(onStart)
                in BUSY_LIKE -> {
                    actions += startAction(onStart, enabled = false, tooltip = "operation_busy")
                    actions += stopAction(onStop, enabled = false, tooltip = "operation_busy")
                }
                else -> {
                    // 未识别的状态值：信息不足，禁用并提示。
                    actions += startAction(onStart, enabled = false, tooltip = "operation_unknown_state")
                    actions += stopAction(onStop, enabled = false, tooltip = "operation_unknown_state")
                }
            }
        }

        // 属性按钮恒在尾位。
        actions += CardAction(
            id = "channel.properties",
            icon = Icons.Default.Info,
            tooltip = "channel_action_properties",
            onClick = onOpenProperties,
        )
        return actions
    }

    private fun startAction(onClick: () -> Unit, enabled: Boolean = true, tooltip: String = "channel_action_start") =
        CardAction(
            id = "channel.start",
            icon = Icons.Default.PlayArrow,
            tooltip = tooltip,
            enabled = enabled,
            onClick = onClick,
        )

    private fun stopAction(onClick: () -> Unit, enabled: Boolean = true, tooltip: String = "channel_action_stop") =
        CardAction(
            id = "channel.stop",
            icon = Icons.Default.Stop,
            tooltip = tooltip,
            enabled = enabled,
            isDangerous = true,
            onClick = onClick,
        )
}
