package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.ui.graphics.vector.ImageVector
import com.mqclient.ibmmq.pcf.MqObjectSnapshot

/** 通道的展示类型（决定第一行 typeIcon 的图标）。 */
enum class ChannelType {
    /** 发送通道（SDR）。 */
    SENDER,

    /** 接收通道（RCVR）。 */
    RECEIVER,

    /** 请求通道（RQSTR）。 */
    REQUESTER,

    /** 服务器通道（SVR）。 */
    SERVER,

    /** 集群发送 / 接收通道（CLUSDR / CLUSRCVR）。 */
    CLUSTER,

    /** 服务器连接通道（SVRCONN）。 */
    SERVER_CONN,

    /** 客户端连接通道（CLNTCONN）。 */
    CLIENT_CONN,

    /** 未知 / 未识别。 */
    UNKNOWN,
}

/**
 * 通道类型 → 图标解析器，镜像 [QueueIconResolver]。
 *
 * 类型来源：慢扫写入的 `channelType`（[MqObjectSnapshot.channelType]，由
 * `MQIACH_CHANNEL_TYPE` 反查成 `MQCHT_*` 可读名再去掉前缀得到 `SDR` / `RCVR` /
 * `SVRCONN` 等）。反查不到 / 值非预期一律落 [ChannelType.UNKNOWN]——不猜。
 */
object ChannelIconResolver {

    /** 从快照解析通道展示类型。 */
    fun resolveType(snapshot: MqObjectSnapshot): ChannelType = when (snapshot.channelType) {
        "SDR" -> ChannelType.SENDER
        "RCVR" -> ChannelType.RECEIVER
        "RQSTR" -> ChannelType.REQUESTER
        "SVR" -> ChannelType.SERVER
        "CLUSDR", "CLUSRCVR" -> ChannelType.CLUSTER
        "SVRCONN" -> ChannelType.SERVER_CONN
        "CLNTCONN" -> ChannelType.CLIENT_CONN
        else -> ChannelType.UNKNOWN
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
        ChannelType.UNKNOWN -> Icons.Default.HelpOutline    // 未知类型兜底
    }

    /** 从快照直接解析图标（[ChannelListPanel] 卡片转换入口）。 */
    fun resolveIcon(snapshot: MqObjectSnapshot): ImageVector = resolveIcon(resolveType(snapshot))
}
