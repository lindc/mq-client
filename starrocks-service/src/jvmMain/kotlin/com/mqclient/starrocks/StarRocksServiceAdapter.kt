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

    /** S1 只有连接探针，没有事件订阅 / 定时任务；留空实现供 S2 接线。 */
    override fun initialize(context: ServiceContext) = Unit

    /** 空骨架不贡献导航项（返回空集合，不抛异常）。 */
    override fun navigationItems(connection: ConnectionInfo): List<NavigationItem> = emptyList()

    /** 空骨架不贡献徽章。 */
    override fun badgeCounts(connectionId: String): Flow<Map<String, Int>> = emptyFlow()

    /** S1 无长期持有的资源；`StarRocksConnectionManager.shutdown()` 待 S2 接线。 */
    override fun dispose() = Unit
}
