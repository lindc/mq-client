package com.mqclient.ibmmq.ui.properties

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.ui.ai.AiChainBuilder
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.ui.ChannelIconResolver
import com.mqclient.ibmmq.ui.ChannelType
import com.mqclient.ibmmq.ui.QmgrPropertiesPanel
import com.mqclient.ibmmq.ui.properties.content.ChannelPropertiesContent
import com.mqclient.ibmmq.ui.properties.content.MqObjectPropertiesContent
import com.mqclient.ibmmq.ui.topology.isLocalQueue
import com.mqclient.uicommon.LocalAiAsk
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource

/**
 * MQ 对象属性对话框：通用外壳（[ObjectDetailDialog]，自绘标题栏 / ESC）+ 按
 * [state.objectType] 分发到对应的内容组件。状态经
 * [ObjectPropertiesDialogController] 持有（对象列表页与拓扑图双击共用）。
 *
 * QUEUE_MANAGER 分支复用连接属性面板 [QmgrPropertiesPanel]（实时 INQUIRE Q_MGR，
 * 与 SPI 面板同数据源）——拓扑图 / 集群画布双击 QMgr 节点走这里。
 *
 * 底部操作区：本地队列 / 发送方通道 / 监听器三类目标对象多一个「询问 AI」按钮
 * （发起链路分析，见 [AiChainBuilder]）；其余对象保持默认单一「关闭」。
 */
@Composable
fun MqObjectPropertiesDialog(
    state: ObjectPropertiesDialogState,
    onDismiss: () -> Unit,
) {
    val aiStarter = LocalAiAsk.current
    // 「询问 AI」按钮可见性：只给本地队列 / SENDER 通道 / 监听器三类入口对象显示。
    // 快照从慢扫缓存按名取（与 MqObjectPropertiesContent 同源），缓存未就绪时按钮隐藏。
    val askAiVisible = aiStarter != null && isAiAskTarget(state)
    ObjectDetailDialog(
        title = state.objectName,
        onDismiss = onDismiss,
        footerActions = {
            if (askAiVisible) {
                FooterActionButton(label = stringResource("ibm_mq_mq_action_ask_ai")) {
                    AiChainBuilder.build(state.connectionId, state.objectType, state.objectName)
                        ?.let(aiStarter::onAsk)
                }
            }
            Spacer(Modifier.weight(1f))
            FooterActionButton(label = stringResource("common_close")) { onDismiss() }
        },
    ) {
        when (state.objectType) {
            MqObjectType.QUEUE -> MqObjectPropertiesContent(state, "ibm_mq_queue_props_group_general") { resolveQueuePropertyGroups(it) }
            MqObjectType.TOPIC -> MqObjectPropertiesContent(state, "ibm_mq_topic_props_group_general") { resolveTopicPropertyGroups(it) }
            MqObjectType.SUBSCRIPTION -> MqObjectPropertiesContent(state, "ibm_mq_sub_props_group_general") { resolveSubscriptionPropertyGroups(it) }
            MqObjectType.CHANNEL -> ChannelPropertiesContent(state)
            MqObjectType.LISTENER -> MqObjectPropertiesContent(state, "ibm_mq_listener_props_group_general") { resolveListenerPropertyGroups(it) }
            MqObjectType.SERVICE -> MqObjectPropertiesContent(state, "ibm_mq_service_props_group_general") { resolveServicePropertyGroups(it) }
            MqObjectType.PROCESS -> MqObjectPropertiesContent(state, "ibm_mq_process_props_group_general") { resolveProcessPropertyGroups(it) }
            MqObjectType.NAMELIST -> MqObjectPropertiesContent(state, "ibm_mq_namelist_props_group_general") { resolveNamelistPropertyGroups(it) }
            MqObjectType.AUTH_INFO -> MqObjectPropertiesContent(state, "ibm_mq_authinfo_props_group_general") { resolveAuthInfoPropertyGroups(it) }
            MqObjectType.COMM_INFO -> MqObjectPropertiesContent(state, "ibm_mq_comminfo_props_group_general") { resolveCommInfoPropertyGroups(it) }
            // QMgr：连接属性面板同一实现（实时查询），集群画布 / 拓扑双击复用。
            MqObjectType.QUEUE_MANAGER -> QmgrPropertiesPanel(state.connectionId)
        }
    }
}

