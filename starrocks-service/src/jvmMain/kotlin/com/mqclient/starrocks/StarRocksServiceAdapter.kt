package com.mqclient.starrocks

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.NavigationItem
import com.mqclient.core.ServiceAdapter
import com.mqclient.core.spi.ServiceContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * StarRocks service 层的 [ServiceAdapter] SPI **空骨架**实现。
 *
 * Skeleton only, protocol logic pending：仅暴露协议身份与 OLAP 分类，所有
 * 生命周期 / 导航 / 徽章方法保持占位（统一 `TODO` 或返回空集合），
 * **不实现**任何连接、目录扫描、查询执行逻辑。
 *
 * ## 为什么放在 jvmMain（而非 commonMain）
 *
 * 父接口 [ServiceAdapter] 定义在 app-core 的 **jvmMain** 源集，KMP 的
 * commonMain 编译期看不到它（mq-base 的 `MQServiceAdapter` KDoc 有同样说明）。
 * 因此本类落在 jvmMain；纯模型 / 常量留在 commonMain。
 *
 * ## SPI 注册
 *
 * 已通过 `src/jvmMain/resources/META-INF/services/com.mqclient.core.ServiceAdapter`
 * 注册，宿主 `ModuleRegistry` 用 ServiceLoader 发现它。新建连接弹窗的
 * **"OLAP" 分类就来自本类的 `category`**（`ModuleRegistry.groupByCategory`
 * 按 `ServiceAdapter.category` 分组，与 AdapterUI 无关）。分类值严格引用
 * [StarRocksConstants.CATEGORY] → `OlapCategory.CATEGORY`（"OLAP"），
 * 本类不硬编码分类字符串。
 *
 * 注册前必须保证 `description` / `initialize` / `dispose` 不会抛：宿主在
 * 渲染卡片时读 description，启动时调 initialize，退出时调 dispose。
 *
 * ## olap-base 接入点
 *
 * olap-base 目前只提供 `AbstractAnalyticsConnectionManager` /
 * `AbstractCatalogScanService` / `AbstractQueryExecutor` 三个模板与
 * [com.mqclient.olap.base.OlapCategory] 常量，**没有**独立的服务侧 SPI
 * 契约（无 `AnalyticsAdapter`）；故本类实现 app-core 的 [ServiceAdapter]。
 */
class StarRocksServiceAdapter : ServiceAdapter {

    /** 模块 id（与 AdapterUI.serviceId 一致）。 */
    override val id: String = StarRocksConstants.PROTOCOL_ID

    /** 模块展示名。 */
    override val name: String = StarRocksConstants.PROTOCOL_NAME

    /** 描述文案的 i18n 键（展示时由宿主 `stringResource` 查表，见 MiddlewareType）。 */
    override val description: String = StarRocksConstants.I18N_KEY_PREFIX + "description"

    /** 协议名。 */
    override val protocol: String = StarRocksConstants.PROTOCOL_NAME

    /** 类目取 olap-base 的 [com.mqclient.olap.base.OlapCategory.CATEGORY]。 */
    override val category: String = StarRocksConstants.CATEGORY

    /** 注入宿主上下文，建立可向 UI 提供 `testConnection` 的模块门面。 */
    override fun initialize(context: ServiceContext) = StarRocksModule.initialize(context)

    /**
     * 侧边栏二级菜单（与 RocketMQ 同构，树由 service 的 [ServiceAdapter.navigationItems]
     * 提供）：集群概览 / 数据目录 / SQL 控制台 / 拓扑结构 / 会话与慢查询。
     * 全部是占位入口 —— `contentFor` 按 objectType 分发，「console」渲染 Tab
     * 布局占位页，其余回落「模块开发中」占位。
     *
     * iconId 必须是宿主 `NavIcons` 已登记的 id（映射在 app-ui，模块不可改宿主）：
     * `clusterRelations` → Hub、`namelists` → List、`command` → Terminal、
     * `topology` → Map、`processDefinitions` → Description。
     * titleKey 走公共表 `olap.starrocks.menu.*`（与本模块其他文案同一前缀约定）。
     */
    override fun navigationItems(connection: ConnectionInfo): List<NavigationItem> = listOf(
        NavigationItem(id = "overview", titleKey = "olap.starrocks.menu.overview", iconId = "clusterRelations", isTool = true),
        NavigationItem(id = "catalog", titleKey = "olap.starrocks.menu.catalog", iconId = "namelists"),
        NavigationItem(id = "console", titleKey = "olap.starrocks.menu.console", iconId = "command", isTool = true),
        NavigationItem(id = "topology", titleKey = "olap.starrocks.menu.topology", iconId = "topology", isTool = true),
        NavigationItem(id = "queries", titleKey = "olap.starrocks.menu.queries", iconId = "processDefinitions"),
    )

    /** 空骨架不贡献徽章。 */
    override fun badgeCounts(connectionId: String): Flow<Map<String, Int>> = emptyFlow()

    /** 退出时摘掉模块门面引用。 */
    override fun dispose() = StarRocksModule.dispose()
}
