package com.mqclient.ibmmq

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.NavigationItem
import com.mqclient.core.ServiceAdapter
import com.mqclient.core.spi.ServiceContext

class IbmMqServiceAdapter : ServiceAdapter {
    override val id = MODULE_ID // 模块名唯一定义处：IbmMQModule.kt 顶层 const
    override val name = "IBM MQ"
    override val description = "IBM MQ connection and management"
    override val protocol = "MQI"
    override val category = "MQ"

    /**
     * 导航树二级菜单：工具类（拓扑图 / 命令控制台）在前，分隔线之后是 MQ 对象类型
     * （队列 / 主题 / 订阅 / 通道 / 监听器 / 服务 / 进程定义 / 名称列表 / 认证信息 /
     * 通信信息 / 安全策略）。纯数据 —— 点击行为（开 Tab / 隐式连接）由宿主统一处理；
     * id 即 Tab 的 object type（`NavSelection.objectType`），宿主按 `nav_<id>` 解析文案。
     */
    override fun navigationItems(connection: ConnectionInfo): List<NavigationItem> = listOf(
        // ===== 工具类 =====
        NavigationItem(id = "topology", titleKey = "nav_topology", icon = "🗺️", isTool = true),
        NavigationItem(id = "command", titleKey = "nav_command", icon = "⌨️", isTool = true),
        NavigationItem.separator("tools.objects"),
        // ===== 对象类型 =====
        NavigationItem(id = "queues", titleKey = "nav_queues", icon = "📥"),
        NavigationItem(id = "topics", titleKey = "nav_topics", icon = "📢"),
        NavigationItem(id = "subscriptions", titleKey = "nav_subscriptions", icon = "📬"),
        NavigationItem(id = "channels", titleKey = "nav_channels", icon = "🔗"),
        NavigationItem(id = "listeners", titleKey = "nav_listeners", icon = "👂"),
        NavigationItem(id = "services", titleKey = "nav_services", icon = "⚙️"),
        NavigationItem(id = "processDefinitions", titleKey = "nav_process_definitions", icon = "📄"),
        NavigationItem(id = "namelists", titleKey = "nav_namelists", icon = "📋"),
        NavigationItem(id = "authInfo", titleKey = "nav_auth_info", icon = "🔐"),
        NavigationItem(id = "commInfo", titleKey = "nav_comm_info", icon = "📡"),
        NavigationItem(id = "securityPolicies", titleKey = "nav_security_policies", icon = "🛡️"),
    )

    /**
     * SPI 发现后由 ModuleRegistry 调用：注册本模块的事件订阅
     * （`ibm-mq.*` 前缀订阅 + 核心 `core.connection.deleted` 跨模块订阅）。
     */
    override fun initialize(context: ServiceContext) {
        IbmMQModule.initialize(context)
    }

    /**
     * SPI 模块销毁：应用退出时由 ModuleRegistry 在 CoreModule 停机之前调用，
     * 转给 [IbmMQModule.shutdown] 取消本模块的订阅、定时任务并释放服务资源。
     */
    override fun dispose() {
        IbmMQModule.shutdown()
    }
}
