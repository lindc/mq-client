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
     * 导航树二级菜单：工具类（拓扑图 / 集群概览）在前，分隔线之后是对象类型
     * （主题 / Consumer Group）—— 与 ibm-mq 的分组模式一致。纯数据 —— 点击行为
     * （开 Tab / 隐式连接）由宿主统一处理。工具类暂无专属内容区，`contentFor`
     * 落到 null 由宿主渲染通用「coming soon」占位，后续实现时加分支即可。
     */
    override fun navigationItems(connection: ConnectionInfo): List<NavigationItem> = listOf(
        // ===== 工具类 =====
        NavigationItem(id = "topology", titleKey = "kafka_ui_nav_topology", iconId = "topology", isTool = true),
        NavigationItem(id = "clusterOverview", titleKey = "kafka_ui_nav_cluster", iconId = "clusterRelations", isTool = true),
        NavigationItem.separator("tools.objects"),
        // ===== 对象类型 =====
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
