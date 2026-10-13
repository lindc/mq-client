package com.mqclient.ibmmq

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.NavigationItem
import com.mqclient.core.spi.ServiceContext
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.mq.base.MQServiceAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class IbmMqServiceAdapter : MQServiceAdapter() {
    override val id = MODULE_ID // 模块名唯一定义处：IbmMQModule.kt 顶层 const
    override val name = "IBM MQ"
    override val description = "IBM MQ connection and management"
    override val protocol = "MQI"
    // category = "MQ" 由 MQServiceAdapter 统一提供

    /**
     * 导航树二级菜单：工具类（拓扑图 / 命令控制台）在前，分隔线之后是 MQ 对象类型
     * （队列 / 主题 / 订阅 / 通道 / 监听器 / 服务 / 进程定义 / 名称列表 / 认证信息 /
     * 通信信息 / 安全策略）。纯数据 —— 点击行为（开 Tab / 隐式连接）由宿主统一处理；
     * id 即 Tab 的 object type（`NavSelection.objectType`），宿主按 `nav_<id>` 解析文案。
     * iconId 与 id 同值：宿主按它解析 Material Icons 图标（emoji 已废弃，Linux 无字体）。
     */
    override fun navigationItems(connection: ConnectionInfo): List<NavigationItem> = listOf(
        // ===== 工具类 =====
        NavigationItem(id = "topology", titleKey = "ibm_mq_nav_topology", iconId = "topology", isTool = true),
        NavigationItem(id = "clusterRelations", titleKey = "ibm_mq_nav_cluster_relations", iconId = "clusterRelations", isTool = true),
        NavigationItem(id = "command", titleKey = "ibm_mq_nav_command", iconId = "command", isTool = true),
        NavigationItem.separator("tools.objects"),
        // ===== 对象类型 =====
        NavigationItem(id = "queues", titleKey = "ibm_mq_nav_queues", iconId = "queues"),
        NavigationItem(id = "topics", titleKey = "ibm_mq_nav_topics", iconId = "topics"),
        NavigationItem(id = "subscriptions", titleKey = "ibm_mq_nav_subscriptions", iconId = "subscriptions"),
        NavigationItem(id = "channels", titleKey = "ibm_mq_nav_channels", iconId = "channels"),
        NavigationItem(id = "listeners", titleKey = "ibm_mq_nav_listeners", iconId = "listeners"),
        NavigationItem(id = "services", titleKey = "ibm_mq_nav_services", iconId = "services"),
        NavigationItem(id = "processDefinitions", titleKey = "ibm_mq_nav_process_definitions", iconId = "processDefinitions"),
        NavigationItem(id = "namelists", titleKey = "ibm_mq_nav_namelists", iconId = "namelists"),
        NavigationItem(id = "authInfo", titleKey = "ibm_mq_nav_auth_info", iconId = "authInfo"),
        NavigationItem(id = "commInfo", titleKey = "ibm_mq_nav_comm_info", iconId = "commInfo"),
    )

    /**
     * SPI 发现后由 ModuleRegistry 调用：注册本模块的事件订阅
     * （`ibm-mq.*` 前缀订阅 + 核心 `core.connection.deleted` 跨模块订阅）。
     */
    override fun initialize(context: ServiceContext) {
        IbmMQModule.initialize(context)
    }

    /**
     * 二级菜单的数量徽章（全面扫描后填充）：从 [IbmMQModule.objectCountsFlow] 派生，
     * 把对象类型键翻译成上面 [navigationItems] 的导航项 id（单一来源在这里 —— 两种
     * 拼写体系的不规则处如 `processDefinitions` / `authInfo` 只此一份）。扫描前键
     * 缺位 → badge = null 不显示；QMGR 类型无对应导航项，翻译时丢弃。
     */
    override fun badgeCounts(connectionId: String): Flow<Map<String, Int>> =
        IbmMQModule.objectCountsFlow.map { byConnection ->
            val counts = byConnection[connectionId].orEmpty()
            counts.entries
                .mapNotNull { (type, count) -> type.navItemId()?.let { it to count } }
                .toMap()
        }

    /** 对象类型 → 导航项 id；null = 无对应导航项（QMGR）。与 [navigationItems] 同步维护。 */
    private fun MqObjectType.navItemId(): String? = when (this) {
        MqObjectType.QUEUE -> "queues"
        MqObjectType.TOPIC -> "topics"
        MqObjectType.SUBSCRIPTION -> "subscriptions"
        MqObjectType.CHANNEL -> "channels"
        MqObjectType.LISTENER -> "listeners"
        MqObjectType.SERVICE -> "services"
        MqObjectType.PROCESS -> "processDefinitions"
        MqObjectType.NAMELIST -> "namelists"
        MqObjectType.AUTH_INFO -> "authInfo"
        MqObjectType.COMM_INFO -> "commInfo"
        MqObjectType.QUEUE_MANAGER -> null
    }

    /**
     * SPI 模块销毁：应用退出时由 ModuleRegistry 在 CoreModule 停机之前调用，
     * 转给 [IbmMQModule.shutdown] 取消本模块的订阅、定时任务并释放服务资源。
     */
    override fun dispose() {
        IbmMQModule.shutdown()
    }
}
