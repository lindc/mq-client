package com.mqclient.ibmmq.ui.properties

import androidx.compose.runtime.Composable
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.ui.properties.content.*
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
            MqObjectType.QUEUE -> QueuePropertiesContent(state)
            MqObjectType.TOPIC -> TopicPropertiesContent(state)
            MqObjectType.SUBSCRIPTION -> SubscriptionPropertiesContent(state)
            MqObjectType.CHANNEL -> ChannelPropertiesContent(state)
            MqObjectType.LISTENER -> ListenerPropertiesContent(state)
            MqObjectType.SERVICE -> ServicePropertiesContent(state)
            MqObjectType.PROCESS -> ProcessPropertiesContent(state)
            MqObjectType.NAMELIST -> NamelistPropertiesContent(state)
            MqObjectType.AUTH_INFO -> AuthInfoPropertiesContent(state)
            MqObjectType.COMM_INFO -> CommInfoPropertiesContent(state)
            // 不在属性对话框支持范围内的类型（QMGR 走 ConnectionPropertiesUi，其余不进 UI）：
            // 回落占位，标题栏已带对象名。
            else -> PropertiesPlaceholder(
                objectName = state.objectName,
                objectType = state.objectType.name,
            )
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
internal fun com.mqclient.ibmmq.ui.topology.TopologyNodeType.toMqObjectType(): MqObjectType? = when (this) {
    com.mqclient.ibmmq.ui.topology.TopologyNodeType.ALIAS_QUEUE -> MqObjectType.QUEUE
    com.mqclient.ibmmq.ui.topology.TopologyNodeType.LOCAL_QUEUE -> MqObjectType.QUEUE
    com.mqclient.ibmmq.ui.topology.TopologyNodeType.REMOTE_QUEUE -> MqObjectType.QUEUE
    com.mqclient.ibmmq.ui.topology.TopologyNodeType.TRANSMISSION_QUEUE -> MqObjectType.QUEUE
    com.mqclient.ibmmq.ui.topology.TopologyNodeType.TOPIC -> MqObjectType.TOPIC
    com.mqclient.ibmmq.ui.topology.TopologyNodeType.SUBSCRIPTION -> MqObjectType.SUBSCRIPTION
    com.mqclient.ibmmq.ui.topology.TopologyNodeType.CHANNEL -> MqObjectType.CHANNEL
    com.mqclient.ibmmq.ui.topology.TopologyNodeType.REMOTE_QMGR -> null
}
