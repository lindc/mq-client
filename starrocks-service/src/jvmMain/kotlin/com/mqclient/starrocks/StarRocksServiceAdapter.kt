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
 * ## 刻意未注册 SPI
 *
 * 宿主通过 `META-INF/services/com.mqclient.core.ServiceAdapter` 发现模块。
 * 本模块**不提供**该注册文件：`initialize` / `dispose` 仍是 TODO，注册会让
 * 宿主启动时崩溃。填肉并接入 app-ui 依赖清单时再补（见【风险与待确认】）。
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

    /** 描述文案待协议实现后补齐（占位抛 NotImplementedError）。 */
    override val description: String
        get() = TODO("StarRocks protocol not implemented yet")

    /** 协议名。 */
    override val protocol: String = StarRocksConstants.PROTOCOL_NAME

    /** 类目取 olap-base 的 [com.mqclient.olap.base.OlapCategory.CATEGORY]。 */
    override val category: String = StarRocksConstants.CATEGORY

    /** 事件订阅 / 连接管理器初始化：协议逻辑待实现。 */
    override fun initialize(context: ServiceContext): Unit =
        TODO("StarRocks protocol not implemented yet")

    /** 空骨架不贡献导航项（返回空集合，不抛异常）。 */
    override fun navigationItems(connection: ConnectionInfo): List<NavigationItem> = emptyList()

    /** 空骨架不贡献徽章。 */
    override fun badgeCounts(connectionId: String): Flow<Map<String, Int>> = emptyFlow()

    /** 资源释放：协议逻辑待实现。 */
    override fun dispose(): Unit = TODO("StarRocks protocol not implemented yet")
}
