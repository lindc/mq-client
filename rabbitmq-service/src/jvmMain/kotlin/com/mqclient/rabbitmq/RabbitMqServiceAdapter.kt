package com.mqclient.rabbitmq

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.NavigationItem
import com.mqclient.core.spi.ServiceContext
import com.mqclient.mq.base.MQServiceAdapter

class RabbitMqServiceAdapter : MQServiceAdapter() {
    override val id = MODULE_ID // 模块名唯一定处：RabbitMqModule.kt 顶层 const
    override val name = "RabbitMQ"
    override val description = "RabbitMQ connection and management"
    override val protocol = "AMQP 0.9.1"
    // category = "MQ" 由 MQServiceAdapter 统一提供

    /**
     * 导航树二级菜单：工具类（拓扑图 / 概览）在前，分隔线之后是对象类型
     * （队列 / 交换机）—— 与 ibm-mq / kafka 的分组模式一致。纯数据 —— 点击行为
     * （开 Tab / 隐式连接）由宿主统一处理。拓扑图第一阶段暂无内容区，`contentFor`
     * 落到 null 由宿主渲染通用「coming soon」占位。
     */
    override fun navigationItems(connection: ConnectionInfo): List<NavigationItem> = listOf(
        // ===== 工具类 =====
        NavigationItem(id = "topology", titleKey = "rabbitmq_ui_nav_topology", iconId = "topology", isTool = true),
        NavigationItem(id = "overview", titleKey = "rabbitmq_ui_nav_overview", iconId = "clusterRelations", isTool = true),
        NavigationItem.separator("tools.objects"),
        // ===== 对象类型 =====
        NavigationItem(id = "queues", titleKey = "rabbitmq_ui_nav_queues", iconId = "queues"),
        NavigationItem(id = "exchanges", titleKey = "rabbitmq_ui_nav_exchanges", iconId = "exchanges"),
    )

    /**
     * SPI 发现后由 ModuleRegistry 调用：注册本模块的事件订阅
     * （核心 `core.connection.*` + 宿主的 connect/disconnect/test 动作请求）。
     */
    override fun initialize(context: ServiceContext) {
        RabbitMqModule.initialize(context)
    }

    /**
     * SPI 模块销毁：应用退出时由 ModuleRegistry 在 CoreModule 停机之前调用，
     * 转给 [RabbitMqModule.shutdown] 断开全部连接并取消本模块的订阅。
     */
    override fun dispose() {
        RabbitMqModule.shutdown()
    }
}
