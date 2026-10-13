package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.graphics.vector.ImageVector
import com.mqclient.core.log.Log
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 通道的展示类型（决定第一行 typeIcon 的图标 + 悬停文案）。
 *
 * 类型来源：慢扫写入的 `channelType`（[MqObjectSnapshot.channelType]，由
 * `MQIACH_CHANNEL_TYPE` 经 [com.mqclient.ibmmq.pcf.MqAttributeNameMapper] 反查成
 * `MQCHT_*` 可读名、再去掉前缀得到的**长名**：`SENDER` / `RECEIVER` / `SVRCONN`…
 * （不是 `SDR` / `RCVR` 之类的短码——之前错配短码，导致 Sender/Receiver/Server
 * 等被误判成 UNKNOWN、显示问号）。
 *
 * 覆盖 IBM MQ 全部通道类型（jar 中核实的 `MQCHT_*` 常量）。未知类型不再给问号图标，
 * 而是中性 [Icons.Default.Category] + 悬停显示「未知类型(原始值=XXX)」，并把原始值
 * 打到日志，方便补全枚举。
 */
enum class ChannelType {
    /** 发送通道（SDR / MQCHT_SENDER）。 */
    SENDER,

    /** 接收通道（RCVR / MQCHT_RECEIVER）。 */
    RECEIVER,

    /** 请求通道（RQSTR / MQCHT_REQUESTER）。 */
    REQUESTER,

    /** 服务器通道（SVR / MQCHT_SERVER）。 */
    SERVER,

    /** 集群发送 / 接收通道（CLUSDR / CLUSRCVR）。 */
    CLUSTER,

    /** 服务器连接通道（SVRCONN）。 */
    SERVER_CONN,

    /** 客户端连接通道（CLNTCONN）。 */
    CLIENT_CONN,

    /** MQTT 通道（MQCHT_MQTT）。 */
    MQTT,

    /** AMQP 通道（MQCHT_AMQP）。 */
    AMQP,

    /** 未知 / 未识别。 */
    UNKNOWN,
}

private val log = Log.withTag("ibm-mq-ui")

object ChannelIconResolver {

    /** 从快照解析通道展示类型（匹配 MQCHT 长名；未知时记日志原始值）。 */
    fun resolveType(snapshot: MqObjectSnapshot): ChannelType = when (snapshot.channelType) {
        "SENDER" -> ChannelType.SENDER
        "RECEIVER" -> ChannelType.RECEIVER
        "REQUESTER" -> ChannelType.REQUESTER
        "SERVER" -> ChannelType.SERVER
        "CLNTCONN" -> ChannelType.CLIENT_CONN
        "SVRCONN" -> ChannelType.SERVER_CONN
        "CLUSRCVR", "CLUSSDR" -> ChannelType.CLUSTER
        "MQTT" -> ChannelType.MQTT
        "AMQP" -> ChannelType.AMQP
        else -> {
            if (snapshot.channelType != null) {
                log.warn("未识别的通道类型（已落 UNKNOWN，请补全枚举）: ${snapshot.channelType}")
            }
            ChannelType.UNKNOWN
        }
    }

    /** 通道展示类型 → 图标（全部取自 material-icons-extended）。 */
    fun resolveIcon(type: ChannelType): ImageVector = when (type) {
        ChannelType.SENDER -> Icons.Default.CallMade        // 向外发送
        ChannelType.RECEIVER -> Icons.Default.CallReceived  // 向内接收
        ChannelType.REQUESTER -> Icons.Default.CallReceived
        ChannelType.SERVER -> Icons.Default.Lan             // 点对点服务端
        ChannelType.CLUSTER -> Icons.Default.Hub            // 集群汇聚
        ChannelType.SERVER_CONN -> Icons.Default.Cloud      // 远端接入
        ChannelType.CLIENT_CONN -> Icons.Default.CloudQueue // 客户端接入
        ChannelType.MQTT -> Icons.Default.Sync             // MQTT 协议通道
        ChannelType.AMQP -> Icons.Default.ImportExport      // AMQP 协议通道
        ChannelType.UNKNOWN -> Icons.Default.Category       // 未知类型：中性图标，不再用问号
    }

    /** 从快照直接解析图标（[ChannelListPanel] 卡片转换入口）。 */
    fun resolveIcon(snapshot: MqObjectSnapshot): ImageVector = resolveIcon(resolveType(snapshot))

    /** 通道展示类型 → 悬停文案（已知类型给可读名；未知类型附原始值，便于排查）。 */
    fun resolveLabel(snapshot: MqObjectSnapshot): String {
        val type = resolveType(snapshot)
        return when (type) {
            ChannelType.SENDER -> stringResource("ibm_mq_channel_type_sender")
            ChannelType.RECEIVER -> stringResource("ibm_mq_channel_type_receiver")
            ChannelType.REQUESTER -> stringResource("ibm_mq_channel_type_requester")
            ChannelType.SERVER -> stringResource("ibm_mq_channel_type_server")
            ChannelType.CLUSTER -> stringResource("ibm_mq_channel_type_cluster")
            ChannelType.SERVER_CONN -> stringResource("ibm_mq_channel_type_server_conn")
            ChannelType.CLIENT_CONN -> stringResource("ibm_mq_channel_type_client_conn")
            ChannelType.MQTT -> stringResource("ibm_mq_channel_type_mqtt")
            ChannelType.AMQP -> stringResource("ibm_mq_channel_type_amqp")
            ChannelType.UNKNOWN ->
                "${stringResource("ibm_mq_channel_type_unknown")} ${stringResource("ibm_mq_channel_type_unknown_raw").withArg(snapshot.channelType ?: "null")}"
        }
    }
}
