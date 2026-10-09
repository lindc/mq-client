package com.mqclient.ibmmq.ui.ai

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.ui.ChannelIconResolver
import com.mqclient.ibmmq.ui.ChannelType
import com.mqclient.ibmmq.ui.topology.isAliasQueue
import com.mqclient.ibmmq.ui.topology.isLocalQueue
import com.mqclient.ibmmq.ui.topology.isRemoteQueue
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.uicommon.AiAskRequest

/**
 * 「询问 AI」在三类列表卡片上的共享按钮构造：**图标区的第一个位置**（AI 绝对最左，
 * 见各面板的 actions 拼接顺序），tooltip 与属性对话框 footer 按钮共用
 * `mq_action_ask_ai`。
 *
 * 显示规则见 [isCardAiTarget]：本地 / 别名 / 远程队列、SENDER 通道、监听器
 * （入口约定，见 AiChainBuilder）。队列 / 通道面板在拼接层判定；监听器卡片无拓扑
 * 前置，规则仍留在 ListenerCardActions 内。
 */
internal fun askAiCardAction(onAskAi: () -> Unit): CardAction = CardAction(
    id = "mq.ask_ai",
    icon = Icons.Default.Android,
    tooltip = "mq_action_ask_ai",
    onClick = onAskAi,
)

/**
 * 卡片 AI 入口的统一回调工厂：从缓存构建 chain 请求后交给宿主（LocalAiAsk）。
 * 构建失败（缓存未就绪 / 子型不符）时静默忽略——按钮由调用方按
 * [isCardAiTarget] + 宿主是否可用决定显示。
 */
internal fun askAiClick(
    connectionId: String,
    objectType: MqObjectType,
    objectName: String,
    aiAsk: (AiAskRequest) -> Unit,
): () -> Unit = {
    AiChainBuilder.build(connectionId, objectType, objectName)?.let(aiAsk)
}

/** 卡片 AI 图标可见性：快照是合法入口对象（与属性对话框 footer 同一规则）。 */
internal fun isCardAiTarget(objectType: MqObjectType, snapshot: MqObjectSnapshot): Boolean =
    when (objectType) {
        // 本地 / 别名 / 远程定义；模型 / 传输队列无入口语义。
        MqObjectType.QUEUE ->
            snapshot.isLocalQueue() || snapshot.isAliasQueue() || snapshot.isRemoteQueue()
        MqObjectType.CHANNEL -> ChannelIconResolver.resolveType(snapshot) == ChannelType.SENDER
        MqObjectType.LISTENER -> true
        else -> false
    }
