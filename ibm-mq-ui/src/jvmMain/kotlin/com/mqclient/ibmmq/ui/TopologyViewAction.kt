package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import com.mqclient.core.topology.TopologyFocusHolder
import com.mqclient.mqui.objectlist.CardAction

/**
 * 列表卡片上的「在拓扑图中查看」动作：把对象名写入 [TopologyFocusHolder]（按连接隔离），
 * 宿主（MainWindow）订阅后把对应连接切到拓扑 Tab，面板打开时以该对象为中心展开 1 跳。
 *
 * 只挂在与拓扑相关的四类对象上（队列 / 通道 / 主题 / 订阅）——它们正是拓扑图的 7 类节点来源。
 */
fun topologyViewAction(connectionId: String, objectName: String): CardAction = CardAction(
    id = "object.view_topology",
    icon = Icons.Filled.AccountTree,
    tooltip = "ibm_mq_topology_view",
    onClick = { TopologyFocusHolder.set(connectionId = connectionId, rootName = objectName) },
)
