package com.mqclient.rocketmq

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.NavigationItem
import com.mqclient.core.spi.ServiceContext
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

    /**
     * SPI 发现后由 ModuleRegistry 调用：注册本模块的事件订阅
     * （核心 `core.connection.*` + 宿主的 connect/disconnect/test 动作请求）。
     * 缺了它 `RocketMqModule.testConnection` 会抛「RocketMQ 模块未初始化」——
     * 与 KafkaServiceAdapter / RabbitMqServiceAdapter 同构。
     */
    override fun initialize(context: ServiceContext) {
        RocketMqModule.initialize(context)
    }

    /**
     * SPI 模块销毁：应用退出时由 ModuleRegistry 调用，转给 [RocketMqModule.shutdown]
     * 断开全部连接并取消本模块的订阅。
     */
    override fun dispose() {
        RocketMqModule.shutdown()
    }
}
