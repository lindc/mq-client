package com.mqclient.rocketmq

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.NavigationItem
import com.mqclient.mq.base.MQServiceAdapter

class RocketMqServiceAdapter : MQServiceAdapter() {
    override val id = MODULE_ID // 模块名唯一定义处：RocketMqModule.kt 顶层 const
    override val name = "RocketMQ"
    override val description = "RocketMQ connection and management"
    override val protocol = "Remoting"
    // category = "MQ" 由 MQServiceAdapter 统一提供

    /**
     * 导航树二级菜单：工具类（拓扑图 / 集群概览）在前，分隔线之后是对象类型
     * （主题 / 消费组）。
     */
    override fun navigationItems(connection: ConnectionInfo): List<NavigationItem> = listOf(
        NavigationItem(id = "topology", titleKey = "rocketmq_ui_nav_topology", iconId = "topology", isTool = true),
        NavigationItem(id = "overview", titleKey = "rocketmq_ui_nav_overview", iconId = "clusterRelations", isTool = true),
        NavigationItem.separator("tools.objects"),
        NavigationItem(id = "topics", titleKey = "rocketmq_ui_nav_topics", iconId = "topics"),
        NavigationItem(id = "groups", titleKey = "rocketmq_ui_nav_groups", iconId = "groups"),
    )
}
