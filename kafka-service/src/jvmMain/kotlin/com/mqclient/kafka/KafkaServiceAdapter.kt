package com.mqclient.kafka

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.NavigationItem
import com.mqclient.core.spi.ServiceContext
import com.mqclient.mq.base.MQServiceAdapter

class KafkaServiceAdapter : MQServiceAdapter() {
    override val id = MODULE_ID // 模块名唯一定处：KafkaModule.kt 顶层 const
    override val name = "Kafka"
    override val description = "Kafka connection and management"
    override val protocol = "Kafka Protocol"
    // category = "MQ" 由 MQServiceAdapter 统一提供

    /**
     * 导航树二级菜单：`topics`（Topic 列表）/ `groups`（Consumer Group 列表）
     * （id = Tab 的 objectType，宿主按 titleKey 解析文案、iconId 解析图标）。
     */
    override fun navigationItems(connection: ConnectionInfo): List<NavigationItem> = listOf(
        NavigationItem(id = "topics", titleKey = "kafka_ui_nav_topics", iconId = "topics"),
        NavigationItem(id = "groups", titleKey = "kafka_ui_nav_groups", iconId = "groups"),
    )

    /**
     * SPI 发现后由 ModuleRegistry 调用：注册本模块的事件订阅
     * （核心 `core.connection.*` + 宿主的 connect/disconnect/test 动作请求）。
     */
    override fun initialize(context: ServiceContext) {
        KafkaModule.initialize(context)
    }

    /**
     * SPI 模块销毁：应用退出时由 ModuleRegistry 在 CoreModule 停机之前调用，
     * 转给 [KafkaModule.shutdown] 断开全部连接并取消本模块的订阅。
     */
    override fun dispose() {
        KafkaModule.shutdown()
    }
}
