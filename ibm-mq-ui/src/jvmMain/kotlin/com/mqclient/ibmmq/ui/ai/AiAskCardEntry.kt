package com.mqclient.ibmmq.ui.ai

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.ui.ChannelIconResolver
import com.mqclient.ibmmq.ui.ChannelType
import com.mqclient.ibmmq.ui.topology.isLocalQueue
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.uicommon.AiAskRequest

/**
 * 「询问 AI」在三类列表卡片上的共享按钮构造：图标插在属性按钮**之前**（属性按钮
 * 恒在尾位的约定不破坏），tooltip 与属性对话框 footer 按钮共用 `mq_action_ask_ai`。
 *
 * 仅本地队列 / SENDER 通道 / 监听器三类卡片调用（入口约定，见 AiChainBuilder）。
 */
internal fun askAiCardAction(onAskAi: () -> Unit): CardAction = CardAction(
    id = "mq.ask_ai",
    icon = Icons.Default.AutoAwesome,
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
        MqObjectType.QUEUE -> snapshot.isLocalQueue()
        MqObjectType.CHANNEL -> ChannelIconResolver.resolveType(snapshot) == ChannelType.SENDER
        MqObjectType.LISTENER -> true
        else -> false
    }
