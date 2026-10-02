package com.mqclient.ibmmq.ui.properties

import androidx.compose.runtime.Composable
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.ui.properties.content.ChannelPropertiesContent
import com.mqclient.ibmmq.ui.properties.content.MqObjectPropertiesContent
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource

/**
 * MQ 对象属性对话框：通用外壳（[ObjectDetailDialog]，undecorated 可拖动 / 可调大小）
 * + 按 [state.objectType] 分发到对应的内容组件（当前均为占位，逐步实装）。
 *
 * 状态由调用方持有（`var propertiesDialog by remember`），关闭时清空。
 */
@Composable
fun MqObjectPropertiesDialog(
    state: ObjectPropertiesDialogState,
    onDismiss: () -> Unit,
) {
    ObjectDetailDialog(
        title = state.objectName,
        onDismiss = onDismiss,
    ) {
        when (state.objectType) {
            MqObjectType.QUEUE -> MqObjectPropertiesContent(state, "queue_props_group_general") { resolveQueuePropertyGroups(it) }
            MqObjectType.TOPIC -> MqObjectPropertiesContent(state, "topic_props_group_general") { resolveTopicPropertyGroups(it) }
            MqObjectType.SUBSCRIPTION -> MqObjectPropertiesContent(state, "sub_props_group_general") { resolveSubscriptionPropertyGroups(it) }
            MqObjectType.CHANNEL -> ChannelPropertiesContent(state)
            MqObjectType.LISTENER -> MqObjectPropertiesContent(state, "listener_props_group_general") { resolveListenerPropertyGroups(it) }
            MqObjectType.SERVICE -> MqObjectPropertiesContent(state, "service_props_group_general") { resolveServicePropertyGroups(it) }
            MqObjectType.PROCESS -> MqObjectPropertiesContent(state, "process_props_group_general") { resolveProcessPropertyGroups(it) }
            MqObjectType.NAMELIST -> MqObjectPropertiesContent(state, "namelist_props_group_general") { resolveNamelistPropertyGroups(it) }
            MqObjectType.AUTH_INFO -> MqObjectPropertiesContent(state, "authinfo_props_group_general") { resolveAuthInfoPropertyGroups(it) }
            MqObjectType.COMM_INFO -> MqObjectPropertiesContent(state, "comminfo_props_group_general") { resolveCommInfoPropertyGroups(it) }
            // 10 类对象已全部实装；仅剩 QUEUE_MANAGER（走 ConnectionPropertiesUi，
            // 不会进本对话框）——此分支理论不可达，兜底空内容。
            else -> {}
        }
    }
}

/** [MqObjectType] → 类型显示名的 i18n 键（占位标题用）。 */
internal fun MqObjectType.displayNameKey(): String = when (this) {
    MqObjectType.QUEUE -> "object_type_queue"
    MqObjectType.TOPIC -> "object_type_topic"
    MqObjectType.SUBSCRIPTION -> "object_type_subscription"
    MqObjectType.CHANNEL -> "object_type_channel"
    MqObjectType.LISTENER -> "object_type_listener"
    MqObjectType.SERVICE -> "object_type_service"
    MqObjectType.PROCESS -> "object_type_process"
    MqObjectType.NAMELIST -> "object_type_namelist"
    MqObjectType.AUTH_INFO -> "object_type_auth_info"
    MqObjectType.COMM_INFO -> "object_type_comm_info"
    else -> "object_type_queue"
}

/** 便捷取类型显示名（i18n）。 */
@Composable
internal fun MqObjectType.displayName(): String = stringResource(displayNameKey())

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
}