/**
 * 「询问 AI」入口对象判定：本地队列（QLOCAL + USAGE=NORMAL）/ SENDER 通道 / 监听器。
 * 与卡片图标同一规则（卡片侧在各自 CardActions 里实现）。快照缺失（缓存未就绪）不算
 * 入口对象——按钮随内容占位一起隐藏。
 */
private fun isAiAskTarget(state: ObjectPropertiesDialogState): Boolean {
    val snapshot = cachedSnapshot(state) ?: return state.objectType == MqObjectType.LISTENER
    return when (state.objectType) {
        MqObjectType.QUEUE -> snapshot.isLocalQueue()
        MqObjectType.CHANNEL -> ChannelIconResolver.resolveType(snapshot) == ChannelType.SENDER
        MqObjectType.LISTENER -> true
        else -> false
    }
}

private fun cachedSnapshot(state: ObjectPropertiesDialogState): MqObjectSnapshot? =
    IbmMQModule.serviceOrNull()?.cachedObjects(state.connectionId, state.objectType)
        ?.firstOrNull { it.name == state.objectName }

/**
 * 拓扑节点类型 → MQ 对象类型（属性对话框按 [MqObjectType] 分发内容）。
 * alias / local / transmission / remote 四种队列角色都落在 QUEUE；
 * REMOTE_QMGR 是虚拟节点（无真实 MQ 对象），返回 null —— 调用方跳过打开对话框。
 */
internal fun com.mqclient.graph.TopologyNodeType.toMqObjectType(): MqObjectType? = when (this) {
    com.mqclient.graph.TopologyNodeType.ALIAS_QUEUE -> MqObjectType.QUEUE
    com.mqclient.graph.TopologyNodeType.LOCAL_QUEUE -> MqObjectType.QUEUE
    com.mqclient.graph.TopologyNodeType.REMOTE_QUEUE -> MqObjectType.QUEUE
    com.mqclient.graph.TopologyNodeType.TRANSMISSION_QUEUE -> MqObjectType.QUEUE
    com.mqclient.graph.TopologyNodeType.TOPIC -> MqObjectType.TOPIC
    com.mqclient.graph.TopologyNodeType.SUBSCRIPTION -> MqObjectType.SUBSCRIPTION
    com.mqclient.graph.TopologyNodeType.CHANNEL -> MqObjectType.CHANNEL
    com.mqclient.graph.TopologyNodeType.REMOTE_QMGR -> null
    // 集群关系图节点是 QMGR 角色视图，无对应的单对象属性页（QMGR 走连接属性面板）。
    com.mqclient.graph.TopologyNodeType.QMGR_FULL -> null
    com.mqclient.graph.TopologyNodeType.QMGR_PARTIAL -> null
    // Kafka / RabbitMQ 专属节点类型：不属 MQ 对象体系（属性面板由各模块自行演进）。
    com.mqclient.graph.TopologyNodeType.PARTITION -> null
    com.mqclient.graph.TopologyNodeType.BROKER -> null
    com.mqclient.graph.TopologyNodeType.CONSUMER_GROUP -> null
    com.mqclient.graph.TopologyNodeType.EXCHANGE -> null
    com.mqclient.graph.TopologyNodeType.RABBIT_QUEUE -> null
}

@Composable
private fun RowScope.FooterActionButton(label: String, onClick: () -> Unit) {
    SecondaryButton(
        onClick = onClick,
    ) { Text(label) }
}
